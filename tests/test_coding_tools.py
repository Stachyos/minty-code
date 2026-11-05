"""Coding policies and real isolated test execution; no paid model calls."""
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import subprocess
import uuid
from unittest.mock import patch

from coding_tools import CodingTools, LifecycleHooks, digest
import worker
import test_worker as fixtures
from test_worker import Block, Stream, response


class CodingToolsTest(unittest.TestCase):
    def test_search_read_lines_and_paths_are_bounded(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'code.py').write_text('first\nneedle\nlast\n',encoding='utf-8')
            (root/'.env').write_text('needle=never-search',encoding='utf-8')
            (root/'node_modules').mkdir(); (root/'node_modules/no.py').write_text('needle')
            tools=CodingTools(root)
            self.assertEqual(['code.py'],[item['path'] for item in tools.list_files()['files']])
            self.assertEqual(2,tools.search_text('needle')['matches'][0]['line'])
            self.assertEqual('2: needle',tools.read_file('code.py',start_line=2,end_line=2)['content'])
            for path in ('../outside','.env','C:\\Windows\\secret','nested/.token'):
                with self.subTest(path=path),self.assertRaises(ValueError): tools.path(path)

    def test_multifile_patch_prevalidates_and_preserves_concurrent_edits(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'a.py').write_text('old-a'); (root/'b.py').write_text('old-b')
            tools=CodingTools(root); a=tools.read_file('a.py'); b=tools.read_file('b.py')
            (root/'b.py').write_text('user edit')
            with self.assertRaisesRegex(ValueError,'changed'):
                tools.apply_patch([{'path':'a.py','old_text':'old-a','new_text':'new-a','expected_sha256':a['sha256']},
                                   {'path':'b.py','old_text':'old-b','new_text':'new-b','expected_sha256':b['sha256']}])
            self.assertEqual('old-a',(root/'a.py').read_text()); self.assertEqual('user edit',(root/'b.py').read_text())

    def test_failed_multifile_write_rolls_back_our_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'a').write_text('a'); (root/'b').write_text('b'); tools=CodingTools(root)
            original=tools.atomic_write
            def fail_b(path,data):
                if path.name=='b': raise OSError('controlled write failure')
                original(path,data)
            with patch.object(tools,'atomic_write',side_effect=fail_b),self.assertRaises(OSError):
                tools.apply_patch([{'path':name,'old_text':name,'new_text':'new','expected_sha256':digest(name.encode())} for name in ('a','b')])
            self.assertEqual('a',(root/'a').read_text()); self.assertEqual('b',(root/'b').read_text())

    def test_diff_includes_new_deleted_and_modified_files_without_git(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'gone').write_text('gone\n'); (root/'edit').write_text('before\n'); tools=CodingTools(root)
            (root/'gone').unlink(); (root/'edit').write_text('after\n'); tools.write_file('new.py','created\n')
            self.assertEqual({'gone':'deleted','edit':'modified','new.py':'created'}, {item['path']:item['kind'] for item in tools.get_diff()['files']})

    def test_ambiguous_match_does_not_modify_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'file').write_text('same same'); tools=CodingTools(root)
            with self.assertRaisesRegex(ValueError,'exactly once'): tools.edit_file('file','same','different')
            self.assertEqual('same same',(root/'file').read_text())

    def test_verification_invalidates_after_edit_and_flags_modified_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'code.py').write_text('before'); (root/'test_code.py').write_text('test'); tools=CodingTools(root)
            tools.write_file('code.py','fixed')
            tools.last_test={'status':'passed','fingerprint':tools.get_diff()['fingerprint']}
            self.assertEqual('verified',tools.assessment()['outcome'])
            tools.write_file('code.py','different')
            self.assertEqual('unverified',tools.assessment()['outcome'])
            tools.write_file('test_code.py','weakened test')
            tools.last_test={'status':'passed','fingerprint':tools.get_diff()['fingerprint']}
            self.assertEqual('unverified',tools.assessment()['outcome'])
            self.assertEqual(['test_code.py'],tools.assessment()['modifiedTests'])

    def test_maven_configuration_changes_need_review_even_with_passing_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root/'pom.xml').write_text('<project/>'); tools = CodingTools(root)
            tools.write_file('pom.xml', '<project>changed test configuration</project>')
            tools.last_test = {'status': 'passed', 'fingerprint': tools.get_diff()['fingerprint']}
            self.assertEqual('unverified', tools.assessment()['outcome'])
            self.assertEqual(['pom.xml'], tools.assessment()['modifiedTests'])

    def test_hook_can_block_write_before_dispatch(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); tools=CodingTools(root); hooks=LifecycleHooks()
            def block(context): raise ValueError('policy blocked')
            hooks.on('before_tool',block)
            call=Block(type='tool_use',id='write',name='write_file',input={'path':'blocked.py','content':'bad'})
            core=fixtures.WorkerTest().core(root,iter([Stream(response([call],'tool_use')),Stream(response([]))]),lambda **kw:'unused')
            output=io.StringIO()
            with contextlib.redirect_stdout(output): worker.execute({'prompt':'test'},core,root,coding=tools,hooks=hooks)
            self.assertFalse((root/'blocked.py').exists()); self.assertIn('policy blocked',output.getvalue())


@unittest.skipUnless(os.getenv('MINTY_DOCKER_TESTS')=='1','Set MINTY_DOCKER_TESTS=1 to run real Docker integration')
class IsolatedCodingTest(unittest.TestCase):
    def project(self,root):
        (root/'calc.py').write_text('def add(a,b): return a-b\n',encoding='utf-8')
        (root/'tests').mkdir()
        (root/'tests/test_calc.py').write_text('import unittest\nfrom calc import add\nclass Addition(unittest.TestCase):\n def test_add(self): self.assertEqual(add(2,3),5)\n',encoding='utf-8')

    def test_actual_test_failure_then_patch_then_pass_and_invalidation(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); self.project(root); tools=CodingTools(root)
            before=tools.run_tests('unittest',path='tests')
            self.assertEqual('failed',before['status'],before)
            data=tools.read_file('calc.py')
            tools.apply_patch([{'path':'calc.py','old_text':'return a-b','new_text':'return a+b','expected_sha256':data['sha256']}])
            after=tools.run_tests('unittest',path='tests')
            self.assertEqual('passed',after['status'],after); self.assertEqual('verified',tools.assessment()['outcome'])
            tools.edit_file('calc.py','return a+b','return 0')
            self.assertEqual('unverified',tools.assessment()['outcome'])

    def test_model_stopping_is_not_success_and_runtime_verifies_then_continues(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); self.project(root); tools=CodingTools(root)
            # The first edit remains wrong; the model ends, then the runtime discovers a failing test.
            first=Block(type='tool_use',id='a',name='edit_file',input={'path':'calc.py','old_text':'return a-b','new_text':'return 0'})
            fix=Block(type='tool_use',id='b',name='edit_file',input={'path':'calc.py','old_text':'return 0','new_text':'return a+b'})
            streams=iter([Stream(response([first],'tool_use')),Stream(response([])),Stream(response([fix],'tool_use')),Stream(response([]))])
            core=fixtures.WorkerTest().core(root,streams,lambda **kw:'unused'); output=io.StringIO()
            with contextlib.redirect_stdout(output): worker.execute({'prompt':'fix addition','maxSteps':6},core,root,coding=tools)
            events=[json.loads(line) for line in output.getvalue().splitlines()]
            self.assertTrue(any(event['type']=='completion_check' for event in events))
            assessment=json.loads(next(event['content'] for event in reversed(events) if event['type']=='verification'))
            self.assertEqual('verified',assessment['outcome']); self.assertEqual(1,assessment['tests']['counts']['passed'])

    def test_tests_have_no_api_key_and_cannot_modify_source_workspace(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'test_isolation.py').write_text("import os,pathlib,unittest\nclass Isolation(unittest.TestCase):\n def test_isolated(self):\n  self.assertNotIn('ANTHROPIC_API_KEY',os.environ)\n  pathlib.Path('container-only.txt').write_text('not on host')\n",encoding='utf-8')
            tools=CodingTools(root)
            with patch.dict(os.environ,{'ANTHROPIC_API_KEY':'fixture-only-never-real'}): result=tools.run_tests('unittest',path='.')
            self.assertEqual('passed',result['status'],result); self.assertFalse((root/'container-only.txt').exists())

    def test_node_builtin_runner_records_actual_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'math.test.js').write_text("const test=require('node:test'); const assert=require('node:assert/strict'); test('addition',()=>assert.equal(2+3,5));",encoding='utf-8')
            result=CodingTools(root).run_tests('node',path='.')
            self.assertEqual('passed',result['status'],result); self.assertEqual(1,result['counts']['passed'])

    def test_all_skipped_tests_are_not_a_verified_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'test_skip.py').write_text("import unittest\nclass Skipped(unittest.TestCase):\n @unittest.skip('not executed')\n def test_skip(self): pass\n",encoding='utf-8')
            result=CodingTools(root).run_tests('unittest',path='.')
            self.assertEqual('no_tests',result['status'],result)

    def test_test_side_effect_cannot_silently_fix_only_the_temporary_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/'code.py').write_text('broken\n')
            (root/'test_mutate.py').write_text("import pathlib,unittest\nclass Mutate(unittest.TestCase):\n def test_mutate(self): pathlib.Path('code.py').write_text('fixed\\n')\n",encoding='utf-8')
            tools=CodingTools(root); tools.write_file('code.py','still broken\n')
            result=tools.run_tests('unittest',path='.')
            self.assertEqual('passed',result['status'],result); self.assertTrue(result['sourceChangedDuringTest'])
            self.assertEqual('unverified',tools.assessment()['outcome'])

    @unittest.skipUnless(os.getenv('MINTY_EXTENDED_TESTS')=='1','Enable dependency preparation integration tests explicitly')
    def test_npm_lockfile_preparation_and_offline_test(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'package.json').write_text(json.dumps({'name':'fixture','version':'1.0.0','scripts':{'test':'node --test'}}),encoding='utf-8')
            (root/'package-lock.json').write_text(json.dumps({'name':'fixture','version':'1.0.0','lockfileVersion':3,'packages':{'':{'name':'fixture','version':'1.0.0'}}}),encoding='utf-8')
            (root/'math.test.js').write_text("require('node:test')('addition',()=>require('node:assert/strict').equal(2+3,5));",encoding='utf-8')
            result=CodingTools(root).run_tests('npm',path='.')
            self.assertEqual('passed',result['status'],result)

    @unittest.skipUnless(os.getenv('MINTY_EXTENDED_TESTS')=='1','Enable dependency preparation integration tests explicitly')
    def test_maven_preparation_and_offline_java_test(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'pom.xml').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>fixture</groupId><artifactId>test</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties>
<dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies>
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin></plugins></build></project>''',encoding='utf-8')
            target=root/'src/test/java/AdditionTest.java'; target.parent.mkdir(parents=True)
            target.write_text('import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class AdditionTest { @Test void addition() { assertEquals(5,2+3); }}',encoding='utf-8')
            result=CodingTools(root).run_tests('maven',path='.',timeout_seconds=120)
            self.assertEqual('passed',result['status'],result)

    @unittest.skipUnless(os.getenv('MINTY_SWE_TESTS')=='1','Enable cached SWE container bridge check explicitly')
    def test_swe_container_uses_shared_patch_and_real_test_results(self):
        from evaluation_worker import ContainerCodingTools, image_for, docker_exec, command, ROOT
        name='minty-bridge-test-'+uuid.uuid4().hex[:12]; image=image_for('psf__requests-2317')
        if command(['docker','image','inspect',image],check=False).returncode: self.skipTest('SWE instance image not cached')
        command(['docker','run','--rm','-d','--name',name,'--network','none','--cap-drop','ALL',image,'tail','-f','/dev/null'])
        try:
            command(['docker','exec',name,'mkdir','-p','/opt/minty'])
            command(['docker','cp',str(ROOT/'coding_tools.py'),name+':/opt/minty/coding_tools.py'])
            base=command(['docker','exec',name,'git','-C','/testbed','rev-parse','HEAD']).stdout.strip()
            seed="printf 'def add(a,b): return a-b\\n' > minty_bridge_code.py; printf 'import unittest\\nfrom minty_bridge_code import add\\nclass Addition(unittest.TestCase):\\n def test_add(self): self.assertEqual(add(2,3),5)\\n' > test_minty_bridge.py"
            output=docker_exec(name,seed); self.assertFalse(output.startswith('Error:'),output)
            tools=ContainerCodingTools(name,base)
            self.assertTrue(tools.invoke('search_text',query='return a-b',pattern='minty_bridge_code.py')['ok'])
            original=tools.invoke('read_file',path='minty_bridge_code.py')
            failed=tools.invoke('run_tests',runner='unittest',path='.',pattern='test_minty_bridge.py')
            self.assertEqual('failed',failed['status'],failed)
            changed=tools.invoke('apply_patch',changes=[{'path':'minty_bridge_code.py','old_text':'return a-b','new_text':'return a+b','expected_sha256':original['sha256']}])
            self.assertTrue(changed['ok'],changed)
            passed=tools.invoke('run_tests',runner='unittest',path='.',pattern='test_minty_bridge.py')
            self.assertEqual('passed',passed['status'],passed)
            self.assertEqual(1,passed['counts']['passed'])
        finally: command(['docker','rm','-f',name],check=False)
