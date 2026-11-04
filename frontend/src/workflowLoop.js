import { buildWorkflow, isActiveRun, parseContent } from './workflow.js'

export const LOOP_STAGES = Object.freeze([
  { id: 'input', title: '接收任务', description: '接收本次请求', icon: 'chat' },
  { id: 'context', title: '整理上下文', description: '整理消息，必要时压缩上下文', icon: 'layers' },
  { id: 'model', title: '调用模型', description: '生成回复与工具调用请求', icon: 'spark' },
  { id: 'decision', title: '需要工具吗？', description: '检查响应中的工具调用', icon: 'workflow' },
  { id: 'tools', title: '执行工具', description: '读取文件、修改代码或运行命令', icon: 'terminal' },
  { id: 'feedback', title: '回填结果', description: '将工具结果写回消息历史', icon: 'refresh' },
  { id: 'output', title: '返回结果', description: '展示回复与最终状态', icon: 'check' },
].map(stage => Object.freeze(stage)))

const LOOP_EDGES = [
  ['input', 'context'], ['context', 'model'], ['model', 'decision'],
  ['decision', 'tools'], ['tools', 'feedback'], ['feedback', 'context'],
  ['decision', 'output'],
]

function phasePayload(node, eventById) {
  const source = node.eventIds?.map(id => eventById.get(String(id))).find(event => event?.type === 'phase')
  return source ? parseContent(source.content) : {}
}

function stageIdFor(node, eventById) {
  if (node.kind === 'phase') {
    const phase = node.phase || node.details?.phase || phasePayload(node, eventById).phase || 'prepare'
    return { prepare: 'context', compact: 'context', context: 'context', decision: 'decision', feedback: 'feedback' }[phase] || null
  }
  return { request: 'input', model: 'model', tool: 'tools', reply: 'output', result: 'output' }[node.kind] || null
}

// Terminal conversion in buildWorkflow changes unfinished steps to failed or
// interrupted. Check their actual events to distinguish that from a tool error
// which was returned successfully and may have been recovered from.
function unfinished(node, eventById) {
  const events = (node.eventIds || []).map(id => eventById.get(String(id))).filter(Boolean)
  if (node.kind === 'model') return events.some(event => event.type === 'model_start') && !events.some(event => event.type === 'model_end')
  if (node.kind === 'tool') return events.some(event => event.type === 'tool_start') && !events.some(event => event.type === 'tool_result')
  if (node.kind === 'phase') {
    const latest = events.filter(event => event.type === 'phase').at(-1)
    return Boolean(latest) && !['completed', 'failed'].includes(parseContent(latest.content).status)
  }
  return false
}

/** Aggregate observed execution entries into seven fixed loop stages. */
export function buildLoopWorkflow(run, events = []) {
  const flow = buildWorkflow(run, events)
  const stages = LOOP_STAGES.map(stage => ({ ...stage, status: 'idle', visits: 0, entries: [], latest: null }))
  const edges = LOOP_EDGES.map(([from, to]) => ({ id: `${from}-${to}`, from, to, status: 'idle' }))
  const stageById = new Map(stages.map(stage => [stage.id, stage]))
  const edgeById = new Map(edges.map(edge => [edge.id, edge]))
  const eventById = new Map(events.map(event => [String(event.id), event]))
  const observed = []

  for (const entry of flow.nodes) {
    const stageId = stageIdFor(entry, eventById)
    if (!stageId) continue
    const stage = stageById.get(stageId)
    stage.entries.push(entry)
    stage.latest = entry
    stage.visits++
    stage.status = 'visited'
    observed.push({ stageId, entry })
  }

  let currentStageId = null
  let currentEntryId = null
  const active = isActiveRun(run)
  if (active) {
    const current = flow.nodes.find(entry => entry.id === flow.activeNodeId)
    // The legacy waiting placeholder represents an accepted request, not a
    // second visit or an invented preparation phase.
    if (current?.kind === 'waiting') {
      currentStageId = 'input'
      currentEntryId = 'request'
    } else if (current) {
      currentStageId = stageIdFor(current, eventById)
      currentEntryId = currentStageId ? current.id : null
    }
    if (currentStageId) stageById.get(currentStageId).status = 'current'
  } else if (run) {
    currentStageId = 'output'
    currentEntryId = 'result'
    stageById.get('output').status = run.status === 'failed' ? 'failed' : ['stopped', 'cancelled'].includes(run.status) ? 'interrupted' : 'visited'
    for (const { stageId, entry } of observed) {
      if (unfinished(entry, eventById)) stageById.get(stageId).status = run.status === 'failed' ? 'failed' : 'interrupted'
    }
  }

  // Only light transitions between adjacent observed stages. Missing context,
  // decision or feedback events in old runs must not create inferred paths.
  let previous = null
  for (const observedEntry of observed) {
    if (previous && previous.stageId !== observedEntry.stageId) {
      const edge = edgeById.get(`${previous.stageId}-${observedEntry.stageId}`)
      if (edge) {
        edge.status = 'visited'
        if (observedEntry.entry.id === currentEntryId && active) edge.status = 'current'
        else if (!active && unfinished(observedEntry.entry, eventById)) edge.status = run?.status === 'failed' ? 'failed' : 'interrupted'
        else if (observedEntry.entry.id === 'result') edge.status = stageById.get('output').status
      }
    }
    previous = observedEntry
  }

  return { stages, currentStageId, currentEntryId, rounds: flow.modelRounds, edges, hasDetailedEvents: flow.hasModelEvents }
}
