"""Recovery and Docker isolation tests for the workbench path."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from coding_tools import CodingTools
from run_checkpoint import BudgetExhausted, RunCheckpoint
from test_worker import Block, Stream, response
import test_worker as fixtures
import worker
from workspace_commands import WorkspaceCommands


def tool(name, number, **arguments):
    return Block(type='tool_use', id=f'tool-{number}', name=name, input=arguments)


class ResumeTest(unittest.TestCase):
    def test_legacy_checkpoint_restores_prior_chat_without_duplicating_latest_prompt(self):
        with tempfile.TemporaryDirectory() as folder, contextlib.redirect_stdout(io.StringIO()):
            root = Path(folder).resolve()
            workspace, directory = root / 'work', root / 'state'
            workspace.mkdir()
            directory.mkdir()
            request = {'prompt': '再重新试一遍', 'model': 'fixture', 'history': [
                {'role': 'user', 'content': '创建喵喵喵目录'}]}
            checkpoint = RunCheckpoint(directory, request, CodingTools(workspace), worker.emit_detail)
            checkpoint.save_messages([{'role': 'user', 'content': request['prompt']}], 0)
            resumed = {**request, 'resume': True}
            coding = CodingTools(workspace, capture_baseline=False)
            checkpoint = RunCheckpoint(directory, resumed, coding, worker.emit_detail)
            core = fixtures.WorkerTest().core(workspace, iter([Stream(response([]))]), None)
            observed = []
            delegate = core.client.messages.stream
            def observe(**arguments):
                observed.extend(arguments['messages'])
                return delegate(**arguments)
            core.client.messages.stream = observe
            worker.execute(resumed, core, workspace, coding=coding, checkpoint=checkpoint)
            self.assertIn(request['history'][0], observed)
            self.assertEqual(1, observed.count({'role': 'user', 'content': request['prompt']}))

    def test_disconnect_keeps_goal_and_completed_writes_and_resumes_the_same_budget(self):
        with tempfile.TemporaryDirectory() as folder, contextlib.redirect_stdout(io.StringIO()) as stream:
            root = Path(folder).resolve()
            workspace = root / 'workspace'
            workspace.mkdir()
            directory = root / 'state'
            directory.mkdir()
            request = {'prompt': 'Create a.txt then b.txt. Preserve existing files.', 'model': 'fixture', 'maxSteps': 6}
            coding = CodingTools(workspace)
            checkpoint = RunCheckpoint(directory, request, coding, worker.emit_detail)
            progress = tool('update_progress', 1, plan=[{'step':'Create a.txt','status':'completed'},
                            {'step':'Create b.txt','status':'pending'}], constraints=['Preserve existing files'],
                            open_issues=['b.txt missing'])
            first = tool('write_file', 2, path='a.txt', content='A')
            original = fixtures.WorkerTest().core(workspace, iter([Stream(response([first, progress], 'tool_use'))]), None)
            first_stream = original.client.messages.stream
            def disconnect_on_second(**arguments):
                try:
                    return first_stream(**arguments)
                except StopIteration as exc:
                    raise ConnectionError('fixture connection broke') from exc
            original.client.messages.stream = disconnect_on_second
            with self.assertRaises(ConnectionError):
                worker.execute(request, original, workspace, coding=coding, checkpoint=checkpoint)

            self.assertEqual('A', (workspace / 'a.txt').read_text())
            before_resume = (workspace / 'a.txt').stat().st_mtime_ns
            (workspace / 'outside-edit.txt').write_text('user added this while paused')
            resumed = {**request, 'resume': True}
            coding = CodingTools(workspace, capture_baseline=False)
            checkpoint = RunCheckpoint(directory, resumed, coding, worker.emit_detail)
            core = fixtures.WorkerTest().core(workspace, iter([
                Stream(response([tool('write_file', 3, path='a.txt', content='A'),
                                 tool('write_file', 4, path='b.txt', content='B')], 'tool_use')),
                Stream(response([Block(type='text', text='complete')]))
            ]), None)
            delegate = core.client.messages.stream
            observed = []
            def observe(**arguments):
                observed.append(json.dumps(arguments['messages'], ensure_ascii=False))
                return delegate(**arguments)
            core.client.messages.stream = observe
            worker.execute(resumed, core, workspace, coding=coding, checkpoint=checkpoint)
            self.assertIn(request['prompt'], observed[0])
            self.assertIn('b.txt missing', observed[0])
            self.assertIn('outside-edit.txt', observed[0])
            self.assertEqual(before_resume, (workspace / 'a.txt').stat().st_mtime_ns)
            self.assertEqual('B', (workspace / 'b.txt').read_text())
            self.assertEqual(4, checkpoint.state['rounds'])
            self.assertEqual(27, checkpoint.state['inputTokens'])
            self.assertEqual('skipped', checkpoint.review_step(3)['status'])
            self.assertEqual('A', checkpoint.review_step(1)['arguments']['content'])
            self.assertEqual(3, coding.get_diff()['totalFiles'])
            self.assertIn('"type": "recovery"', stream.getvalue())
            (workspace / 'a.txt').write_text('edited by the user')
            self.assertIn('当前文件状态需要核对', checkpoint.repeated_effect('write_file', first.input))

    def test_in_flight_write_is_not_replayed_after_reconciliation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            workspace, directory = root / 'work', root / 'state'
            workspace.mkdir()
            directory.mkdir()
            request = {'prompt': 'Create both files', 'model': 'fixture', 'maxSteps': 1}
            checkpoint = RunCheckpoint(directory, request, CodingTools(workspace), lambda *a, **k: None)
            arguments = {'path': 'first.txt', 'content': 'written before kill'}
            checkpoint.before_model(10, 20)
            checkpoint.begin_tool('write_file', arguments)
            (workspace / 'first.txt').write_text(arguments['content'])
            checkpoint = RunCheckpoint(directory, {**request, 'resume':True},
                                       CodingTools(workspace, capture_baseline=False), lambda *a, **k: None)
            changed, pending = checkpoint.reconcile()
            self.assertEqual(['first.txt'], changed)
            self.assertEqual('interrupted', checkpoint.review_step(pending['id'])['status'])
            self.assertIsNotNone(checkpoint.repeated_effect('write_file', arguments))
            with self.assertRaises(BudgetExhausted):
                checkpoint.before_model(1, 20)


@unittest.skipUnless(os.getenv('MINTY_DOCKER_TESTS') == '1', 'Docker is required')
class BackgroundCommandTest(unittest.TestCase):
    def test_background_snapshot_is_offline_unprivileged_and_disposable(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            workspace, directory = root / 'work', root / 'state'
            workspace.mkdir()
            directory.mkdir()
            (workspace / 'source.txt').write_text('original')
            (workspace / '.env').write_text('FIXTURE_SECRET=local-only')
            coding = CodingTools(workspace)
            notifications = []
            commands = WorkspaceCommands(coding, directory, lambda kind, **data: notifications.append(data))
            try:
                job = commands.start('sleep 2; test "$(id -u)" = 1000 && test "$(ls /sys/class/net)" = lo '
                                     '&& test ! -e .env && test -z "$ANTHROPIC_API_KEY" '
                                     '&& echo disposable > source.txt && cat source.txt', 20)
                self.assertEqual('running', job['status'])
                result = commands.status(job['jobId'], 10)
                self.assertEqual('completed', result['status'], result['output'])
                self.assertEqual('disposable', result['output'].strip())
                self.assertEqual('original', (workspace / 'source.txt').read_text())
                second = commands.start('sleep 90', 100)
                self.assertEqual('stopped', commands.stop(second['jobId'])['status'])
            finally:
                commands.close()
            remaining = subprocess.check_output(['docker', 'ps', '-aq', '--filter', f'label=minty.run={coding.run_id}'], text=True)
            self.assertEqual('', remaining.strip())
            self.assertTrue(any(event['status'] == 'completed' for event in notifications))
