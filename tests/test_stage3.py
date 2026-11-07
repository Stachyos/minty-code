"""Project retrieval and shared-skill evidence through the workbench loop."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest

from coding_tools import CodingTools
from project_knowledge import ProjectKnowledge
from run_checkpoint import RunCheckpoint
import test_worker as fixtures
import worker


def item(id, kind, title, content, verified=False):
    return {'id': id, 'kind': kind, 'title': title, 'source': 'Fixture', 'content': content, 'verified': verified}


class ProjectKnowledgeTest(unittest.TestCase):
    def test_project_answer_receives_matching_sources_and_loads_verified_skill(self):
        resources = [
            item('doc', 'document', '发布规范', '简介\n发布前需要运行回归测试。'),
            item('memo', 'memory', '发布决策', '发布审批需要两人复核。'),
            item('skill', 'skill', 'release-check', 'Run the approved release procedure.', True),
            item('draft', 'skill', 'draft-skill', 'Unverified procedure.'),
        ]
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory).resolve()
            workspace = root / 'workspace'
            workspace.mkdir()
            (workspace / 'release.py').write_text('def verify_release(): pass')
            state = root / 'state'
            state.mkdir()
            coding = CodingTools(workspace)
            notices = []
            def notify(kind, **details):
                notices.append((kind, details))
            knowledge = ProjectKnowledge(resources, coding.baseline, notify)
            request = {'prompt': '发布审批和回归测试有什么要求？', 'model': 'fixture'}
            checkpoint = RunCheckpoint(state, request, coding, notify)
            load = fixtures.Block(type='tool_use', id='skill-1', name='load_skill', input={'name': 'release-check'})
            streams = iter([fixtures.Stream(fixtures.response([load], 'tool_use')),
                            fixtures.Stream(fixtures.response([fixtures.Block(type='text', text='检查完毕')]))])
            core = fixtures.WorkerTest().core(workspace, streams, None)
            core.TOOLS.append({'name': 'load_skill'})
            core.TOOL_HANDLERS['load_skill'] = lambda name: 'Error: Unknown skill'
            requests = []
            def stream(**kwargs):
                requests.append(kwargs)
                return next(streams)
            core.client.messages.stream = stream
            worker.execute(request, core, workspace, coding=coding, checkpoint=checkpoint, knowledge=knowledge)
            reference = requests[0]['messages'][0]['content']
            self.assertIn('发布审批需要两人复核', reference)
            self.assertIn('回归测试', reference)
            self.assertNotIn('draft-skill', requests[0]['system'])
            self.assertNotIn('Unverified procedure', reference)
            self.assertIn('release-check', requests[0]['system'])
            self.assertIn('Run the approved release procedure', str(requests[1]['messages']))
            sources = next(data['sources'] for kind, data in notices if kind == 'knowledge')
            self.assertIn('memory', {hit['kind'] for hit in sources})
            self.assertEqual('发布前需要运行回归测试。', knowledge.read('project:doc', 2, 2)['excerpt'])
            self.assertTrue(knowledge.search('verify_release')['matches'][0]['sourceId'].startswith('workspace:'))
            self.assertFalse(knowledge.read('project:unrelated')['ok'])
