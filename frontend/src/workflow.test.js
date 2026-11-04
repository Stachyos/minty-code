import test from 'node:test'
import assert from 'node:assert/strict'
import { buildWorkflow, elapsedMs, formatDuration, parseContent } from './workflow.js'

const run = status => ({ id: 'run1', status, prompt: '创建文件', createdAt: '2026-10-30T12:00:00Z', finishedAt: status === 'running' ? null : '2026-10-30T12:00:10Z' })
function event(id, type, content) { return { id, type, content: typeof content === 'object' ? JSON.stringify(content) : content, createdAt: `2026-10-30T12:00:${String(id).padStart(2, '0')}Z` } }

test('legacy empty or malformed payloads cannot break workflow rendering', () => {
  for (const content of [null, 'null', 'false', '1', '', 'not-json']) {
    assert.deepEqual(parseContent(content), {})
    assert.doesNotThrow(() => buildWorkflow(run('failed'), [event(1, 'tool_start', content)]))
  }
})

test('model rounds and text deltas remain one node per call', () => {
  const graph = buildWorkflow(run('completed'), [
    event(1, 'model_start', { round: 1, model: 'fixture' }), event(2, 'text', '先'), event(3, 'text', '创建'), event(4, 'model_end', { round: 1, inputTokens: 10, outputTokens: 2 }),
    event(5, 'tool_start', { name: 'write_file', input: { path: 'hello.txt' }, toolUseId: 't1', round: 1 }), event(6, 'tool_result', { name: 'write_file', output: 'OK', toolUseId: 't1', round: 1, status: 'completed' }),
    event(7, 'model_start', { round: 2 }), event(8, 'text', '完成了'), event(9, 'model_end', { round: 2 })])
  assert.deepEqual(graph.nodes.map(n => n.kind), ['request', 'model', 'tool', 'model', 'result'])
  assert.equal(graph.nodes[1].output, '先创建')
  assert.equal(graph.modelRounds, 2)
  assert.equal(graph.completedTools, 1)
  assert.equal(graph.activeNodeId, null)
})

test('same-name tools match by call ID when results arrive out of order', () => {
  const graph = buildWorkflow(run('running'), [event(1, 'tool_start', { name: 'read_file', toolUseId: 'a', input: { path: 'a.txt' } }), event(2, 'tool_start', { name: 'read_file', toolUseId: 'b', input: { path: 'b.txt' } }), event(3, 'tool_result', { name: 'read_file', toolUseId: 'b', output: 'B' })])
  assert.equal(graph.nodes[1].output, '')
  assert.equal(graph.nodes[1].status, 'running')
  assert.equal(graph.nodes[2].output, 'B')
  assert.equal(graph.activeNodeId, graph.nodes[1].id)
})

test('tool errors remain failed even if the overall run succeeds', () => {
  const graph = buildWorkflow(run('completed'), [event(1, 'tool_start', { name: 'read_file', toolUseId: 'a' }), event(2, 'tool_result', { name: 'read_file', toolUseId: 'a', output: 'Error: missing', status: 'failed' })])
  assert.equal(graph.nodes[1].status, 'failed')
  assert.equal(graph.failedTools, 1)
  assert.equal(graph.nodes.at(-1).status, 'completed')
})

test('stopped and failed runs never mark an unfinished step completed', () => {
  for (const status of ['stopped', 'failed', 'completed']) {
    const graph = buildWorkflow(run(status), [event(1, 'model_start', { round: 1 })])
    assert.equal(graph.nodes[1].status, status === 'failed' ? 'failed' : 'interrupted')
    assert.equal(graph.activeNodeId, null)
  }
})

test('phase start and completion update one node', () => {
  const graph = buildWorkflow(run('running'), [event(1, 'phase', { phaseId: 'compact-1', phase: 'compact', label: '压缩上下文', status: 'running' }), event(2, 'phase', { phaseId: 'compact-1', phase: 'compact', label: '压缩上下文', status: 'completed' }), event(3, 'model_start', { round: 2 })])
  assert.equal(graph.nodes.filter(n => n.kind === 'phase').length, 1)
  assert.equal(graph.nodes[1].status, 'completed')
  assert.equal(graph.phaseLabel, '第 2 轮 · 模型调用')
})

test('old error records do not fabricate model nodes and duplicate events deduplicate', () => {
  const oldError = event(1, 'error', 'Insufficient Balance')
  const graph = buildWorkflow({ ...run('failed'), prompt: null, error: 'Insufficient Balance' }, [oldError, oldError])
  assert.deepEqual(graph.nodes.map(n => n.kind), ['request', 'result'])
  assert.equal(graph.hasModelEvents, false)
  assert.equal(graph.nodes.at(-1).output, 'Insufficient Balance')
})

test('old tools pair FIFO and expose missing timing honestly', () => {
  const graph = buildWorkflow(run('completed'), [event(1, 'tool_start', { name: 'read_file' }), event(2, 'tool_result', { name: 'read_file', output: 'hello' }), event(3, 'tool_result', { name: 'write_file', output: 'OK' })])
  assert.equal(graph.completedTools, 2)
  assert.equal(graph.nodes[2].startedAt, null)
  assert.equal(elapsedMs(null, run('completed').finishedAt), null)
  assert.equal(formatDuration(62000), '1 分 2 秒')
})
