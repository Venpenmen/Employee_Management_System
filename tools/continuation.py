"""Local-only development checkpoints. Resume never executes a pending task."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import uuid
from datetime import datetime, timezone

STATES = {'PENDING','RUNNING','IMPLEMENTED','TESTING','VERIFIED','BLOCKED','FAILED','DONE'}

def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')

def digest(path):
    target = Path(path)
    # A vanished artifact is an invalid evidence input, not a crash: retention and
    # manual clean-up may remove runs after they were recorded.
    if not target.is_file(): return None
    h = hashlib.sha256()
    with target.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            h.update(block)
    return h.hexdigest()

def evidence_valid(root, proof):
    path=checked(root,Path(root)/proof['path'])
    if digest(path)!=proof['sha256']:return False
    report=json.loads(path.read_text(encoding='utf-8-sig'))
    if report.get('passed') is not True or report.get('exitCode')!=0:return False
    for item in report.get('inputs',[]):
        if digest(checked(root,Path(root)/item['path']))!=item['sha256']:return False
    return True

def checked(root, path):
    root = Path(root).absolute()
    candidate = Path(os.path.abspath(path))
    if not candidate.is_relative_to(root):
        raise ValueError('Path outside declared project')
    for parent in [candidate, *candidate.parents]:
        if parent.exists() and getattr(parent.lstat(), 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            raise ValueError('Reparse paths refused')
        if parent == root:
            break
    return candidate

def git(root, *args):
    environment=os.environ.copy();environment['GIT_OPTIONAL_LOCKS']='0'
    # Read the live directory inventory even when an inherited Git fsmonitor or
    # untracked cache has not noticed newly created test/recovery scripts yet.
    # These are invocation-only options, never persistent repository settings.
    if args and args[0]=='status':
        args=('-c','core.fsmonitor=false','-c','core.untrackedCache=false',*args)
    result = subprocess.run(['git','-C',str(root),*args], capture_output=True, check=True,env=environment)
    return result.stdout.decode('utf-8', errors='replace').strip()

def atomic(path, content):
    part = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with part.open('xb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(part, path)
    finally:
        if part.exists():
            part.unlink()

def process_identity(pid, expected_ticks):
    if os.name!='nt':return {'identity':'not-verified','reason':'Windows process identity required'}
    import ctypes
    from ctypes import wintypes
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x1000,False,int(pid))
    if not handle:return {'identity':'not-verified','reason':'exited or access unavailable'}
    try:
        creation=wintypes.FILETIME();exit_time=wintypes.FILETIME();system=wintypes.FILETIME();user=wintypes.FILETIME()
        kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,*([ctypes.POINTER(wintypes.FILETIME)]*4)]
        if not kernel.GetProcessTimes(handle,ctypes.byref(creation),ctypes.byref(exit_time),ctypes.byref(system),ctypes.byref(user)):return {'identity':'not-verified'}
        ticks=(creation.dwHighDateTime<<32)|creation.dwLowDateTime
        buffer=ctypes.create_unicode_buffer(32768);size=wintypes.DWORD(len(buffer))
        kernel.QueryFullProcessImageNameW.argtypes=[wintypes.HANDLE,wintypes.DWORD,wintypes.LPWSTR,ctypes.POINTER(wintypes.DWORD)]
        if not kernel.QueryFullProcessImageNameW(handle,0,buffer,ctypes.byref(size)):return {'identity':'not-verified'}
        return {'identity':'same-start-time' if str(ticks)==str(expected_ticks) else 'PID-reused','startTicks':str(ticks),'executable':buffer.value,'readOnly':True}
    finally:kernel.CloseHandle(handle)

class Store:
    def __init__(self, root):
        self.root = Path(root).absolute()
        self.folder = checked(self.root, self.root/'.codex-continuation')

    @contextlib.contextmanager
    def lock(self, name="state.lock"):
        self.folder.mkdir(exist_ok=True)
        lockfile = checked(self.root,self.folder/name)
        with lockfile.open('a+b') as stream:
            if os.name == 'nt':
                import msvcrt
                if lockfile.stat().st_size == 0:
                    stream.write(b'0'); stream.flush()
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK,1)
                try: yield
                finally: stream.seek(0); msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)
            else:
                import fcntl
                fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
                try: yield
                finally: fcntl.flock(stream,fcntl.LOCK_UN)

    def valid(self, path):
        value = json.loads(checked(self.root,path).read_text(encoding='utf-8'))
        return self.validate(value)

    def validate(self, value):
        if value['schemaVersion'] != 1 or hashlib.sha256(encoded(value['payload'])).hexdigest() != value['sha256']:
            raise ValueError('Checkpoint checksum mismatch')
        tasks = value['payload']['tasks']
        ids = [t['id'] for t in tasks]
        if len(ids) != len(set(ids)):
            raise ValueError('Duplicate task ID')
        completed = set()
        for task in tasks:
            if task['status'] not in STATES or not task.get('title') or not task['acceptance'] or not set(task['dependsOn']).issubset(completed):
                raise ValueError('Invalid task graph or task state')
            completed.add(task['id'])
        return value

    def read(self):
        errors=[];valid=[]
        for name in ('STATE.json','STATE.previous.json'):
            try: valid.append((self.valid(self.folder/name),name))
            except (OSError,ValueError,KeyError,TypeError) as error: errors.append(type(error).__name__)
        if valid:return max(valid,key=lambda item:(item[0]['sequence'],item[1]=='STATE.json'))
        raise ValueError('No valid checkpoint: '+','.join(errors))

    def save(self, payload):
        with self.lock():
            sequence=1
            try:
                prior,_=self.read(); sequence=prior['sequence']+1
                atomic(self.folder/'STATE.previous.json',encoded(prior))
            except ValueError:
                if (self.folder/'STATE.json').exists() or (self.folder/'STATE.previous.json').exists():
                    raise
            payload['savedAt']=datetime.now(timezone.utc).isoformat()
            value={'schemaVersion':1,'sequence':sequence,'sha256':hashlib.sha256(encoded(payload)).hexdigest(),'payload':payload}
            self.validate(value)
            # Validate before replacing the active checkpoint. Evidence that vanished
            # after it was recorded (retention or manual clean-up) is surfaced by
            # report() as invalidEvidenceTasks but must not wedge every later save;
            # only evidence that is newly invalid still fails the save.
            prior_invalid=set()
            try:
                for task in prior['payload']['tasks']:
                    for proof in task.get('evidence') or []:
                        try:
                            if not evidence_valid(self.root,proof):prior_invalid.add(task['id'])
                        except (OSError,ValueError,KeyError,TypeError):prior_invalid.add(task['id'])
            except (NameError,KeyError,TypeError):prior_invalid=set()
            for task in payload['tasks']:
                if task['status'] not in STATES: raise ValueError('Invalid status')
                if task['status'] in ('VERIFIED','DONE'):
                    if not task.get('evidence'): raise ValueError('Verification requires evidence')
                    for proof in task['evidence']:
                        if not evidence_valid(self.root,proof) and task['id'] not in prior_invalid:
                            raise ValueError('Verification evidence invalid: '+task['id'])
            atomic(self.folder/'STATE.json',encoded(value))
            self.valid(self.folder/'STATE.json')
            self.render(payload)
            return value

    def render(self,payload):
        for filename, selected in [('COMPLETED.md',{'DONE','VERIFIED'}),('PENDING.md',STATES-{'DONE','VERIFIED'})]:
            lines=['# '+filename[:-3], '', *[f"- {t['id']} [{t['status']}] {t['title']}" for t in payload['tasks'] if t['status'] in selected], '']
            atomic(self.folder/filename, '\n'.join(lines).encode('utf-8'))

    def report(self):
        value,source=self.read(); payload=value['payload']
        current=git(self.root,'rev-parse','HEAD')
        changes=git(self.root,'status','--porcelain','--untracked-files=normal')
        invalid=[]
        for task in payload['tasks']:
            for proof in task.get('evidence',[]):
                try:
                    if not evidence_valid(self.root,proof):invalid.append(task['id'])
                except (OSError,ValueError,KeyError): invalid.append(task['id'])
        protected=[]
        for record in payload.get('protected',[]):
            try:
                if digest(checked(self.root,self.root/record['path']))!=record['sha256']:protected.append(record['path'])
            except OSError:protected.append(record['path'])
        manifests=[]
        runs=self.root/'.release-work/runs'
        if runs.exists():
            for folder in runs.iterdir():
                if folder.name.startswith('.'):continue
                file=checked(self.root,folder/'run.json')
                if file.exists():
                    try:
                        m=json.loads(file.read_text(encoding='utf-8-sig'))
                        owned=m.get('ownerTaskId')==payload['taskScope']
                        identity=process_identity(m['ownerId'],m['ownerTicks']) if owned else {'identity':'protected non-task manifest; not inspected'}
                        manifests.append({'id':folder.name,'status':m.get('status'),'currentTaskOwned':owned,'ownerProcess':identity,'action':'inspect full PID/start-time/executable before any retry; never auto-stop'})
                    except ValueError: manifests.append({'id':folder.name,'action':'unknown manifest; retain'})
        active=[]
        for task in payload['tasks']:
            command=task.get('activeCommand')
            if command:active.append({'task':task['id'],'process':process_identity(command['pid'],command.get('startTicks','')),'action':'read-only; inspect before retry'})
        return {'mode':'read-only','checkpointSource':source,'sequence':value['sequence'],'head':current,'headChanged':current!=payload['gitHead'],'workingTreeStatus':changes,'invalidEvidenceTasks':sorted(set(invalid)),'changedProtectedFiles':protected,'interruptedTasks':[t['id'] for t in payload['tasks'] if t['status'] in ('RUNNING','TESTING')],'activeCommands':active,'taskManifests':manifests,'nextTasks':[t['id'] for t in payload['tasks'] if t['status'] not in ('VERIFIED','DONE')],'automaticExecution':False}

def initialize(root):
    store=Store(root)
    if (store.folder/'STATE.json').exists():raise ValueError('Checkpoint already exists; use resume')
    tasks=[]
    titles=[('S0','Permanent checkpoints and recovery fault tests'),('S1','Registered version conversion and extensibility'),('S2','Controlled legacy lineage and safe roundtrip'),('S3','Compatibility UI and authenticated confirmation'),('S4','Local LAN and isolated trusted proxy modes'),('S5','Account security and controlled secondary recovery'),('S6','Cross-module integration'),('S7','Full regression and failure recovery'),('S8','Candidate packaging and extracted runtime verification'),('S9','Documentation protection audit and local delivery')]
    dependencies={'S0':[],'S1':['S0'],'S2':['S1'],'S3':['S2'],'S4':['S0'],'S5':['S0'],'S6':['S3','S4','S5'],'S7':['S6'],'S8':['S7'],'S9':['S8']}
    acceptance={
      'S0':'Atomic state, previous-state recovery, lock, evidence validation, read-only resume, interruption simulations and task-scoped cleanup tests pass',
      'S1':'Registered 113/120/121 schemas, exact mapping, encrypted companion and future converter interfaces tested; unknown structures rejected',
      'S2':'Real old binary roundtrip with transactional lineage, insert/update/delete/reuse/cascade/rollback/replay cases; security state never revived',
      'S3':'Authenticated UI supports export/upload/preview/conflict choices and report-bound confirmation; no early switch',
      'S4':'Local/LAN/isolated TLS proxy tests; unsafe compatibility restricted to owned fixtures; live FRP unchanged',
      'S5':'Existing session/reset/freeze controls reviewed; independently approved local secondary recovery and concurrency tests pass',
      'S6':'Cross-module transaction, ownership, backup and authorization scenarios pass',
      'S7':'Offline Maven, actual old runtime, HTTP/browser/business/Excel/security/failure regression evidence passes; external limits explicit',
      'S8':'Fresh complete candidate ZIPs/source/tools, hashes, extracted portable/server lifecycle and persistence pass',
      'S9':'Final report traces evidence, protected hashes unchanged, owned cleanup verified, no remote release'}
    for number,(id,title) in enumerate(titles):tasks.append({'id':id,'title':title,'dependsOn':dependencies[id],'status':'RUNNING' if number==0 else 'PENDING','acceptance':acceptance[id],'evidence':[]})
    protected=[]
    for name in ['网络运行模式设置.bat','docs/DATABASE_COMPATIBILITY_HANDOFF_20261028.md']:
        file=checked(store.root,store.root/name)
        if file.exists():protected.append({'path':name,'sha256':digest(file)})
    for scope in ['dist/database-compat-p0-20261028-final','backend/src/main/resources/db/upgrade']:
        folder=checked(store.root,store.root/scope)
        for file in folder.iterdir():
            if file.is_file():protected.append({'path':file.relative_to(store.root).as_posix(),'sha256':digest(file)})
    payload={'taskScope':str(uuid.uuid4()),'gitHead':git(root,'rev-parse','HEAD'),'branch':git(root,'branch','--show-current'),'currentTask':'S0','lastSuccessfulCheckpoint':None,'protected':protected,'tasks':tasks,'networkPolicy':'offline; no production or external services','nextAction':'verify S0 before S1'}
    store.save(payload)
    docs={
     'MASTER_PLAN.md':'# EMS v1.2.1 master plan\n\nS0 checkpoints first; S1 versions; S2 lineage; S3 UI; S4 networks; S5 security; S6 integration; S7 full tests; S8 packages; S9 local delivery. Task states and dependencies are in STATE.json. DONE requires verified evidence. No production mutation, remote operations or external agents.\n',
     'DECISIONS.md':'# Decisions\n\nFrozen/TEMP/REISSUE accounts block downgrade. Controlled old copies use separate lineage schema. Unprovable identity never auto-restores. Insecure compatibility is isolated-test-only. Secondary recovery is local and independently approved. Preserve all historical artifacts.\n',
     'BLOCKERS.md':'# Blockers\n\nNo authorization to change existing services, production data, FRP or system configuration. Missing offline dependencies require explicit download authorization. Unknown ownership means retain resources.\n',
     'TEST_RESULTS.md':'# Actual tests\n\nHistorical P1 foundation: 72 Maven tests reported by p1-foundation-test.log; not a full P1 acceptance. S0 fault tests pending. No database roundtrip completion claimed.\n',
     'RECOVERY.md':'# Recovery\n\nRun tools/resume-development.ps1. It only reads and prints findings. Check actual HEAD, dirty files, evidence hashes and task manifests. Never trust STATE alone, reset Git, delete unknown resources or automatically replay a migration. Inspect process PID/start-time/executable through owned manifests before actions.\n',
     'SESSION_LOG.md':'# Sessions\n\n2026-10-29: S0 initialized from 729caf4; original dirty files preserved.\n',
     'RESUME_PROMPT.md':'Resume the existing EMS v1.2.1 total task. Read .codex-continuation/STATE.json and RECOVERY.md, run the read-only resume tool, verify Git and ownership. Continue pending tasks without reinitializing. Preserve original dirty files, RC2/P0 artifacts and active services. Offline tools only. Do not announce delivery before required evidence passes.\n'}
    for name,text in docs.items():atomic(store.folder/name,text.encode('utf-8'))
    return store.report()

def rc2_tasks():
    titles=['Recovery and continuation compatibility','Network modes and entry isolation','Loopback and dual connector validation','Unified independent server runtime','Database management layering','Bound restore summary and confirmation','Affected security and integration regression','RC2 candidate packaging and final audit']
    return [{'id':f'RC2-P{i}','title':title,'dependsOn':['S9' if i==0 else f'RC2-P{i-1}'],'status':'PENDING','acceptance':'Actual phase evidence, protected history and security controls verified; no production or remote mutations','evidence':[]} for i,title in enumerate(titles)]

def append_rc2(store):
    # Same command lock as builds/tests; never append under a running command.
    with store.lock('command.lock'):
        value,source=store.read();payload=value['payload']
        history=[t for t in payload['tasks'] if not t['id'].startswith('RC2-')]
        if [t['id'] for t in history]!=[f'S{i}' for i in range(10)]:raise ValueError('Expected complete original S0-S9 history')
        if any(t.get('activeCommand') for t in payload['tasks']):raise ValueError('Inspect interrupted/active command before appending')
        for t in history:
            if t['status'] not in ('DONE','VERIFIED') or not t.get('evidence'):raise ValueError('Historical task not accepted')
            for proof in t['evidence']:
                if not evidence_valid(store.root,proof):raise ValueError('Historical evidence changed')
        for item in payload.get('protected',[]):
            if digest(checked(store.root,store.root/item['path']))!=item['sha256']:raise ValueError('Protected file changed')
        definitions=rc2_tasks();existing=[t for t in payload['tasks'] if t['id'].startswith('RC2-')]
        if existing:
            if len(existing)!=len(definitions) or any(any(a[k]!=b[k] for k in ('id','title','dependsOn','acceptance')) for a,b in zip(existing,definitions)):raise ValueError('Partial/conflicting RC2 stages; no changes')
            return {'appended':False,'sequence':value['sequence'],'reason':'identical definitions already registered'}
        before=encoded(history);scope=payload['taskScope'];protected=encoded(payload.get('protected',[]))
        backup=checked(store.root,store.folder/'checkpoints'/f"pre-rc2-{value['sequence']}.json");backup.parent.mkdir(exist_ok=True)
        if backup.exists():
            if backup.read_bytes()!=encoded(value):raise ValueError('Recovery point conflict')
        else:
            atomic(backup,encoded(value));backup.chmod(stat.S_IREAD)
        # Mutate a decoded copy only after every gate and recovery point succeeds.
        updated=json.loads(encoded(payload));updated['tasks'].extend(definitions)
        updated['gitHead']=git(store.root,'rev-parse','HEAD');updated['currentTask']='RC2-P0';updated['nextAction']='Verify RC2-P0; preserve original S0-S9 acceptance'
        if encoded(updated['tasks'][:10])!=before or updated['taskScope']!=scope or encoded(updated.get('protected',[]))!=protected:raise ValueError('History preservation assertion failed')
        store.save(updated)
        return {'appended':True,'sequence':store.read()[0]['sequence'],'historyUnchanged':True,'recoveryPoint':backup.relative_to(store.root).as_posix()}

def run_command(args,store):
    with store.lock('command.lock'):
        command=args.arguments
        if command and command[0]=='--':command=command[1:]
        if not command:raise ValueError('Provide a local command after --')
        value,_=store.read();payload=value['payload'];task=next(t for t in payload['tasks'] if t['id']==args.task)
        if any(next(t for t in payload['tasks'] if t['id']==id)['status'] not in ('VERIFIED','DONE') for id in task['dependsOn']):raise ValueError('Dependencies not verified')
        executable=Path(command[0]).name.lower()
        if executable not in ('mvn.cmd','java.exe','powershell.exe','pwsh.exe','python.exe','python','node.exe','git.exe','git'):raise ValueError('Unregistered local development executable')
        if executable=='mvn.cmd' and '-o' not in command and '--offline' not in command:raise ValueError('Maven must run offline')
        if executable in ('git','git.exe'):raise ValueError('Git mutations require explicit reviewed file lists, not this runner')
        logfolder=checked(store.root,store.folder/'evidence');logfolder.mkdir(exist_ok=True)
        label=args.task+'-'+uuid.uuid4().hex
        log=logfolder/(label+'.log');proof=logfolder/(label+'.json')
        task['status']='TESTING';payload['gitHead']=git(store.root,'rev-parse','HEAD');payload['currentTask']=args.task;store.save(payload)
        environment=os.environ.copy();environment['EMS_DEVELOPMENT_TASK_ID']=payload['taskScope']
        temporary=checked(store.root,store.folder/'test-work'/label)
        temporary.mkdir(parents=True)
        environment['TEMP']=str(temporary);environment['TMP']=str(temporary)
        # Child-only settings: do not alter the user's system or global environment.
        environment['JAVA_TOOL_OPTIONS']=environment.get('JAVA_TOOL_OPTIONS','')+' -Djava.io.tmpdir="'+str(temporary)+'"'
        with log.open('xb') as output:
            process=subprocess.Popen(command,cwd=store.root,env=environment,stdout=output,stderr=subprocess.STDOUT)
            identity=process_identity(process.pid,'')
            task['activeCommand']={'pid':process.pid,'executable':executable,'startTicks':identity.get('startTicks'),'log':log.relative_to(store.root).as_posix()};store.save(payload)
            code=process.wait()
        atomic(proof,encoded({'passed':code==0,'exitCode':code,'executable':executable,'scope':'command only; not whole-module acceptance','logSha256':digest(log),'inputs':[{'path':log.relative_to(store.root).as_posix(),'sha256':digest(log)}]}))
        task.pop('activeCommand',None);task['status']='RUNNING' if code==0 else 'FAILED';task.setdefault('checks',[]).append({'path':proof.relative_to(store.root).as_posix(),'sha256':digest(proof)});store.save(payload)
        print(json.dumps(store.report(),ensure_ascii=False,indent=2));sys.exit(code)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['init','resume','checkpoint','run','append-rc2']);parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parent.parent);parser.add_argument('--task');parser.add_argument('--status',choices=sorted(STATES));parser.add_argument('--evidence',type=Path)
    tokens=sys.argv[1:];separator=tokens.index('--') if '--' in tokens else len(tokens)
    args=parser.parse_args(tokens[:separator]);args.arguments=tokens[separator+1:];store=Store(args.root)
    if args.command=='init':result=initialize(args.root)
    elif args.command=='resume':result=store.report()
    elif args.command=='append-rc2':result=append_rc2(store)
    elif args.command=='run':run_command(args,store)
    else:
        value,_=store.read();payload=value['payload']
        task=next(t for t in payload['tasks'] if t['id']==args.task)
        if any(next(t for t in payload['tasks'] if t['id']==id)['status'] not in ('VERIFIED','DONE') for id in task['dependsOn']):raise ValueError('Dependencies not verified')
        task['status']=args.status
        if args.evidence:
            evidence=checked(store.root,args.evidence.absolute());task['evidence']=[{'path':evidence.relative_to(store.root).as_posix(),'sha256':digest(evidence)}]
        payload['gitHead']=git(store.root,'rev-parse','HEAD');payload['currentTask']=args.task
        if args.status in ('VERIFIED','DONE'):payload['lastSuccessfulCheckpoint']=args.task
        store.save(payload);result=store.report()
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
