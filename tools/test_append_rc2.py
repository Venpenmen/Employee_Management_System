import copy
import json
import os
import stat
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import continuation as c

class AppendRc2Tests(unittest.TestCase):
 def setUp(self):
  parent=Path(__file__).resolve().parent.parent/'.codex-continuation/test-work';parent.mkdir(exist_ok=True)
  self.tmp=tempfile.TemporaryDirectory(prefix='append-rc2-fixture-',dir=parent);self.root=Path(self.tmp.name);self.store=c.Store(self.root)
  proof=self.root/'proof.json';proof.write_bytes(c.encoded({'passed':True,'exitCode':0}))
  sentinel=self.root/'user.bat';sentinel.write_bytes(b'UNCHANGED')
  self.payload={'taskScope':'synthetic-scope','gitHead':'base','protected':[{'path':'user.bat','sha256':c.digest(sentinel)}],'tasks':[{'id':f'S{i}','title':f'History {i}','dependsOn':[] if i==0 else [f'S{i-1}'],'acceptance':'actual evidence required','status':'DONE','evidence':[{'path':'proof.json','sha256':c.digest(proof)}]} for i in range(10)]}
  self.store.save(copy.deepcopy(self.payload));self.mock=patch.object(c,'git',return_value='new-head');self.mock.start()
 def tearDown(self):
  self.mock.stop()
  for p in self.root.rglob('*'):
   if p.is_file():p.chmod(stat.S_IREAD|stat.S_IWRITE)
  self.tmp.cleanup()
 def test_append_preserves_history_scope_protection_and_readonly_backup(self):
  before=self.store.read()[0];r=c.append_rc2(self.store);after=self.store.read()[0]
  self.assertTrue(r['appended']);self.assertEqual(before['payload']['tasks'],after['payload']['tasks'][:10]);self.assertEqual(before['payload']['protected'],after['payload']['protected']);self.assertEqual('synthetic-scope',after['payload']['taskScope']);self.assertEqual(18,len(after['payload']['tasks']))
  saved=self.root/r['recoveryPoint'];self.assertEqual(before,json.loads(saved.read_text(encoding='utf-8')))
  if os.name=='nt':self.assertTrue(saved.stat().st_file_attributes&stat.FILE_ATTRIBUTE_READONLY)
 def test_repeat_is_byte_idempotent_even_after_phase_progress(self):
  c.append_rc2(self.store);v=self.store.read()[0]['payload'];v['tasks'][10]['status']='RUNNING';self.store.save(v)
  before={p.name:p.read_bytes() for p in self.store.folder.iterdir() if p.is_file()}
  self.assertFalse(c.append_rc2(self.store)['appended']);self.assertEqual(before,{p.name:p.read_bytes() for p in self.store.folder.iterdir() if p.is_file()})
 def test_partial_or_conflicting_definitions_rejected(self):
  for conflict in (False,True):
   v=copy.deepcopy(self.payload);stages=c.rc2_tasks();v['tasks']+=stages if conflict else stages[:1]
   if conflict:v['tasks'][10]['title']='unexpected'
   self.store.save(v);before=(self.store.folder/'STATE.json').read_bytes()
   with self.assertRaises(ValueError):c.append_rc2(self.store)
   self.assertEqual(before,(self.store.folder/'STATE.json').read_bytes())
 def test_command_lock_conflict_rejected(self):
  with self.store.lock('command.lock'):
   with self.assertRaises(OSError):c.append_rc2(self.store)
 def test_state_lock_conflict_rejected_without_replacing_state(self):
  before=(self.store.folder/'STATE.json').read_bytes()
  with self.store.lock('state.lock'):
   with self.assertRaises(OSError):c.append_rc2(self.store)
  self.assertEqual(before,(self.store.folder/'STATE.json').read_bytes())
 def test_interrupted_atomic_write_can_retry(self):
  original=c.os.replace;before=(self.store.folder/'STATE.json').read_bytes()
  def fail(a,b):
   if Path(b).name=='STATE.json':raise OSError('synthetic interruption')
   return original(a,b)
  with patch.object(c.os,'replace',side_effect=fail):
   with self.assertRaises(OSError):c.append_rc2(self.store)
  self.assertEqual(before,(self.store.folder/'STATE.json').read_bytes());self.assertTrue(c.append_rc2(self.store)['appended'])
 def test_corrupt_current_uses_valid_previous(self):
  self.store.save(copy.deepcopy(self.payload));(self.store.folder/'STATE.json').write_bytes(b'{broken')
  self.assertTrue(c.append_rc2(self.store)['appended']);self.assertEqual(18,len(self.store.read()[0]['payload']['tasks']))
 def test_changed_protected_or_evidence_refuses(self):
  for name in ('user.bat','proof.json'):
   p=self.root/name;before=p.read_bytes();p.write_bytes(b'changed')
   with self.assertRaises((ValueError,json.JSONDecodeError)):c.append_rc2(self.store)
   p.write_bytes(before)
 def test_active_command_and_unfinished_history_refuse(self):
  for change in ('active','unfinished'):
   v=copy.deepcopy(self.payload)
   if change=='active':v['tasks'][0]['activeCommand']={'pid':123}
   else:v['tasks'][0]['status']='RUNNING'
   self.store.save(v)
   with self.assertRaises(ValueError):c.append_rc2(self.store)

if __name__=='__main__':unittest.main()
