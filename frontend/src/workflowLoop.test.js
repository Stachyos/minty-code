import test from 'node:test'
import assert from 'node:assert/strict'
import { LOOP_STAGES, buildLoopWorkflow } from './workflowLoop.js'

const run = status => ({ id: 'fixture', prompt: '本地测试任务', status, createdAt: '2026-10-30T12:00:00Z', finishedAt: status === 'running' ? null : '2026-10-30T12:01:00Z' })
const event = (id, type, content) => ({ id, type, content: typeof content === 'object' ? JSON.stringify(content) : content, createdAt: new Date(Date.parse('2026-10-30T12:00:00Z') + id * 1000).toISOString() })
const phase = (id, name, status = 'completed', round = 1, phaseId = `${name}-${round}`) => event(id, 'phase', { phase: name, phaseId, label: name, status, round })
const getStage = (loop, id) => loop.stages.find(stage => stage.id === id)
const getEdge = (loop, from, to) => loop.edges.find(edge => edge.from === from && edge.to === to)

function firstRound() {
  return [
    phase(1, 'context'), event(2, 'model_start', { round: 1 }), event(3, 'model_end', { round: 1 }),
    phase(4, 'decision'), event(5, 'tool_start', { name: 'read_file', toolUseId: 't1', round: 1 }),
    event(6, 'tool_result', { name: 'read_file', toolUseId: 't1', round: 1, status: 'completed', output: 'fixture' }),
    phase(7, 'feedback'),
  ]
}

test('empty input keeps seven fixed idle stages and edges without mutating constants', () => {
  const loop = buildLoopWorkflow(null)
  assert.deepEqual(loop.stages.map(stage => stage.id), ['input', 'context', 'model', 'decision', 'tools', 'feedback', 'output'])
  assert.equal(loop.stages.length, 7)
  assert.equal(loop.edges.length, 7)
  assert.ok(loop.stages.every(stage => stage.status === 'idle' && stage.visits === 0 && stage.latest === null))
  assert.ok(loop.edges.every(edge => edge.status === 'idle'))
  assert.equal(loop.currentStageId, null)
  assert.equal(loop.currentEntryId, null)
  assert.equal(loop.rounds, 0)
  loop.stages[0].status = 'current'
  assert.equal(LOOP_STAGES[0].status, undefined)
})

test('accepted tasks wait at input without inventing context or an extra input visit', () => {
  const loop = buildLoopWorkflow(run('running'))
  assert.equal(loop.currentStageId, 'input')
  assert.equal(loop.currentEntryId, 'request')
  assert.equal(getStage(loop, 'input').visits, 1)
  assert.equal(getStage(loop, 'input').status, 'current')
  assert.ok(loop.stages.slice(1).every(stage => stage.status === 'idle'))
})

test('multiple rounds revisit fixed stages and retain actual entry details', () => {
  const events = [...firstRound(), phase(8, 'context', 'completed', 2), event(9, 'model_start', { round: 2 }), event(10, 'text', '完成了'), event(11, 'model_end', { round: 2 }), phase(12, 'decision', 'completed', 2)]
  const loop = buildLoopWorkflow(run('completed'), events)
  assert.equal(loop.stages.length, 7)
  assert.equal(loop.rounds, 2)
  assert.equal(getStage(loop, 'model').visits, 2)
  assert.equal(getStage(loop, 'context').visits, 2)
  assert.equal(getStage(loop, 'decision').visits, 2)
  assert.equal(getStage(loop, 'model').latest.output, '完成了')
  assert.equal(getStage(loop, 'tools').entries[0].output, 'fixture')
  assert.equal(loop.currentStageId, 'output')
  assert.equal(loop.currentEntryId, 'result')
  assert.ok(loop.edges.every(edge => edge.status === 'visited'))
  assert.equal(loop.hasDetailedEvents, true)
})

test('the current stage follows real model, tool, context, decision and feedback starts', () => {
  const fixtures = [
    ['model', [phase(1, 'context'), event(2, 'model_start', { round: 1 })], 'model-2', ['context', 'model']],
    ['tools', [event(1, 'model_start', { round: 1 }), event(2, 'model_end', { round: 1 }), phase(3, 'decision'), event(4, 'tool_start', { name: 'read_file', toolUseId: 't1' })], 'tool-4', ['decision', 'tools']],
    ['context', [...firstRound(), phase(8, 'compact', 'running')], 'phase-8', ['feedback', 'context']],
    ['decision', [event(1, 'model_start', { round: 1 }), event(2, 'model_end', { round: 1 }), phase(3, 'decision', 'running')], 'phase-3', ['model', 'decision']],
    ['feedback', firstRound().map(item => item.id === 7 ? phase(7, 'feedback', 'running') : item), 'phase-7', ['tools', 'feedback']],
  ]
  for (const [stageId, events, entryId, edge] of fixtures) {
    const loop = buildLoopWorkflow(run('running'), events)
    assert.equal(loop.currentStageId, stageId)
    assert.equal(loop.currentEntryId, entryId)
    assert.equal(getStage(loop, stageId).status, 'current')
    assert.equal(getEdge(loop, ...edge).status, 'current')
  }
})

test('paired phase start and end count as one visit, with no fabricated current stage between events', () => {
  const events = [phase(1, 'context', 'running'), phase(2, 'context')]
  const loop = buildLoopWorkflow(run('running'), events)
  assert.equal(getStage(loop, 'context').visits, 1)
  assert.equal(getStage(loop, 'context').status, 'visited')
  assert.equal(loop.currentStageId, null)
  assert.equal(loop.currentEntryId, null)
  assert.equal(getEdge(loop, 'input', 'context').status, 'visited')
})

test('a direct answer visits decision-output while tools and feedback remain idle', () => {
  const loop = buildLoopWorkflow(run('completed'), [phase(1, 'prepare'), event(2, 'model_start', { round: 1 }), event(3, 'text', '你好'), event(4, 'model_end', { round: 1 }), phase(5, 'decision')])
  assert.equal(getStage(loop, 'context').visits, 1)
  assert.equal(getStage(loop, 'tools').status, 'idle')
  assert.equal(getStage(loop, 'feedback').status, 'idle')
  assert.equal(getEdge(loop, 'decision', 'output').status, 'visited')
  assert.equal(getEdge(loop, 'decision', 'tools').status, 'idle')
  assert.equal(getEdge(loop, 'tools', 'feedback').status, 'idle')
})

test('terminal failures and stops retain unfinished stage states and select the result', () => {
  for (const status of ['failed', 'stopped', 'cancelled']) {
    for (const [stageId, events] of [
      ['model', [phase(1, 'context'), event(2, 'model_start', { round: 1 })]],
      ['tools', [phase(1, 'decision'), event(2, 'tool_start', { name: 'bash', toolUseId: 't1' })]],
      ['context', [phase(1, 'compact', 'running')]],
    ]) {
      const loop = buildLoopWorkflow({ ...run(status), error: status === 'failed' ? 'fixture error' : null }, events)
      const expected = status === 'failed' ? 'failed' : 'interrupted'
      assert.equal(getStage(loop, stageId).status, expected)
      assert.equal(getStage(loop, 'output').status, expected)
      assert.equal(loop.currentStageId, 'output')
      assert.equal(loop.currentEntryId, 'result')
    }
  }
})

test('recovered tool failures remain in details without becoming the current fatal stage', () => {
  const events = firstRound().map(item => item.id === 6 ? event(6, 'tool_result', { name: 'read_file', toolUseId: 't1', round: 1, status: 'failed', output: 'Error: missing' }) : item)
  events.push(phase(8, 'context', 'completed', 2), event(9, 'model_start', { round: 2 }))
  const loop = buildLoopWorkflow(run('running'), events)
  assert.equal(loop.currentStageId, 'model')
  assert.equal(getStage(loop, 'model').status, 'current')
  assert.equal(getStage(loop, 'tools').status, 'visited')
  assert.equal(getStage(loop, 'tools').entries[0].status, 'failed')
  const failedAfterFinishedSteps = buildLoopWorkflow({ ...run('failed'), error: 'service error' }, events.slice(0, -1))
  assert.equal(getStage(failedAfterFinishedSteps, 'tools').status, 'visited')
  assert.equal(getStage(failedAfterFinishedSteps, 'output').status, 'failed')
})

test('legacy records keep unavailable model and phase stages idle without inferring edges', () => {
  const legacy = [event(1, 'tool_start', { name: 'read_file' }), event(2, 'tool_result', { name: 'read_file', output: 'old data' }), event(3, 'text', '旧回复')]
  const loop = buildLoopWorkflow(run('completed'), legacy)
  assert.equal(loop.hasDetailedEvents, false)
  assert.equal(loop.rounds, 0)
  for (const id of ['context', 'model', 'decision', 'feedback']) assert.equal(getStage(loop, id).status, 'idle')
  assert.equal(getStage(loop, 'tools').visits, 1)
  assert.ok(loop.edges.every(edge => edge.status === 'idle'))
  assert.equal(getStage(loop, 'output').latest.id, 'result')
  const errorOnly = buildLoopWorkflow({ ...run('failed'), error: 'old API error' }, [event(1, 'error', 'old API error')])
  assert.equal(getStage(errorOnly, 'model').status, 'idle')
  assert.equal(getStage(errorOnly, 'output').status, 'failed')
})
