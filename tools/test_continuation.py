import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import continuation as c

class ContinuationTests(unittest.TestCase):
    def test_git_status_reads_live_untracked_inventory_without_config_writes(self):
        with patch.object(c.subprocess,'run') as command:
            command.return_value.stdout=b'?? synthetic-new-test.ps1\n'
            self.assertEqual('?? synthetic-new-test.ps1',c.git(Path('.'),'status','--porcelain'))
            args=command.call_args.args[0]
            self.assertIn('core.fsmonitor=false',args)
            self.assertIn('core.untrackedCache=false',args)
            self.assertEqual('0',command.call_args.kwargs['env']['GIT_OPTIONAL_LOCKS'])
            self.assertNotIn('config',args)
    def setUp(self):
        parent=Path(__file__).resolve().parent.parent/'.codex-continuation'/'test-work'
        parent.mkdir(parents=True,exist_ok=True)
        self.tmp=tempfile.TemporaryDirectory(prefix='owned-',dir=parent)
        self.root=Path(self.tmp.name);self.store=c.Store(self.root)
        self.payload={'taskScope':'synthetic','gitHead':'a','tasks':[{'id':'S0','title':'Synthetic checkpoint task','status':'RUNNING','dependsOn':[],'acceptance':'proof required','evidence':[]}],'protected':[]}
        self.store.save(copy.deepcopy(self.payload))
    def tearDown(self):self.tmp.cleanup()
    def test_corrupt_current_uses_previous(self):
        self.store.save(copy.deepcopy(self.payload));(self.store.folder/'STATE.json').write_text('{broken')
        self.assertEqual('STATE.previous.json',self.store.read()[1])
    def test_interrupted_replace_preserves_valid_checkpoint(self):
        before=(self.store.folder/'STATE.json').read_bytes();original=c.os.replace
        def fail(source,dest):
            if Path(dest).name=='STATE.json':raise OSError('synthetic power loss')
            return original(source,dest)
        with patch.object(c.os,'replace',side_effect=fail):
            with self.assertRaises(OSError):self.store.save(copy.deepcopy(self.payload))
        self.assertEqual(before,(self.store.folder/'STATE.json').read_bytes());self.store.read()
    def test_done_requires_actual_evidence(self):
        task=copy.deepcopy(self.payload);task['tasks'][0]['status']='DONE'
        with self.assertRaises(ValueError):self.store.save(task)
        proof=self.root/'proof.json';proof.write_text('{"passed":true,"exitCode":0}')
        task['tasks'][0]['evidence']=[{'path':'proof.json','sha256':c.digest(proof)}];self.store.save(task)
        proof.write_text('{"passed":false,"exitCode":1}')
        with patch.object(c,'git',return_value='a'):self.assertEqual(['S0'],self.store.report()['invalidEvidenceTasks'])
    def test_resume_is_idempotent_and_reports_git_changes(self):
        before={p.name:p.read_bytes() for p in self.store.folder.iterdir() if p.is_file()}
        with patch.object(c,'git',side_effect=['b',' M user.bat','b',' M user.bat']):a=self.store.report();b=self.store.report()
        self.assertEqual(a,b);self.assertTrue(a['headChanged']);self.assertIn('user.bat',a['workingTreeStatus']);self.assertEqual(['S0'],a['interruptedTasks'])
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.store.folder.iterdir() if p.is_file()})
    def test_interrupted_build_and_test_are_not_success(self):
        for status in ['RUNNING','TESTING']:
            data=copy.deepcopy(self.payload);data['tasks'][0]['status']=status;self.store.save(data)
            with patch.object(c,'git',return_value='a'):self.assertEqual(['S0'],self.store.report()['interruptedTasks'])
    def test_finished_artifact_does_not_automatically_promote_task(self):
        (self.root/'finished.json').write_text('{"passed":true,"exitCode":0}')
        with patch.object(c,'git',return_value='a'):self.store.report()
        self.assertEqual('RUNNING',self.store.read()[0]['payload']['tasks'][0]['status'])
    def test_invalid_graph_and_outside_paths_rejected(self):
        data=copy.deepcopy(self.payload);data['tasks'][0]['dependsOn']=['UNKNOWN']
        with self.assertRaises(ValueError):self.store.save(data)
        with self.assertRaises(ValueError):c.checked(self.root,self.root/'..'/'outside')
    def test_failed_evidence_cannot_mark_done(self):
        proof=self.root/'proof.json';proof.write_text('{"passed":false,"exitCode":1}')
        data=copy.deepcopy(self.payload);data['tasks'][0].update(status='DONE',evidence=[{'path':'proof.json','sha256':c.digest(proof)}])
        with self.assertRaises(ValueError):self.store.save(data)
    def test_parallel_commands_cannot_acquire_same_task_lock(self):
        with self.store.lock('command.lock'):
            with self.assertRaises(OSError):
                with self.store.lock('command.lock'):pass
            # State writes use a distinct, short-lived lock.
            self.store.save(copy.deepcopy(self.payload))
    def test_log_change_invalidates_command_evidence(self):
        log=self.root/'command.log';log.write_text('Synthetic result')
        proof=self.root/'proof.json';proof.write_bytes(c.encoded({'passed':True,'exitCode':0,'inputs':[{'path':'command.log','sha256':c.digest(log)}]}))
        record={'path':'proof.json','sha256':c.digest(proof)};self.assertTrue(c.evidence_valid(self.root,record));log.write_text('Changed result');self.assertFalse(c.evidence_valid(self.root,record))

if __name__=='__main__':unittest.main()
