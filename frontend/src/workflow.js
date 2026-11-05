const ACTIVE = new Set(['queued', 'pending', 'running', 'stopping'])
const STATUS_LABELS = { queued: '排队中', pending: '等待中', running: '进行中', stopping: '正在停止', completed: '已完成', failed: '失败', stopped: '已停止', cancelled: '已停止', interrupted: '未完成', unknown: '未记录' }
const TOOLS = { bash: '执行命令', read_file: '读取文件', write_file: '创建文件', edit_file: '修改文件', TodoWrite: '更新待办', task_create: '创建任务', task_get: '查看任务', task_update: '更新任务', task_list: '读取任务列表', load_skill: '加载技能', compress: '压缩上下文' }

export const isActiveRun = run => ACTIVE.has(String(run?.status || '').toLowerCase())
export const stateLabel = state => STATUS_LABELS[String(state || '').toLowerCase()] || state || '等待中'
export const toolLabel = name => TOOLS[name] || name || '工具调用'
export function parseContent(content) {
  if (content && typeof content === 'object') return content
  try {
    const value = JSON.parse(content)
    return value && typeof value === 'object' ? value : {}
  } catch { return {} }
}
export function elapsedMs(start, end, now = Date.now()) {
  const startTime = Date.parse(start)
  const endTime = end ? Date.parse(end) : now
  return Number.isFinite(startTime) && Number.isFinite(endTime) ? Math.max(0, endTime - startTime) : null
}
export function formatDuration(ms) {
  if (ms == null) return '—'
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds % 3600 / 60)} 分`
}
export function formatDetail(value) {
  if (value == null || value === '') return ''
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

/** Convert observed events into steps. Never invent model/tool completion. */
export function buildWorkflow(run, rawEvents = []) {
  if (!run) return { nodes: [], activeNodeId: null, phaseLabel: '尚未选择任务', hasModelEvents: false, tools: 0, completedTools: 0, failedTools: 0, modelRounds: 0, lastEventAt: null }
  const nodes = [{ id: 'request', kind: 'request', title: '接收任务', summary: run.prompt || '历史任务未保存请求内容', status: 'completed', startedAt: run.createdAt, endedAt: run.createdAt, input: run.prompt || '', output: '', eventIds: [] }]
  const events = [...new Map(rawEvents.map(event => [event.id, event])).values()].sort((a, b) => Number(a.id) - Number(b.id))
  const models = new Map()
  const phases = new Map()
  const toolsById = new Map()
  const pendingTools = []
  let currentModel = null
  let legacyText = null
  let fatalError = ''
  let hasModelEvents = false

  for (const event of events) {
    const data = parseContent(event.content)
    if (event.type === 'phase') {
      const phaseId = data.phaseId || `${data.phase || 'phase'}-${event.id}`
      let node = phases.get(phaseId)
      if (!node) {
        node = { id: `phase-${event.id}`, kind: 'phase', phase: data.phase || 'prepare', round: data.round ?? null, details: data, title: data.label || '准备执行', summary: data.phase === 'compact' ? '整理对话上下文，继续当前任务' : ({ context: '整理消息历史与工具上下文', decision: '检查模型是否请求调用工具', feedback: '将工具结果写入消息历史' })[data.phase] || '准备会话与执行环境', status: 'running', startedAt: data.status === 'completed' ? null : event.createdAt, input: '', output: '', eventIds: [] }
        phases.set(phaseId, node); nodes.push(node)
      }
      node.eventIds.push(event.id)
      node.details = data
      if (data.phase === 'decision' && data.status === 'completed') {
        node.summary = data.route === 'tools' ? `需要调用 ${data.toolCalls || 0} 个工具` : '无需工具，返回回答'
        node.output = node.summary
      }
      if (data.phase === 'feedback' && data.status === 'completed') node.output = `已回填 ${data.toolCalls || 0} 个工具结果，继续下一轮`
      if (data.status === 'completed') { node.status = 'completed'; node.endedAt = event.createdAt }
      if (data.status === 'failed') { node.status = 'failed'; node.endedAt = event.createdAt }
    } else if (event.type === 'model_start') {
      hasModelEvents = true
      const round = data.round ?? models.size + 1
      const node = { id: `model-${event.id}`, kind: 'model', round, title: `第 ${round} 轮 · 模型调用`, summary: data.model || run.model || '模型处理任务', status: 'running', startedAt: event.createdAt, input: { round, model: data.model || run.model || null }, output: '', eventIds: [event.id] }
      models.set(String(round), node); nodes.push(node); currentModel = node
    } else if (event.type === 'model_end') {
      hasModelEvents = true
      const node = models.get(String(data.round)) || currentModel
      if (node) {
        node.status = 'completed'; node.endedAt = event.createdAt; node.eventIds.push(event.id)
        node.inputTokens = data.inputTokens ?? null; node.outputTokens = data.outputTokens ?? null
      }
    } else if (event.type === 'text') {
      if (!currentModel) {
        if (!legacyText) {
          legacyText = { id: `reply-${event.id}`, kind: 'reply', title: '回复片段', summary: '旧记录未包含模型轮次', status: isActiveRun(run) ? 'running' : run.status === 'completed' ? 'completed' : 'interrupted', startedAt: event.createdAt, endedAt: run.finishedAt, input: '', output: '', eventIds: [] }
          nodes.push(legacyText)
        }
        legacyText.output += String(event.content || ''); legacyText.eventIds.push(event.id)
      } else {
        currentModel.output += String(event.content || ''); currentModel.eventIds.push(event.id)
      }
    } else if (event.type === 'tool_start') {
      const node = { id: `tool-${event.id}`, kind: 'tool', round: data.round ?? null, toolUseId: data.toolUseId || null, name: data.name, title: toolLabel(data.name), summary: data.input?.path || data.input?.subject || data.input?.command || data.name || '等待工具返回结果', status: 'running', startedAt: event.createdAt, input: data.input ?? {}, output: '', eventIds: [event.id] }
      nodes.push(node); pendingTools.push(node)
      if (data.toolUseId) toolsById.set(data.toolUseId, node)
    } else if (event.type === 'tool_result') {
      // New runs pair by provider call ID; legacy runs use first unclosed name.
      let node = data.toolUseId ? toolsById.get(data.toolUseId) : pendingTools.find(tool => tool.status === 'running' && tool.name === data.name && (data.round == null || tool.round === data.round))
      if (!node) {
        node = { id: `tool-result-${event.id}`, kind: 'tool', round: data.round ?? null, name: data.name, title: toolLabel(data.name), summary: '此记录仅包含工具返回结果', startedAt: null, input: '', eventIds: [] }
        nodes.push(node)
      }
      node.output = data.output ?? event.content ?? ''
      node.status = data.status === 'failed' || data.isError === true || /^Error:/i.test(String(data.output || '')) ? 'failed' : 'completed'
      node.endedAt = event.createdAt; node.eventIds.push(event.id)
    } else if (['hook', 'verification', 'completion_check'].includes(event.type)) {
      nodes.push({ id: `${event.type}-${event.id}`, kind: event.type, name: data.hook || event.type,
        title: event.type === 'hook' ? `生命周期 · ${{input:'输入',before_tool:'工具前',after_tool:'工具后',stop:'结束'}[data.hook] || data.hook}` : event.type === 'verification' ? '独立验证结果' : '验证后继续修复',
        summary: data.reason || '', status: event.type === 'verification' && data.outcome === 'validation_failed' ? 'failed' : 'completed',
        startedAt: event.createdAt, endedAt: event.createdAt, input: '', output: formatDetail(data), details: data, eventIds: [event.id] })
    } else if (event.type === 'error') {
      fatalError = String(event.content || '任务执行失败')
    }
  }

  const active = isActiveRun(run)
  if (!active) {
    for (const node of nodes) {
      if (node.status !== 'running') continue
      node.status = run.status === 'failed' ? 'failed' : 'interrupted'
      node.endedAt = run.finishedAt || events.at(-1)?.createdAt || null
      if (!node.output) node.output = run.error || fatalError || '运行结束前未收到此步骤的完成事件。'
    }
    nodes.push({ id: 'result', kind: 'result', title: run.status === 'completed' ? '本次执行结束' : ['stopped', 'cancelled'].includes(run.status) ? '任务已停止' : '任务执行失败', summary: run.error || fatalError || (run.status === 'completed' ? '本次执行已结束' : '运行已停止，已完成的步骤仍保留'), status: run.status, startedAt: run.finishedAt || null, endedAt: run.finishedAt || null, input: '', output: run.error || fatalError || '', eventIds: [] })
  } else if (nodes.length === 1) {
    nodes.push({ id: 'waiting', kind: 'waiting', title: '等待执行事件', summary: '任务已受理，等待执行进度', status: 'running', startedAt: run.createdAt, input: '', output: '', eventIds: [] })
  }
  const activeNode = active ? [...nodes].reverse().find(node => node.status === 'running') : null
  const tools = nodes.filter(node => node.kind === 'tool')
  return { nodes, activeNodeId: activeNode?.id || null, phaseLabel: activeNode?.title || (active ? '正在结束当前步骤' : nodes.at(-1).title), hasModelEvents,
    tools: tools.length, completedTools: tools.filter(node => node.status === 'completed').length, failedTools: tools.filter(node => node.status === 'failed').length,
    modelRounds: models.size, lastEventAt: events.at(-1)?.createdAt || run.createdAt }
}
