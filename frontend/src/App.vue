<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import Icon from './components/Icon.vue'
import WorkflowView from './components/WorkflowView.vue'
import EvaluationView from './components/EvaluationView.vue'
import RunOutcome from './components/RunOutcome.vue'
import TaskProgress from './components/TaskProgress.vue'
import ProjectSidebar from './components/ProjectSidebar.vue'
import ProjectResources from './components/ProjectResources.vue'
import DirectoryPicker from './components/DirectoryPicker.vue'
import DialogHeading from './components/DialogHeading.vue'
import KnowledgeSources from './components/KnowledgeSources.vue'
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
const projects = ref([])
const projectsError = ref('')
const projectTree = ref(null)
const projectDialog = ref(false)
const editingProject = ref(null)
const projectName = ref('')
const projectDirectory = ref('')
const projectFormError = ref('')
const savingProject = ref(false)
const deletingItem = ref(false)
const selectedId = ref(null)
const projectResources = ref(null)
const draft = ref({ mode: 'chat' })
const drafts = new Map()
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
const showExecution = ref(false)
const fetchingExecution = ref(false)
const moreExecution = ref(true)
const executionError = ref('')
let executionCursor = 0
let pollTimer, healthTimer, chart, chartObserver, echarts
let selectionVersion = 0
let selectionRequests = new AbortController()
let pollSequence = 0
let sideSequence = 0
let pollCursor = 0
let destroyed = false

const activeSession = computed(() => draft.value || sessions.value.find(s => s.id === selectedId.value))
const activeProject = computed(() => projects.value.find(project => project.id === activeSession.value?.projectId))
const plainChat = computed(() => activeSession.value?.mode === 'chat')
const currentId = computed(() => selectedId.value || draft.value?.id)
const scopeLabel = computed(() => plainChat.value ? '普通聊天' : activeSession.value?.projectId
  ? [...new Set([activeSession.value.projectName, activeSession.value.workspaceName].filter(Boolean))].join(' / ') || '项目工作区'
  : '独立会话工作区')
const viewTitle = computed(() => ({ workspace: plainChat.value ? '普通聊天' : '编程工作台', workflow: '工作流程', analytics: '使用概览', evaluation: 'Agent 评测' })[view.value])
const active = computed(() => ['queued', 'running', 'pending', 'stopping'].includes(String(run.value?.status).toLowerCase()))
const liveText = computed(() => {
  const start = events.value.findLastIndex(event => event.type === 'status' && event.content === 'running')
  return events.value.slice(start + 1).filter(event => ['text', 'assistant_delta', 'text_delta'].includes(event.type)).map(event => event.content).join('')
})
const toolEvents = computed(() => events.value.filter(e => !['text', 'assistant_delta', 'text_delta', 'knowledge'].includes(e.type)))
const transcript = computed(() => messages.value.filter(message => ['user', 'assistant'].includes(message.role))
  .map(message => ({ ...message, html: message.role === 'assistant' ? markdown(message.content) : '' })))
const completedTasks = computed(() => tasks.value.filter(t => ['completed', 'done'].includes(String(t.status).toLowerCase())).length)
const taskProgress = computed(() => tasks.value.length ? Math.round(completedTasks.value / tasks.value.length * 100) : 0)
const tokenCount = computed(() => (run.value?.inputTokens || 0) + (run.value?.outputTokens || 0))
const statusLabel = computed(() => !connected.value ? '等待连接' : status.value?.databaseReady === false ? '数据库未连接' : sessionsError.value ? '历史记录加载失败' : status.value?.configured ? '已就绪' : '待配置模型')
const applicationReady = computed(() => connected.value && status.value?.configured && status.value?.databaseReady !== false && !sessionsError.value)
const dateLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())
const normalizeState = value => String(value || '').toLowerCase()
const formatCount = n => Number(n || 0).toLocaleString('zh-CN')
const formatTime = date => date ? new Date(date).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : ''
const stateLabel = value => ({ queued: '排队中', pending: '等待中', running: '进行中', stopping: '正在停止', completed: '执行已结束', succeeded: '执行已结束', failed: '出错了', cancelled: '已停止', stopped: '已停止', interrupted: '已中断', budget_exhausted: '预算已耗尽', in_progress: '进行中' })[normalizeState(value)] || value || '等待中'
const bytes = size => size < 1024 ? `${size} B` : size < 1048576 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1048576).toFixed(1)} MB`
function eventDetails(event) {
  try { return JSON.stringify(JSON.parse(event.content), null, 2) } catch { return event.content }
}
const TOOL_LABELS = { list_files:'查找文件', search_text:'搜索代码', apply_patch:'应用补丁', get_diff:'检查修改', run_tests:'运行测试', read_file: '读取文件', write_file: '创建文件', create_directory: '创建文件夹', edit_file: '修改文件', bash: '执行命令', TodoWrite: '更新待办', task_create: '创建任务', task_get: '查看任务', task_update: '更新任务', task_list: '读取任务列表', load_skill: '加载技能', compress: '压缩上下文', update_progress: '更新任务进度', review_step: '回查步骤记录', start_background: '启动后台命令', background_status: '查看后台命令', stop_background: '停止后台命令' }
Object.assign(TOOL_LABELS, { search_project_knowledge: '检索项目资料', read_project_source: '读取资料出处' })
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
  let data = {}
  try { data = JSON.parse(event.content) || {} } catch { data = {} }
  if (event.type === 'model_end') {
    if (data.retryMode === 'same') return '模型接口异常，自动重试'
    if (data.retrying) return '模型中断，改用一次性接收'
    return data.status === 'failed' ? '模型请求失败' : '模型返回'
  }
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
let initializeRequest = 0
async function initialize() {
  const request = ++initializeRequest
  initialLoading.value = true
  loadError.value = ''
  void loadProjects()
  const results = await Promise.allSettled([api.get('/status'), api.get('/sessions')])
  if (destroyed || request !== initializeRequest) return
  if (results[0].status === 'fulfilled') {
    status.value = results[0].value.data; connected.value = true
    connectionError.value = status.value.databaseReady === false ? status.value.message : ''
  } else { connected.value = false; connectionError.value = errorMessage(results[0].reason) }
  if (results[1].status === 'fulfilled') { sessions.value = results[1].value.data; sessionsError.value = '' }
  else { sessionsError.value = errorMessage(results[1].reason); loadError.value = sessionsError.value }
  initialLoading.value = false
  if (view.value === 'analytics') void refreshStats()
}
let projectRequest = 0
async function loadProjects() {
  const request = ++projectRequest
  try {
    const { data } = await api.get('/projects')
    if (request === projectRequest && !destroyed) {
      projects.value = data
      projectsError.value = ''
    }
  } catch (e) {
    if (request === projectRequest && !destroyed) projectsError.value = errorMessage(e)
  }
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
    await selectSession(activeSession.value || { id: selectedId.value }, true)
  }
}
function beginSelection() {
  clearTimeout(pollTimer)
  selectionRequests.abort()
  selectionRequests = new AbortController()
  events.value = []
  openedEvents.value = new Set()
  showExecution.value = false
  fetchingExecution.value = false
  moreExecution.value = true
  executionError.value = ''
  executionCursor = 0
  pollCursor = 0
  return ++selectionVersion
}
const draftKey = session => session?.projectId || 'chat'
function stashDraft() {
  if (!draft.value) return
  const key = draftKey(draft.value)
  if (draft.value.id || prompt.value) drafts.set(key, { ...draft.value, prompt: prompt.value })
  else drafts.delete(key)
}
function newSession() { openDraft() }
function openDraft(project) {
  if (sending.value || uploading.value) return
  const next = project ? {
    mode: 'workspace', projectId: project.id, projectName: project.name,
    workspaceId: activeSession.value?.projectId === project.id ? activeSession.value.workspaceId : undefined
  } : { mode: 'chat' }
  const key = draftKey(next)
  if (draft.value && draftKey(draft.value) === key) {
    void changeView('workspace')
    nextTick(() => composer.value?.focus())
    return
  }
  stashDraft()
  beginSelection()
  const { prompt: storedPrompt = '', ...saved } = drafts.get(key) || {}
  draft.value = { ...next, ...saved }
  selectedId.value = null
  messages.value = []; run.value = null; files.value = []; tasks.value = []
  prompt.value = storedPrompt; sideError.value = ''; loadError.value = ''; view.value = 'workspace'; sidebarOpen.value = false
  contextOpen.value = false; fileOpen.value = false; loadingSession.value = false
  if (project) void refreshSide()
  nextTick(() => composer.value?.focus())
}
function filesEndpoint(id = currentId.value) {
  if (id) return { url: `/sessions/${id}/files`, params: {} }
  if (!draft.value?.projectId) return null
  return { url: `/projects/${draft.value.projectId}/files`, params: { workspaceId: draft.value.workspaceId } }
}
async function refreshSide(id = currentId.value, version = selectionVersion) {
  const endpoint = filesEndpoint(id)
  if (!endpoint || plainChat.value) return
  const request = ++sideSequence
  const { signal } = selectionRequests
  const result = await Promise.allSettled([
    api.get(endpoint.url, { params: endpoint.params, signal }),
    id ? api.get(`/sessions/${id}/tasks`, { signal }) : Promise.resolve({ data: [] })
  ])
  if (id !== currentId.value || version !== selectionVersion || request !== sideSequence) return
  sideError.value = ''
  if (result[0].status === 'fulfilled') files.value = result[0].value.data
  else { files.value = []; sideError.value = errorMessage(result[0].reason) }
  if (result[1].status === 'fulfilled') tasks.value = result[1].value.data
  else sideError.value ||= errorMessage(result[1].reason)
}
async function selectSession(session, reload = false) {
  if (sending.value || uploading.value) return
  if (!reload && selectedId.value === session.id && !loadError.value) {
    await changeView('workspace')
    return
  }
  stashDraft()
  draft.value = null
  const version = beginSelection()
  const { signal } = selectionRequests
  if (selectedId.value !== session.id) prompt.value = ''
  selectedId.value = session.id; messages.value = []; run.value = null; files.value = []; tasks.value = []
  loadError.value = ''; sideError.value = ''; loadingSession.value = true; view.value = 'workspace'; sidebarOpen.value = false
  contextOpen.value = false; fileOpen.value = false
  try {
    const { data } = await api.get(`/sessions/${session.id}`, { signal })
    if (version !== selectionVersion) return
    applySession(data)
    run.value = data.latestRun || null
    loadingSession.value = false
    void scrollToBottom()
    void refreshSide(session.id, version)
    if (run.value?.id) {
      if (active.value) void pollRun(run.value.id, version, run.value)
      else if (!plainChat.value) void loadRunSummary(run.value.id, version)
    }
  } catch (e) { if (version === selectionVersion) loadError.value = errorMessage(e) }
  finally { if (version === selectionVersion) loadingSession.value = false }
}
function applySession(data) {
  const { messages: storedMessages, latestRun, ...metadata } = data
  const entry = sessions.value.find(item => item.id === data.id)
  if (entry) Object.assign(entry, metadata)
  else sessions.value.unshift(metadata)
  messages.value = storedMessages || []
}
function mergeEvents(batch) {
  if (!batch.length) return
  const byId = new Map(events.value.map(event => [event.id, event]))
  for (const event of batch) byId.set(event.id, event)
  events.value = [...byId.values()].sort((a, b) => a.id - b.id)
}
async function loadRunSummary(runId, version) {
  try {
    const { data } = await api.get(`/runs/${runId}/events`, { params: { summary: true }, signal: selectionRequests.signal })
    if (version === selectionVersion && run.value?.id === runId) mergeEvents(data)
  } catch {
    // The transcript and run snapshot remain usable; detailed events can be loaded on demand.
  }
}
async function loadExecution() {
  if (!run.value?.id || fetchingExecution.value || !moreExecution.value) return
  const version = selectionVersion
  fetchingExecution.value = true
  executionError.value = ''
  try {
    const { data } = await api.get(`/runs/${run.value.id}/events`, {
      params: { after: executionCursor }, signal: selectionRequests.signal
    })
    if (version !== selectionVersion) return
    mergeEvents(data)
    executionCursor = data.at(-1)?.id || executionCursor
    moreExecution.value = data.length === 200
  } catch (e) {
    if (version === selectionVersion) executionError.value = errorMessage(e)
  } finally {
    if (version === selectionVersion) fetchingExecution.value = false
  }
}
function toggleExecution() {
  showExecution.value = !showExecution.value
  if (showExecution.value && !active.value && !executionCursor) void loadExecution()
}
async function pollRun(runId, version, snapshot = null) {
  clearTimeout(pollTimer)
  if (destroyed || version !== selectionVersion) return
  const id = selectedId.value
  const request = ++pollSequence
  const { signal } = selectionRequests
  try {
    const [{ data: batch }, latest] = await Promise.all([
      api.get(`/runs/${runId}/events`, { params: { after: pollCursor }, signal }),
      snapshot ? Promise.resolve({ data: snapshot }) : api.get(`/runs/${runId}`, { signal })
    ])
    if (version !== selectionVersion || request !== pollSequence || destroyed) return
    mergeEvents(batch)
    pollCursor = batch.at(-1)?.id || pollCursor
    run.value = latest.data
    loadError.value = ''
    if (batch.some(event => event.type === 'text')) void scrollToBottom()
    if (batch.length === 200) {
      pollTimer = setTimeout(() => pollRun(runId, version, active.value ? null : latest.data), 0)
      return
    }
    if (!active.value && !snapshot) {
      pollTimer = setTimeout(() => pollRun(runId, version, latest.data), 0)
      return
    }
    if (active.value) {
      if (batch.some(event => event.type === 'changes' || (event.type === 'tool_result' && /"task_(?:create|update)"/.test(event.content)))) {
        void refreshSide(id, version)
      }
      pollTimer = setTimeout(() => pollRun(runId, version), 1200)
    } else {
      moreExecution.value = false
      const { data } = await api.get(`/sessions/${id}`, { signal })
      if (version !== selectionVersion || request !== pollSequence) return
      applySession(data)
      void refreshSide(id, version)
      if (view.value === 'analytics') void refreshStats()
      void scrollToBottom()
    }
  } catch (e) {
    if (version !== selectionVersion || request !== pollSequence || destroyed) return
    loadError.value = errorMessage(e)
    if (active.value) pollTimer = setTimeout(() => pollRun(runId, version), 3500)
  }
}
async function send() {
  const text = prompt.value.trim()
  if (!text || sending.value || active.value || uploading.value || loadingSession.value || initialLoading.value || deletingItem.value) return
  if (!connected.value || !status.value?.configured) { settingsOpen.value = true; return }
  if (status.value?.databaseReady === false) { loadError.value = connectionError.value; return }
  sending.value = true; loadError.value = ''; view.value = 'workspace'
  try {
    const id = await ensureSession()
    const createdRun = (await api.post(`/sessions/${id}/runs`, { prompt: text })).data
    if (draft.value) {
      const session = draft.value
      const timestamp = new Date().toISOString()
      sessions.value = [{ ...session, title: text.slice(0, 24), updatedAt: timestamp },
        ...sessions.value.filter(item => item.id !== id)]
      drafts.delete(draftKey(session))
      selectedId.value = id
      draft.value = null
    }
    const version = beginSelection()
    prompt.value = ''; run.value = createdRun
    messages.value.push({ id: `local-${Date.now()}`, role: 'user', content: text, createdAt: new Date().toISOString() })
    await scrollToBottom()
    void pollRun(createdRun.id, version, createdRun)
  } catch (e) { loadError.value = errorMessage(e); ElMessage.error(loadError.value) }
  finally { sending.value = false }
}
async function ensureSession() {
  if (currentId.value) return currentId.value
  const { mode, projectId, workspaceId } = draft.value
  const { data } = await api.post('/sessions', { mode, projectId, workspaceId })
  Object.assign(draft.value, data)
  return data.id
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
  const endpoint = filesEndpoint()
  if (!endpoint) return
  fileOpen.value = true; fileLoading.value = true; openedFile.value = { path: file.path, content: '' }
  try { openedFile.value = (await api.get(`${endpoint.url}/content`, { params: { ...endpoint.params, path: file.path } })).data }
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
  if (!selected.length || active.value || uploading.value || sending.value || plainChat.value || loadingSession.value || deletingItem.value) return
  if (selected.length > 2000 || selected.reduce((total,file) => total + file.size, 0) > 25000000) { ElMessage.error('一次最多导入 2000 个文件、总计 25 MB。'); return }
  const invalid = selected.find(file => file.size > 1024 * 1024)
  if (invalid) { ElMessage.error(`${invalid.name} 超过了 1 MB，请选择较小的代码或文本文件。`); return }
  uploading.value = true
  try {
    const id = await ensureSession()
    for (const file of selected) {
      const data = new FormData(); data.append('file', file)
      if (file.webkitRelativePath) data.append('path', file.webkitRelativePath.split('/').slice(1).join('/'))
      await api.post(`/sessions/${id}/files`, data)
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
function showCreateProject() {
  editingProject.value = null
  projectName.value = ''
  projectDirectory.value = ''
  projectFormError.value = ''
  projectDialog.value = true
}
function showProjectDirectory(project) {
  editingProject.value = project
  projectDirectory.value = project.localDirectory || ''
  projectFormError.value = ''
  projectDialog.value = true
}
async function saveProject() {
  const project = editingProject.value
  if (savingProject.value || (!project && !projectName.value.trim())) return
  if (project && activeProject.value?.id === project.id && (uploading.value || sending.value)) {
    projectFormError.value = '请等待当前操作结束后再更改目录。'
    return
  }
  savingProject.value = true
  projectFormError.value = ''
  try {
    const localDirectory = projectDirectory.value.trim() || null
    const { data } = project
      ? await api.put(`/projects/${project.id}/directory`, { localDirectory })
      : await api.post('/projects', { name: projectName.value.trim(), localDirectory })
    ++projectRequest
    projects.value = project ? projects.value.map(item => item.id === data.id ? data : item) : [data, ...projects.value]
    projectsError.value = ''
    projectDialog.value = false
    if (!project) projectTree.value?.focusProjects()
    else if (activeSession.value?.projectId === project.id) {
      files.value = []
      fileOpen.value = false
      void refreshSide()
    }
    void loadProjects()
  } catch (e) {
    projectFormError.value = errorMessage(e)
  } finally {
    savingProject.value = false
  }
}
async function deleteSidebarItem(item, project = false) {
  if (deletingItem.value) return
  const kind = project ? '项目' : '聊天'
  const name = project ? item.name : item.title || '新聊天'
  const isSelected = () => project ? activeSession.value?.projectId === item.id : selectedId.value === item.id
  const blocked = () => isSelected() && (sending.value || uploading.value)
  if (blocked()) { ElMessage.warning('请等待当前操作结束后再删除。'); return }
  deletingItem.value = true
  try {
    await ElMessageBox.confirm(
      project ? `将从工作台移除「${name}」及其聊天和项目资料。本地文件会保留。`
        : `将从工作台移除聊天「${name}」。工作文件会保留。`,
      `删除${kind}`, { type: 'warning', confirmButtonType: 'danger', confirmButtonText: `删除${kind}`, cancelButtonText: '取消' }
    )
    if (blocked()) { ElMessage.warning('请等待当前操作结束后再删除。'); return }
    await api.delete(`/${project ? 'projects' : 'sessions'}/${item.id}`)
    if (isSelected()) newSession()
    if (project) {
      drafts.delete(item.id)
      projects.value = projects.value.filter(entry => entry.id !== item.id)
      if (projectResources.value?.project.id === item.id) projectResources.value = null
    }
    sessions.value = sessions.value.filter(entry => project ? entry.projectId !== item.id : entry.id !== item.id)
    void initialize()
    ElMessage.success(`已删除${kind}`)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(errorMessage(e))
  } finally {
    deletingItem.value = false
  }
}
function showProjects() {
  if (!projects.value.length && !projectsError.value) {
    showCreateProject()
    return
  }
  if (window.matchMedia('(max-width: 960px)').matches) sidebarOpen.value = true
  projectTree.value?.focusProjects()
}
function openProjectResources(project, resourceId = null) {
  projectResources.value = { project, resourceId }
}
function showActiveProjectResources(resourceId = null) {
  const session = activeSession.value
  if (!session?.projectId) return
  openProjectResources(projects.value.find(project => project.id === session.projectId)
    || { id: session.projectId, name: session.projectName || '当前项目' }, resourceId)
}
function openKnowledgeSource(source) {
  if (source.kind === 'workspace') openFile({ path: source.source })
  else showActiveProjectResources(source.sourceId.replace(/^project:/, ''))
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
onBeforeUnmount(() => { destroyed = true; selectionRequests.abort(); clearTimeout(pollTimer); clearTimeout(healthTimer); chartObserver?.disconnect(); chart?.dispose() })
</script>

<template>
  <div class="app-shell">
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="sidebarOpen = false"></div>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <a class="brand" href="#" @click.prevent="newSession">
        <div class="brand-mark"><img :src="mascotUrl" alt="薄荷猫娘" width="30" height="30" /></div>
        <strong>Minty Code<span class="brand-dot">.</span></strong>
      </a>
      <button class="new-chat" @click="newSession"><Icon name="compose" :size="17" /><span>新聊天</span></button>
      <ProjectSidebar ref="projectTree" :projects="projects" :sessions="sessions"
        :selected-id="view === 'workspace' ? selectedId : null" :loading="initialLoading"
        :project-error="projectsError" :sessions-error="sessionsError"
        @create-project="showCreateProject" @create-chat="openDraft" @open-chat="selectSession"
        @open-resources="openProjectResources" @set-directory="showProjectDirectory"
        @delete-project="deleteSidebarItem($event, true)" @delete-chat="deleteSidebarItem($event)" @reload="initialize" />
      <nav class="main-nav sidebar-tools" aria-label="工具导航">
        <button :class="{ selected: view === 'workflow' }" @click="changeView('workflow')"><Icon name="workflow" /><span>工作流程</span><span class="nav-dot" v-if="view === 'workflow'"></span></button>
        <button :class="{ selected: view === 'analytics' }" @click="changeView('analytics')"><Icon name="chart" /><span>使用概览</span></button>
        <button :class="{ selected: view === 'evaluation' }" @click="changeView('evaluation')"><Icon name="shield" /><span>Agent 评测</span><span class="nav-dot" v-if="view === 'evaluation'"></span></button>
      </nav>
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
          <div class="conversation-header"><div><span class="mini-icon"><Icon name="chat" :size="15" /></span><strong>{{ selectedId ? activeSession?.title : plainChat ? '普通聊天' : activeProject?.name || activeSession?.projectName || '项目聊天' }}</strong><span v-if="selectedId" class="session-badge">已保存</span></div><div class="conversation-actions"><button v-if="run" class="workflow-link" @click="changeView('workflow')"><Icon name="workflow" :size="14" /><span>查看流程</span></button><button v-if="!plainChat" class="icon-button context-toggle" title="任务与文件" aria-label="打开任务与文件" @click="contextOpen = true"><Icon name="folder" :size="16" /></button><button class="icon-button" title="设置与连接" aria-label="设置与连接" @click="settingsOpen = true"><Icon name="settings" :size="16" /></button></div></div>
          <RunOutcome v-if="!plainChat" :run="run" :events="events" />
          <TaskProgress v-if="!plainChat" :run="run" :events="events" :busy="stopping" @pause="runAction('pause')" @resume="runAction('resume')" />
          <div ref="chatScroller" class="chat-scroll">
            <div v-if="loadingSession" class="loading-state"><span class="loading-dot"></span>正在打开对话…</div>
            <div v-else-if="!messages.length && !active" class="welcome">
              <div class="welcome-mark"><Icon name="spark" :size="21" /></div>
              <h1>{{ plainChat ? '今天想聊些什么' : '今天想做点什么' }}<span>？</span></h1>
              <p v-if="plainChat">直接提问、讨论想法，或到项目里开始编程。</p>
              <p v-else>可以导入代码、提出编程任务，或查询项目资料。</p>
              <el-button v-if="plainChat" @click="showProjects">浏览项目</el-button>
            </div>
            <div v-else class="message-list">
              <div class="conversation-start"><span></span>灵感从这里开始<span></span></div>
              <article v-for="message in transcript" :key="message.id" :class="['message', message.role]"><div :class="['message-avatar', message.role]"><span v-if="message.role === 'assistant'" class="assistant-mark"><Icon name="spark" :size="13" /></span><span v-else>我</span></div><div class="message-body"><div class="message-meta"><strong>{{ message.role === 'assistant' ? '薄荷' : '你' }}</strong><span>{{ formatTime(message.createdAt) }}</span></div><div v-if="message.role === 'assistant'" class="markdown-content" v-html="message.html"></div><div v-else class="user-content">{{ message.content }}</div></div></article>
              <article v-if="active" class="message assistant"><div class="message-avatar assistant"><span class="assistant-mark"><Icon name="spark" :size="13" /></span></div><div class="message-body"><div class="message-meta"><strong>薄荷</strong><span class="thinking-label"><span class="loading-dot"></span>{{ run?.status === 'stopping' ? '正在收好手头的工作' : '正在认真处理' }}</span></div><div v-if="liveText" class="markdown-content" v-html="markdown(liveText)"></div><div v-else class="thinking-bubbles"><i></i><i></i><i></i></div></div></article>
              <KnowledgeSources v-if="activeSession?.projectId" :events="events" @open-source="openKnowledgeSource" />
              <div v-if="run && !plainChat" class="execution-card">
                <button class="execution-heading execution-toggle" :aria-expanded="showExecution" @click="toggleExecution">
                  <Icon name="terminal" :size="16" /><strong>执行足迹</strong><span v-if="active" class="live-badge">LIVE</span>
                  <Icon name="chevron" :size="13" :class="{ rotated: showExecution }" />
                </button>
                <div v-if="showExecution" class="execution-items">
                  <div v-for="event in toolEvents" :key="event.id" :class="['execution-item', { error: event.type === 'error' }]">
                    <button @click="toggleEvent(event.id)">
                      <Icon :name="event.type === 'error' ? 'close' : event.type === 'tool_start' ? 'terminal' : 'check'" :size="14" />
                      <span>{{ eventTitle(event) }}</span><small>{{ formatTime(event.createdAt) }}</small>
                      <Icon name="chevron" :size="13" :class="{ rotated: openedEvents.has(event.id) }" />
                    </button>
                    <pre v-if="openedEvents.has(event.id)">{{ eventDetails(event) }}</pre>
                  </div>
                  <div v-if="executionError" class="side-error">{{ executionError }}</div>
                  <button v-if="!active && moreExecution" class="text-button execution-more" :disabled="fetchingExecution" @click="loadExecution">
                    {{ fetchingExecution ? '正在加载…' : executionError ? '重试加载' : '加载更多记录' }}
                  </button>
                </div>
              </div>
              <div v-if="run && !active" :class="['run-summary', normalizeState(run.status)]"><Icon :name="normalizeState(run.status) === 'failed' ? 'close' : 'check'" :size="14" /><span>{{ stateLabel(run.status) }}</span><span v-if="tokenCount">· {{ formatCount(tokenCount) }} tokens</span><span v-if="run.error" class="run-error">{{ run.error }}</span></div>
            </div>
          </div>
          <div class="composer-wrap">
            <el-alert v-if="connectionError || loadError" :title="connectionError || loadError" type="error" :closable="false" show-icon class="composer-alert"><template #default><button class="text-button" @click="reconnect">重新连接</button></template></el-alert>
            <div v-else-if="!initialLoading && connected && !status?.configured" class="config-banner"><Icon name="bolt" :size="16" /><span>再一步就好：配置模型 API Key，即可开始对话。</span><button @click="settingsOpen = true">查看配置 <Icon name="chevron" :size="12" /></button></div>
            <div class="composer" :class="{ busy: active }"><textarea ref="composer" v-model="prompt" :disabled="active || sending || loadingSession" :aria-label="plainChat ? '输入聊天消息' : '输入编程任务'" placeholder="给薄荷发消息…" rows="2" maxlength="20000" @keydown="onComposerKey"></textarea><div class="composer-bottom"><div class="composer-context"><Icon name="folder" :size="13" /><span>{{ scopeLabel }}</span><span class="context-separator"></span><span class="model-name">{{ status?.model || '模型未连接' }}</span></div><button v-if="active" class="stop-button" :disabled="stopping" @click="stop"><Icon name="stop" :size="11" />{{ stopping ? '停止中' : '停止任务' }}</button><button v-else class="send-button" :disabled="!prompt.trim() || sending || initialLoading || uploading || loadingSession" aria-label="发送任务" @click="send"><span v-if="sending" class="loading-dot"></span><Icon v-else name="arrow" :size="17" /></button></div></div>
            <div class="composer-footnote"><span><kbd>Enter</kbd> 发送 · <kbd>Shift + Enter</kbd> 换行</span><span><Icon name="shield" :size="12" />仅供个人使用，请检查重要修改</span></div>
          </div>
        </section>

        <div v-if="contextOpen" class="context-scrim" @click="contextOpen = false"></div><aside v-if="!plainChat" class="context-panel" :class="{ open: contextOpen }">
          <div class="context-heading"><div><Icon name="layers" :size="15" /><strong>任务与文件</strong></div><button v-if="contextOpen" class="icon-button" aria-label="关闭工作区" @click="contextOpen = false"><Icon name="close" :size="14" /></button><button class="icon-button" aria-label="刷新任务和文件" title="刷新任务和文件" @click="refreshSide()"><Icon name="refresh" :size="13" /></button></div>
          <div class="context-tabs"><button :class="{ selected: sideTab === 'tasks' }" @click="sideTab = 'tasks'">任务清单<span>{{ tasks.length }}</span></button><button :class="{ selected: sideTab === 'files' }" @click="sideTab = 'files'">工作文件<span>{{ files.length }}</span></button></div>
          <div v-if="sideTab === 'files'" class="upload-toolbar"><input ref="uploadInput" type="file" multiple hidden @change="uploadFiles" /><input ref="folderInput" type="file" webkitdirectory multiple hidden @change="uploadFiles" /><button class="upload-button" :disabled="active || uploading || sending" @click="uploadInput?.click()"><span v-if="uploading" class="loading-dot"></span><Icon v-else name="plus" :size="14" />{{ uploading ? '正在放入文件…' : '添加代码文件' }}</button><button class="upload-button" :disabled="active || uploading || sending" @click="folderInput?.click()">导入代码目录</button><small>跳过隐藏配置、密钥和依赖 · 单文件 ≤ 1 MB</small></div><div v-if="sideError" class="side-error">{{ sideError }}</div>
          <div class="context-content">
            <template v-if="sideTab === 'tasks'"><div v-if="tasks.length" class="task-progress"><div><span>完成进度</span><strong>{{ taskProgress }}%</strong></div><el-progress :percentage="taskProgress" :show-text="false" :stroke-width="4" color="#5f9472" /></div><div v-if="!tasks.length" class="context-empty"><div class="empty-illustration"><Icon name="check" :size="19" /></div><strong>暂无任务</strong><p>薄荷拆解任务后，<br />会在这里记下每一个小步骤。</p></div><div v-for="task in tasks" :key="task.id" class="task-item"><span :class="['task-state', normalizeState(task.status)]"><Icon :name="normalizeState(task.status) === 'completed' ? 'check' : 'circle'" :size="13" /></span><div><strong>{{ task.subject || task.title }}</strong><p v-if="task.description">{{ task.description }}</p><small>{{ stateLabel(task.status) }}</small></div></div></template>
            <template v-else><div v-if="!files.length" class="context-empty"><div class="empty-illustration file-empty"><Icon name="folder" :size="19" /></div><strong>暂无文件</strong><p>添加已有代码，或让薄荷创建文件，<br />它们会被好好收在这里。</p></div><button v-for="file in files" :key="file.path" class="file-item" @click="openFile(file)"><span class="file-icon"><Icon name="file" :size="15" /></span><span><strong>{{ file.path.split('/').pop() }}</strong><small :title="file.path">{{ file.path }}</small></span><small>{{ bytes(file.size || 0) }}</small></button></template>
          </div>
          <div class="workspace-info"><div class="info-eyebrow">当前工作区</div><div><Icon name="folder" :size="13" /><span>{{ scopeLabel }}</span></div>
            <template v-if="activeSession?.projectId">
              <p v-if="activeProject?.localDirectory" class="workspace-directory" :title="activeProject.localDirectory">{{ activeProject.localDirectory }}</p>
              <button class="text-button" @click="showActiveProjectResources()">查看项目资料</button>
            </template>
            <p v-else>当前会话的任务与文件独立保存。</p></div>
        </aside>
      </div>
    </main>

    <el-dialog v-model="settingsOpen" width="520px" align-center><template #header="{ titleId }"><DialogHeading :id="titleId" icon="settings" title="设置与连接" description="查看服务、数据库与模型的连接状态。" /></template><div class="settings-rows"><div><span>后端连接</span><strong :class="connected ? 'text-mint' : 'text-error'">{{ connected ? '连接正常' : '尚未连接' }}</strong></div><div><span>数据库连接</span><strong>{{ !connected ? '尚未检查' : status?.databaseReady === false ? '连接中断' : status?.databaseReady === true ? '连接正常' : '等待检查' }}</strong></div><div><span>模型配置</span><strong>{{ status?.configured ? '已配置' : '尚未配置' }}</strong></div><div><span>当前模型</span><code>{{ status?.model || '—' }}</code></div><div><span>前台命令</span><strong>{{ status?.shellEnabled ? '已启用（Docker 隔离）' : '已关闭' }}</strong></div><div><span>服务状态</span><strong>{{ statusLabel }}</strong></div></div><div class="settings-guide"><strong>首次使用</strong><ol><li>按项目 README 配置 PostgreSQL 与后端环境变量。</li><li>设置 <code>ANTHROPIC_API_KEY</code>、<code>MODEL_ID</code>；需要时设置兼容服务的地址。</li><li>启动后端，再点击下方按钮检查连接。</li></ol><p>为了保护密钥，配置需要在本机后端完成。此页面不会展示或保存 API Key。</p></div><template #footer><el-button @click="settingsOpen = false">关闭</el-button><el-button type="primary" :loading="initialLoading" @click="initialize">重新检查连接</el-button></template></el-dialog>
    <el-dialog v-model="fileOpen" width="900px" align-center><template #header="{ titleId }"><DialogHeading :id="titleId" icon="file" title="文件预览" :description="openedFile.path" /></template><div v-if="fileLoading" class="loading-state">正在打开文件…</div><pre v-else class="file-preview">{{ openedFile.content }}</pre><template #footer><el-button @click="fileOpen = false">关闭</el-button><el-button :disabled="fileLoading" @click="downloadFile">下载文件</el-button><el-button type="primary" :disabled="fileLoading" @click="copyFile"><Icon name="copy" :size="14" />复制内容</el-button></template></el-dialog>
    <el-dialog v-model="projectDialog" width="540px" align-center destroy-on-close
      :close-on-click-modal="!savingProject" :close-on-press-escape="!savingProject" :show-close="!savingProject">
      <template #header="{ titleId }">
        <DialogHeading :id="titleId" icon="folder" :title="editingProject ? '设置本地目录' : '新建项目'"
          :description="editingProject ? editingProject.name : '在一起整理项目聊天、代码和资料。'" />
      </template>
      <form id="project-form" class="new-project-form" @submit.prevent="saveProject">
        <el-alert v-if="projectFormError" :title="projectFormError" type="error" :closable="false" />
        <label v-if="!editingProject">
          <span class="dialog-field-label">项目名称</span>
          <el-input v-model="projectName" placeholder="例如，代码助手" aria-label="项目名称" maxlength="120" />
        </label>
        <DirectoryPicker v-model="projectDirectory" :disabled="savingProject" />
      </form>
      <template #footer>
        <el-button :disabled="savingProject" @click="projectDialog = false">取消</el-button>
        <el-button form="project-form" type="primary" native-type="submit" :loading="savingProject" :disabled="!editingProject && !projectName.trim()">
          {{ editingProject ? '保存目录设置' : '创建项目' }}
        </el-button>
      </template>
    </el-dialog>
    <ProjectResources v-if="projectResources" :key="`${projectResources.project.id}:${projectResources.resourceId || ''}`"
      :project="projectResources.project" :preferred-resource="projectResources.resourceId" @close="projectResources = null" />
  </div>
</template>
