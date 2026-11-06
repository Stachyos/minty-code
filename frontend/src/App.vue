<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import Icon from './components/Icon.vue'
import WorkflowView from './components/WorkflowView.vue'
import EvaluationView from './components/EvaluationView.vue'
import RunOutcome from './components/RunOutcome.vue'
import TaskProgress from './components/TaskProgress.vue'
import { api, errorMessage } from './api'

const mascotUrl = '/images/minty-catgirl.png'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const markdown = value => DOMPurify.sanitize(md.render(value || ''))
const status = ref(null)
const connected = ref(false)
const initialLoading = ref(true)
const loadError = ref('')
const sessionsError = ref('')
const connectionError = ref('')
const sessions = ref([])
const selectedId = ref(null)
const messages = ref([])
const events = ref([])
const run = ref(null)
const files = ref([])
const tasks = ref([])
const stats = ref({ sessions: 0, runs: 0, completedRuns: 0, totalTokens: 0, daily: [] })
const statsError = ref('')
const sideError = ref('')
const loadingSession = ref(false)
const sending = ref(false)
const stopping = ref(false)
const prompt = ref('')
const view = ref('workspace')
const sideTab = ref('tasks')
const settingsOpen = ref(false)
const sidebarOpen = ref(false)
const contextOpen = ref(false)
const fileOpen = ref(false)
const openedFile = ref({ path: '', content: '' })
const fileLoading = ref(false)
const uploading = ref(false)
const uploadInput = ref(null)
const folderInput = ref(null)
const composer = ref(null)
const chatScroller = ref(null)
const chartElement = ref(null)
const chartMetric = ref('runs')
const openedEvents = ref(new Set())
let pollTimer, healthTimer, chart, chartObserver, echarts
let selectionVersion = 0
let destroyed = false

const activeSession = computed(() => sessions.value.find(s => s.id === selectedId.value))
const viewTitle = computed(() => ({ workspace: '编程工作台', workflow: '工作流程', analytics: '使用概览', evaluation: 'Agent 评测' })[view.value])
const active = computed(() => ['queued', 'running', 'pending', 'stopping'].includes(String(run.value?.status).toLowerCase()))
const liveText = computed(() => {
  const start = events.value.findLastIndex(event => event.type === 'status' && event.content === 'running')
  return events.value.slice(start + 1).filter(event => ['text', 'assistant_delta', 'text_delta'].includes(event.type)).map(event => event.content).join('')
})
const toolEvents = computed(() => events.value.filter(e => !['text', 'assistant_delta', 'text_delta'].includes(e.type)))
const completedTasks = computed(() => tasks.value.filter(t => ['completed', 'done'].includes(String(t.status).toLowerCase())).length)
const taskProgress = computed(() => tasks.value.length ? Math.round(completedTasks.value / tasks.value.length * 100) : 0)
const tokenCount = computed(() => (run.value?.inputTokens || 0) + (run.value?.outputTokens || 0))
const statusLabel = computed(() => !connected.value ? '等待连接' : status.value?.databaseReady === false ? '数据库未连接' : sessionsError.value ? '历史记录加载失败' : status.value?.configured ? '已就绪' : '待配置模型')
const applicationReady = computed(() => connected.value && status.value?.configured && status.value?.databaseReady !== false && !sessionsError.value)
const dateLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const normalizeState = value => String(value || '').toLowerCase()
const formatCount = n => Number(n || 0).toLocaleString('zh-CN')
const formatTime = date => date ? new Date(date).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : ''
const sessionDate = date => date ? new Date(date).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' }) : ''
const stateLabel = value => ({ queued: '排队中', pending: '等待中', running: '进行中', stopping: '正在停止', completed: '执行已结束', succeeded: '执行已结束', failed: '出错了', cancelled: '已停止', stopped: '已停止', interrupted: '已中断', budget_exhausted: '预算已耗尽', in_progress: '进行中' })[normalizeState(value)] || value || '等待中'
const bytes = size => size < 1024 ? `${size} B` : size < 1048576 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1048576).toFixed(1)} MB`
function eventDetails(event) {
  try { return JSON.stringify(JSON.parse(event.content), null, 2) } catch { return event.content }
}
const TOOL_LABELS = { list_files:'查找文件', search_text:'搜索代码', apply_patch:'应用补丁', get_diff:'检查修改', run_tests:'运行测试', read_file: '读取文件', write_file: '创建文件', edit_file: '修改文件', bash: '执行命令', TodoWrite: '更新待办', task_create: '创建任务', task_get: '查看任务', task_update: '更新任务', task_list: '读取任务列表', load_skill: '加载技能', compress: '压缩上下文', update_progress: '更新任务进度', review_step: '回查步骤记录', start_background: '启动后台命令', background_status: '查看后台命令', stop_background: '停止后台命令' }
function eventTitle(event) {
  if (event.type === 'verification') return '独立验证结果'
  if (event.type === 'changes') return '文件修改记录'
  if (event.type === 'hook') return '生命周期检查'
  if (event.type === 'completion_check') return '验证未通过，继续修复'
  if (event.type === 'error') return '执行遇到问题'
  if (event.type === 'status') return '运行状态更新'
  if (event.type === 'recovery') return '核对恢复检查点'
  if (event.type === 'background') return '后台命令状态'
  if (event.type === 'tool_result') return '工具返回结果'
  if (event.type === 'model_start') return '调用模型'
  if (event.type === 'model_end') return '模型返回'
  let data = {}
  try { data = JSON.parse(event.content) || {} } catch { data = {} }
  if (event.type === 'phase') return ({ prepare: '整理上下文', context: '整理上下文', compact: '压缩上下文', decision: '检查工具调用', feedback: '回填结果' })[data.phase] || '执行阶段'
  if (event.type === 'tool_start') return TOOL_LABELS[data.name] || data.name || '调用工具'
  return data.name || data.tool || '运行记录'
}
function toggleEvent(id) {
  const next = new Set(openedEvents.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openedEvents.value = next
}
async function scrollToBottom() {
  await nextTick()
  if (chatScroller.value) chatScroller.value.scrollTop = chatScroller.value.scrollHeight
}
async function refreshStats() {
  try { stats.value = (await api.get('/stats')).data; statsError.value = ''; await nextTick(); drawChart() }
  catch (e) { statsError.value = errorMessage(e) }
}
async function initialize() {
  initialLoading.value = true
  loadError.value = ''
  const results = await Promise.allSettled([api.get('/status'), api.get('/sessions')])
  if (destroyed) return
  if (results[0].status === 'fulfilled') {
    status.value = results[0].value.data; connected.value = true
    connectionError.value = status.value.databaseReady === false ? status.value.message : ''
  } else { connected.value = false; connectionError.value = errorMessage(results[0].reason) }
  if (results[1].status === 'fulfilled') { sessions.value = results[1].value.data; sessionsError.value = '' }
  else { sessionsError.value = errorMessage(results[1].reason); loadError.value = sessionsError.value }
  initialLoading.value = false
  await refreshStats()
}
async function checkConnection() {
  try {
    const next = (await api.get('/status')).data
    if (destroyed) return
    const recovering = !connected.value || status.value?.databaseReady === false || !!sessionsError.value
    status.value = next; connected.value = true
    connectionError.value = next.databaseReady === false ? next.message : ''
    if (next.databaseReady !== false && recovering && !initialLoading.value) await initialize()
  } catch (e) {
    if (destroyed) return
    connected.value = false; connectionError.value = errorMessage(e)
  } finally {
    if (!destroyed) healthTimer = setTimeout(checkConnection, 10000)
  }
}
async function reconnect() {
  await initialize()
  if (selectedId.value && !sessionsError.value && !connectionError.value) {
    await selectSession(activeSession.value || { id: selectedId.value })
  }
}
function newSession() {
  if (sending.value || uploading.value) return
  selectionVersion++
  clearTimeout(pollTimer)
  selectedId.value = null
  messages.value = []; events.value = []; run.value = null; files.value = []; tasks.value = []
  prompt.value = ''; sideError.value = ''; loadError.value = ''; view.value = 'workspace'; sidebarOpen.value = false
  nextTick(() => composer.value?.focus())
}
async function refreshSide(id = selectedId.value, version = selectionVersion) {
  if (!id) return
  const result = await Promise.allSettled([api.get(`/sessions/${id}/files`), api.get(`/sessions/${id}/tasks`)])
  if (id !== selectedId.value || version !== selectionVersion) return
  sideError.value = ''
  if (result[0].status === 'fulfilled') files.value = result[0].value.data
  else sideError.value = errorMessage(result[0].reason)
  if (result[1].status === 'fulfilled') tasks.value = result[1].value.data
  else sideError.value ||= errorMessage(result[1].reason)
}
async function selectSession(session) {
  if (sending.value || uploading.value) return
  const version = ++selectionVersion
  clearTimeout(pollTimer)
  selectedId.value = session.id; messages.value = []; events.value = []; run.value = null; files.value = []; tasks.value = []
  loadError.value = ''; sideError.value = ''; loadingSession.value = true; view.value = 'workspace'; sidebarOpen.value = false
  try {
    const [details, latest] = await Promise.all([api.get(`/sessions/${session.id}`), api.get(`/sessions/${session.id}/runs/latest`)])
    if (version !== selectionVersion) return
    messages.value = details.data.messages || []
    run.value = latest.data || null
    await refreshSide(session.id, version)
    if (run.value?.id) await pollRun(run.value.id, version)
    await scrollToBottom()
  } catch (e) { if (version === selectionVersion) loadError.value = errorMessage(e) }
  finally { if (version === selectionVersion) loadingSession.value = false }
}
async function pollRun(runId, version) {
  clearTimeout(pollTimer)
  if (destroyed || version !== selectionVersion) return
  const id = selectedId.value
  try {
    let after = events.value.length ? events.value[events.value.length - 1].id : 0
    const [newEvents, latest] = await Promise.all([api.get(`/runs/${runId}/events`, { params: { after } }), api.get(`/sessions/${id}/runs/latest`)])
    if (version !== selectionVersion || destroyed) return
    const known = new Set(events.value.map(e => e.id))
    events.value.push(...newEvents.data.filter(e => !known.has(e.id)))
    run.value = latest.data
    // Drain paginated events, including the tail after a run finishes.
    let batch = newEvents.data
    while (batch.length === 200) {
      after = batch[batch.length - 1].id
      const next = await api.get(`/runs/${runId}/events`, { params: { after } })
      if (version !== selectionVersion || destroyed) return
      batch = next.data
      const existing = new Set(events.value.map(e => e.id))
      events.value.push(...batch.filter(e => !existing.has(e.id)))
      if (batch.length && batch[batch.length - 1].id === after) break
    }
    loadError.value = ''
    if (newEvents.data.length) await scrollToBottom()
    if (active.value) {
      await refreshSide(id, version)
      if (version === selectionVersion) pollTimer = setTimeout(() => pollRun(runId, version), 1200)
    } else {
      const details = await api.get(`/sessions/${id}`)
      if (version !== selectionVersion) return
      messages.value = details.data.messages || []
      const entry = sessions.value.find(session => session.id === id)
      if (entry) Object.assign(entry, { title: details.data.title, updatedAt: details.data.updatedAt })
      await refreshSide(id, version)
      await refreshStats()
      await scrollToBottom()
    }
  } catch (e) {
    if (version !== selectionVersion || destroyed) return
    loadError.value = errorMessage(e)
    if (active.value) pollTimer = setTimeout(() => pollRun(runId, version), 3500)
  }
}
async function send() {
  const text = prompt.value.trim()
  if (!text || sending.value || active.value || uploading.value) return
  if (!connected.value || !status.value?.configured) { settingsOpen.value = true; return }
  if (status.value?.databaseReady === false) { loadError.value = connectionError.value; return }
  sending.value = true; loadError.value = ''; view.value = 'workspace'
  try {
    if (!selectedId.value) {
      const created = (await api.post('/sessions', { title: text.slice(0, 28) })).data
      sessions.value.unshift(created); selectedId.value = created.id
    }
    const createdRun = (await api.post(`/sessions/${selectedId.value}/runs`, { prompt: text })).data
    const version = ++selectionVersion
    clearTimeout(pollTimer)
    prompt.value = ''; events.value = []; openedEvents.value = new Set(); run.value = createdRun
    messages.value.push({ id: `local-${Date.now()}`, role: 'user', content: text, createdAt: new Date().toISOString() })
    await scrollToBottom()
    pollRun(createdRun.id, version)
  } catch (e) { loadError.value = errorMessage(e); ElMessage.error(loadError.value) }
  finally { sending.value = false }
}
async function runAction(action) {
  if (!run.value?.id || stopping.value) return
  const runId = run.value.id
  const version = selectionVersion
  stopping.value = true
  try {
    const result = await api.post(`/runs/${runId}/${action}`)
    if (version === selectionVersion && run.value?.id === runId) {
      run.value = result.data
      await pollRun(runId, version)
    }
  }
  catch (e) { ElMessage.error(errorMessage(e)) }
  finally { stopping.value = false }
}
const stop = () => runAction('stop')
function onComposerKey(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); send() }
}
async function openFile(file) {
  fileOpen.value = true; fileLoading.value = true; openedFile.value = { path: file.path, content: '' }
  try { openedFile.value = (await api.get(`/sessions/${selectedId.value}/files/content`, { params: { path: file.path } })).data }
  catch (e) { openedFile.value.content = errorMessage(e) }
  finally { fileLoading.value = false }
}
async function copyFile() {
  try { await navigator.clipboard.writeText(openedFile.value.content); ElMessage.success('已复制文件内容') }
  catch { ElMessage.error('浏览器未允许复制，请手动选择文本。') }
}
function downloadFile() {
  const url = URL.createObjectURL(new Blob([openedFile.value.content], { type: 'text/plain;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = openedFile.value.path.split('/').pop() || 'file.txt'; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function uploadFiles(event) {
  const selected = Array.from(event.target.files || []).filter(file => {
    const parts = file.webkitRelativePath ? file.webkitRelativePath.split('/').slice(1) : [file.name]
    return parts.every(part => !part.startsWith('.') && !['node_modules','__pycache__','target','dist','venv','coverage','data'].includes(part)) && !/\.(pyc|pem|key|p12|pfx|png|jpe?g|gif|webp|ico|woff2?|ttf|zip|jar|class|pdf|db|sqlite3?)$/i.test(file.name)
  })
  event.target.value = ''
  if (!selected.length || active.value || uploading.value) return
  if (selected.length > 2000 || selected.reduce((total,file) => total + file.size, 0) > 25000000) { ElMessage.error('一次最多导入 2000 个文件、总计 25 MB。'); return }
  const invalid = selected.find(file => file.size > 1024 * 1024)
  if (invalid) { ElMessage.error(`${invalid.name} 超过了 1 MB，请选择较小的代码或文本文件。`); return }
  uploading.value = true
  try {
    if (!selectedId.value) {
      const created = (await api.post('/sessions', { title: '新的灵感' })).data
      sessions.value.unshift(created); selectedId.value = created.id
    }
    for (const file of selected) {
      const data = new FormData(); data.append('file', file)
      if (file.webkitRelativePath) data.append('path', file.webkitRelativePath.split('/').slice(1).join('/'))
      await api.post(`/sessions/${selectedId.value}/files`, data)
    }
    ElMessage.success(`已放入 ${selected.length} 个文件，可以让薄荷阅读它们了。`)
    sideTab.value = 'files'
  } catch (e) { ElMessage.error(errorMessage(e)) }
  finally { await refreshSide(); uploading.value = false }
}
async function changeView(next) {
  view.value = next; sidebarOpen.value = false; contextOpen.value = false
  if (next === 'analytics') { await refreshStats(); await nextTick(); drawChart() }
}
async function openWorkflowSession(sessionId) {
  if (sessionId) await selectSession(sessions.value.find(session => session.id === sessionId) || { id: sessionId })
  else await changeView('workspace')
}
async function drawChart() {
  if (!chartElement.value || view.value !== 'analytics') return
  if (!echarts) echarts = await import('./chart.js')
  if (!chartElement.value || view.value !== 'analytics') return
  if (!chart || chart.getDom() !== chartElement.value) {
    chart?.dispose(); chartObserver?.disconnect()
    chart = echarts.init(chartElement.value)
    chartObserver = new ResizeObserver(() => chart?.resize())
    chartObserver.observe(chartElement.value)
  }
  const daily = stats.value.daily || []
  chart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: '#fff', borderColor: '#eceeec', borderWidth: 1, textStyle: { color: '#1f2421', fontSize: 12, fontFamily: '"Inter", "Segoe UI", "Microsoft YaHei", "PingFang SC", sans-serif' }, extraCssText: 'box-shadow:0 6px 20px rgba(23,32,26,.08);border-radius:8px;' },
    grid: { left: 8, right: 30, top: 24, bottom: 8, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: daily.map(d => d.date?.slice(5)), axisLine: { lineStyle: { color: '#eceeec' } }, axisTick: { show: false }, axisLabel: { color: '#8b938d', fontSize: 12, margin: 14 } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#f2f4f2', type: 'dashed' } }, axisLabel: { color: '#8b938d', fontSize: 12 } },
    series: [{ type: 'line', smooth: 0.35, symbolSize: 6, data: daily.map(d => d[chartMetric.value] || 0), itemStyle: { color: '#5f9472', borderWidth: 2, borderColor: '#fff' }, lineStyle: { width: 2, color: '#5f9472' }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(95,148,114,.16)' }, { offset: 1, color: 'rgba(95,148,114,0)' }]) } }],
  }, true)
}
onMounted(() => { initialize(); healthTimer = setTimeout(checkConnection, 10000) })
onBeforeUnmount(() => { destroyed = true; clearTimeout(pollTimer); clearTimeout(healthTimer); chartObserver?.disconnect(); chart?.dispose() })
</script>

<template>
  <div class="app-shell">
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="sidebarOpen = false"></div>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <a class="brand" href="#" @click.prevent="newSession">
        <div class="brand-mark"><img :src="mascotUrl" alt="薄荷猫娘" width="30" height="30" /></div>
        <strong>Minty Code<span class="brand-dot">.</span></strong>
      </a>
      <button class="new-chat" @click="newSession"><Icon name="plus" :size="17" /><span>新对话</span></button>
      <div class="nav-label">工作台</div>
      <nav class="main-nav" aria-label="主导航">
        <button :class="{ selected: view === 'workspace' }" @click="changeView('workspace')"><Icon name="grid" /><span>编程工作台</span><span class="nav-dot" v-if="view === 'workspace'"></span></button>
        <button :class="{ selected: view === 'workflow' }" @click="changeView('workflow')"><Icon name="workflow" /><span>工作流程</span><span class="nav-dot" v-if="view === 'workflow'"></span></button>
        <button :class="{ selected: view === 'analytics' }" @click="changeView('analytics')"><Icon name="chart" /><span>使用概览</span></button>
        <button :class="{ selected: view === 'evaluation' }" @click="changeView('evaluation')"><Icon name="shield" /><span>Agent 评测</span><span class="nav-dot" v-if="view === 'evaluation'"></span></button>
      </nav>
      <div class="history-heading"><span>最近对话</span><span class="history-count">{{ sessions.length }}</span></div>
      <div class="session-list">
        <div v-if="initialLoading" class="sidebar-empty"><span class="loading-dot"></span>正在加载…</div>
        <div v-else-if="sessionsError" class="sidebar-empty" role="status">{{ sessions.length ? '历史列表更新失败，保留上次结果。' : '历史记录暂时无法加载，请检查数据库连接。' }}<button class="text-button" @click="initialize">重新加载</button></div>
        <div v-else-if="!sessions.length" class="sidebar-empty">还没有对话，从「新对话」开始。</div>
        <button v-for="session in sessions" :key="session.id" class="session-item" :class="{ selected: selectedId === session.id && view === 'workspace' }" @click="selectSession(session)">
          <Icon name="chat" :size="16" /><span>{{ session.title || '新的对话' }}</span><small>{{ sessionDate(session.updatedAt || session.createdAt) }}</small>
        </button>
      </div>
      <div class="sidebar-bottom">
        <button class="settings-button" @click="settingsOpen = true"><Icon name="settings" :size="18" /><span>设置与连接</span><span :class="['connection-dot', { online: applicationReady }]"></span></button>
        <div class="personal-profile"><div class="profile-avatar">S<span></span></div><div><strong>我的个人工作室</strong><small>Personal workspace</small></div><span class="local-pill">本地</span></div>
      </div>
    </aside>

    <main class="main-area">
      <header class="topbar">
        <div class="breadcrumb"><button class="icon-button mobile-menu" aria-label="打开导航" @click="sidebarOpen = true"><Icon name="menu" :size="18" /></button><strong>{{ viewTitle }}</strong></div>
        <div class="topbar-right"><span class="date-label">{{ dateLabel }}</span><button class="status-pill" @click="settingsOpen = true"><span :class="['connection-dot', { online: applicationReady }]"></span>{{ statusLabel }}</button></div>
      </header>

      <EvaluationView v-if="view === 'evaluation'" />
      <WorkflowView v-else-if="view === 'workflow'" :sessions="sessions" :preferred-session-id="selectedId" @open-session="openWorkflowSession" />
      <div v-else-if="view === 'analytics'" class="analytics-view">
        <h1>使用概览</h1>
        <p class="page-description">最近七天的真实运行次数与 Token 用量。</p>
        <el-alert v-if="statsError" :title="statsError" type="error" :closable="false" show-icon class="spaced-alert" />
        <div class="stat-grid">
          <div v-for="item in [{label:'对话空间', value:stats.sessions, icon:'chat', unit:'个'}, {label:'发起任务', value:stats.runs, icon:'bolt', unit:'次'}, {label:'已结束运行', value:stats.completedRuns, icon:'check', unit:'次'}, {label:'累计 Token', value:stats.totalTokens, icon:'layers', unit:'tokens'}]" :key="item.label" class="stat-card"><div class="stat-icon"><Icon :name="item.icon" :size="16" /></div><span>{{ item.label }}</span><div><strong>{{ formatCount(item.value) }}</strong><small>{{ item.unit }}</small></div></div>
        </div>
        <section class="chart-card"><div class="section-title-row"><div><h2>最近七天</h2><p>每天的任务次数与 Token 用量</p></div><el-radio-group v-model="chartMetric" size="small" @change="drawChart"><el-radio-button value="runs">任务次数</el-radio-button><el-radio-button value="tokens">Token 用量</el-radio-button></el-radio-group></div><div ref="chartElement" class="activity-chart"></div><div v-if="!(stats.daily || []).some(d => d.runs || d.tokens)" class="chart-empty"><Icon name="leaf" :size="15" /><span>完成第一个任务后，这里会出现曲线。</span></div></section>
        <div class="analytics-footnote"><Icon name="shield" :size="13" />统计来自本地保存的真实运行记录，Token 用量由模型服务返回。</div>
      </div>

      <div v-else class="workspace-layout">
        <section class="conversation-area">
          <div class="conversation-header"><div><span class="mini-icon"><Icon name="chat" :size="15" /></span><strong>{{ activeSession?.title || '新对话' }}</strong><span v-if="selectedId" class="session-badge">已保存</span></div><div class="conversation-actions"><button v-if="run" class="workflow-link" @click="changeView('workflow')"><Icon name="workflow" :size="14" /><span>查看流程</span></button><button class="icon-button context-toggle" title="任务与文件" aria-label="打开任务与文件" @click="contextOpen = true"><Icon name="folder" :size="16" /></button><button class="icon-button" title="设置与连接" aria-label="设置与连接" @click="settingsOpen = true"><Icon name="settings" :size="16" /></button></div></div>
          <RunOutcome :run="run" :events="events" />
          <TaskProgress :run="run" :events="events" :busy="stopping" @pause="runAction('pause')" @resume="runAction('resume')" />
          <div ref="chatScroller" class="chat-scroll">
            <div v-if="loadingSession" class="loading-state"><span class="loading-dot"></span>正在打开对话…</div>
            <div v-else-if="!messages.length && !active" class="welcome">
              <div class="welcome-mark"><Icon name="spark" :size="21" /></div>
              <h1>今天想创造点什么<span>？</span></h1>
              <p>我是薄荷，你的编程小搭档。<br />从一个小点子开始，我们一起把它变成现实。</p>
            </div>
            <div v-else class="message-list">
              <div class="conversation-start"><span></span>灵感从这里开始<span></span></div>
              <article v-for="message in messages.filter(m => ['user','assistant'].includes(m.role))" :key="message.id" :class="['message', message.role]"><div :class="['message-avatar', message.role]"><span v-if="message.role === 'assistant'" class="assistant-mark"><Icon name="spark" :size="13" /></span><span v-else>我</span></div><div class="message-body"><div class="message-meta"><strong>{{ message.role === 'assistant' ? '薄荷' : '你' }}</strong><span>{{ formatTime(message.createdAt) }}</span></div><div v-if="message.role === 'assistant'" class="markdown-content" v-html="markdown(message.content)"></div><div v-else class="user-content">{{ message.content }}</div></div></article>
              <article v-if="active" class="message assistant"><div class="message-avatar assistant"><span class="assistant-mark"><Icon name="spark" :size="13" /></span></div><div class="message-body"><div class="message-meta"><strong>薄荷</strong><span class="thinking-label"><span class="loading-dot"></span>{{ run?.status === 'stopping' ? '正在收好手头的工作' : '正在认真处理' }}</span></div><div v-if="liveText" class="markdown-content" v-html="markdown(liveText)"></div><div v-else class="thinking-bubbles"><i></i><i></i><i></i></div></div></article>
              <div v-if="toolEvents.length" class="execution-card"><div class="execution-heading"><Icon name="terminal" :size="16" /><strong>执行足迹</strong><span>{{ toolEvents.length }} 条记录</span><span v-if="active" class="live-badge">LIVE</span></div><div class="execution-items"><div v-for="event in toolEvents" :key="event.id" :class="['execution-item', { error: event.type === 'error' }]"><button @click="toggleEvent(event.id)"><Icon :name="event.type === 'error' ? 'close' : event.type === 'tool_start' ? 'terminal' : 'check'" :size="14" /><span>{{ eventTitle(event) }}</span><small>{{ formatTime(event.createdAt) }}</small><Icon name="chevron" :size="13" :class="{ rotated: openedEvents.has(event.id) }" /></button><pre v-if="openedEvents.has(event.id)">{{ eventDetails(event) }}</pre></div></div></div>
              <div v-if="run && !active" :class="['run-summary', normalizeState(run.status)]"><Icon :name="normalizeState(run.status) === 'failed' ? 'close' : 'check'" :size="14" /><span>{{ stateLabel(run.status) }}</span><span v-if="tokenCount">· {{ formatCount(tokenCount) }} tokens</span><span v-if="run.error" class="run-error">{{ run.error }}</span></div>
            </div>
          </div>
          <div class="composer-wrap">
            <el-alert v-if="connectionError || loadError" :title="connectionError || loadError" type="error" :closable="false" show-icon class="composer-alert"><template #default><button class="text-button" @click="reconnect">重新连接</button></template></el-alert>
            <div v-else-if="!initialLoading && connected && !status?.configured" class="config-banner"><Icon name="bolt" :size="16" /><span>再一步就好：配置模型 API Key，即可开始对话。</span><button @click="settingsOpen = true">查看配置 <Icon name="chevron" :size="12" /></button></div>
            <div class="composer" :class="{ busy: active }"><textarea ref="composer" v-model="prompt" :disabled="active || sending" aria-label="输入编程任务" placeholder="给薄荷发消息…" rows="2" maxlength="20000" @keydown="onComposerKey"></textarea><div class="composer-bottom"><div class="composer-context"><Icon name="folder" :size="13" /><span>独立会话工作区</span><span class="context-separator"></span><span class="model-name">{{ status?.model || '模型未连接' }}</span></div><button v-if="active" class="stop-button" :disabled="stopping" @click="stop"><Icon name="stop" :size="11" />{{ stopping ? '停止中' : '停止任务' }}</button><button v-else class="send-button" :disabled="!prompt.trim() || sending || initialLoading || uploading" aria-label="发送任务" @click="send"><span v-if="sending" class="loading-dot"></span><Icon v-else name="arrow" :size="17" /></button></div></div>
            <div class="composer-footnote"><span><kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行</span><span><Icon name="shield" :size="12" />仅供个人使用，请检查重要修改</span></div>
          </div>
        </section>

        <div v-if="contextOpen" class="context-scrim" @click="contextOpen = false"></div><aside class="context-panel" :class="{ open: contextOpen }">
          <div class="context-heading"><div><Icon name="layers" :size="15" /><strong>任务与文件</strong></div><button v-if="contextOpen" class="icon-button" aria-label="关闭工作区" @click="contextOpen = false"><Icon name="close" :size="14" /></button><button class="icon-button" aria-label="刷新任务和文件" title="刷新任务和文件" :disabled="!selectedId" @click="refreshSide()"><Icon name="refresh" :size="13" /></button></div>
          <div class="context-tabs"><button :class="{ selected: sideTab === 'tasks' }" @click="sideTab = 'tasks'">任务清单<span>{{ tasks.length }}</span></button><button :class="{ selected: sideTab === 'files' }" @click="sideTab = 'files'">工作文件<span>{{ files.length }}</span></button></div>
          <div v-if="sideTab === 'files'" class="upload-toolbar"><input ref="uploadInput" type="file" multiple hidden @change="uploadFiles" /><input ref="folderInput" type="file" webkitdirectory multiple hidden @change="uploadFiles" /><button class="upload-button" :disabled="active || uploading || sending" @click="uploadInput?.click()"><span v-if="uploading" class="loading-dot"></span><Icon v-else name="plus" :size="14" />{{ uploading ? '正在放入文件…' : '添加代码文件' }}</button><button class="upload-button" :disabled="active || uploading || sending" @click="folderInput?.click()">导入代码目录</button><small>跳过隐藏配置、密钥和依赖 · 单文件 ≤ 1 MB</small></div><div v-if="sideError" class="side-error">{{ sideError }}</div>
          <div class="context-content">
            <template v-if="sideTab === 'tasks'"><div v-if="tasks.length" class="task-progress"><div><span>完成进度</span><strong>{{ taskProgress }}%</strong></div><el-progress :percentage="taskProgress" :show-text="false" :stroke-width="4" color="#5f9472" /></div><div v-if="!tasks.length" class="context-empty"><div class="empty-illustration"><Icon name="check" :size="19" /></div><strong>暂无任务</strong><p>薄荷拆解任务后，<br />会在这里记下每一个小步骤。</p></div><div v-for="task in tasks" :key="task.id" class="task-item"><span :class="['task-state', normalizeState(task.status)]"><Icon :name="normalizeState(task.status) === 'completed' ? 'check' : 'circle'" :size="13" /></span><div><strong>{{ task.subject || task.title }}</strong><p v-if="task.description">{{ task.description }}</p><small>{{ stateLabel(task.status) }}</small></div></div></template>
            <template v-else><div v-if="!files.length" class="context-empty"><div class="empty-illustration file-empty"><Icon name="folder" :size="19" /></div><strong>暂无文件</strong><p>添加已有代码，或让薄荷创建文件，<br />它们会被好好收在这里。</p></div><button v-for="file in files" :key="file.path" class="file-item" @click="openFile(file)"><span class="file-icon"><Icon name="file" :size="15" /></span><span><strong>{{ file.path.split('/').pop() }}</strong><small :title="file.path">{{ file.path }}</small></span><small>{{ bytes(file.size || 0) }}</small></button></template>
          </div>
          <div class="workspace-info"><div class="info-eyebrow">当前工作区</div><div><Icon name="folder" :size="13" /><span>{{ selectedId ? `会话 ${String(selectedId).slice(0, 8)}` : '尚未创建工作区' }}</span></div><p>当前会话的任务与文件独立保存。</p></div>
        </aside>
      </div>
    </main>

    <el-dialog v-model="settingsOpen" title="设置与连接" width="520px" class="mint-dialog" align-center><div class="settings-intro"><div><h3>连接状态</h3><p>模型密钥只保存在后端，不会发送到网页。</p></div></div><div class="settings-rows"><div><span>后端连接</span><strong :class="connected ? 'text-mint' : 'text-error'">{{ connected ? '连接正常' : '尚未连接' }}</strong></div><div><span>数据库连接</span><strong>{{ !connected ? '尚未检查' : status?.databaseReady === false ? '连接中断' : status?.databaseReady === true ? '连接正常' : '等待检查' }}</strong></div><div><span>模型配置</span><strong>{{ status?.configured ? '已配置' : '尚未配置' }}</strong></div><div><span>当前模型</span><code>{{ status?.model || '—' }}</code></div><div><span>前台命令</span><strong>{{ status?.shellEnabled ? '已启用（Docker 隔离）' : '已关闭' }}</strong></div><div><span>服务状态</span><strong>{{ statusLabel }}</strong></div></div><div class="settings-guide"><strong>首次使用</strong><ol><li>按项目 README 配置 PostgreSQL 与后端环境变量。</li><li>设置 <code>ANTHROPIC_API_KEY</code>、<code>MODEL_ID</code>；需要时设置兼容服务的地址。</li><li>启动后端，再点击下方按钮检查连接。</li></ol><p>为了保护密钥，配置需要在本机后端完成。此页面不会展示或保存 API Key。</p></div><template #footer><el-button @click="settingsOpen = false">关闭</el-button><el-button type="primary" :loading="initialLoading" @click="initialize">重新检查连接</el-button></template></el-dialog>
    <el-dialog v-model="fileOpen" :title="openedFile.path" width="min(900px, 94vw)" class="mint-dialog file-dialog"><div v-if="fileLoading" class="loading-state">正在打开文件…</div><pre v-else class="file-preview">{{ openedFile.content }}</pre><template #footer><el-button @click="fileOpen = false">关闭</el-button><el-button :disabled="fileLoading" @click="downloadFile">下载文件</el-button><el-button type="primary" :disabled="fileLoading" @click="copyFile"><Icon name="copy" :size="14" />复制内容</el-button></template></el-dialog>
  </div>
</template>
