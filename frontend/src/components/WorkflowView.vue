<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import Icon from './Icon.vue'
import WorkflowRing from './WorkflowRing.vue'
import { api, errorMessage } from '../api'
import { buildWorkflow, elapsedMs, formatDetail, formatDuration, isActiveRun, stateLabel } from '../workflow.js'
import { buildLoopWorkflow } from '../workflowLoop.js'

const props = defineProps({ sessions: { type: Array, default: () => [] }, preferredSessionId: { type: String, default: null } })
const emit = defineEmits(['open-session'])
const sessionFilter = ref(props.preferredSessionId || '')
const runs = ref([])
const run = ref(null)
const selectedRunId = ref('')
const events = ref([])
const selectedNodeId = ref('')
const selectedStageId = ref('')
const followNewest = ref(true)
const followStep = ref(true)
const loadingList = ref(false)
const loadingRun = ref(false)
const stopping = ref(false)
const listError = ref('')
const pollError = ref('')
const syncedAt = ref(null)
const now = ref(Date.now())
let listTimer, runTimer, clockTimer, listController, runController
let listVersion = 0
let runVersion = 0
let pollRequestVersion = 0
let destroyed = false

const flow = computed(() => buildWorkflow(run.value, events.value))
const loop = computed(() => buildLoopWorkflow(run.value, events.value))
const active = computed(() => isActiveRun(run.value))
const selectedNode = computed(() => flow.value.nodes.find(node => node.id === selectedNodeId.value) || null)
const selectedStage = computed(() => loop.value.stages.find(stage => stage.id === selectedStageId.value) || null)
const stageEntries = computed(() => [...(selectedStage.value?.entries || [])].reverse())
const displayedRuns = computed(() => run.value && !runs.value.some(item => item.id === run.value.id) ? [run.value, ...runs.value] : runs.value)
const totalTokens = computed(() => Number(run.value?.inputTokens || 0) + Number(run.value?.outputTokens || 0))
const elapsed = computed(() => formatDuration(elapsedMs(run.value?.createdAt, run.value?.finishedAt, now.value)))
const isLegacy = computed(() => run.value && !active.value && !flow.value.hasModelEvents)
const currentDescription = computed(() => !run.value ? '选择一条运行记录' : pollError.value || listError.value ? '连接中断，保留最近观察到的状态' : flow.value.phaseLabel)
const kindLabel = node => ({ request: '任务输入', phase: selectedStage.value?.title || '执行阶段', model: '模型调用', tool: '工具执行', reply: '历史回复', waiting: '等待进度', result: '运行结果' })[node?.kind] || '执行步骤'
const sessionName = id => props.sessions.find(session => session.id === id)?.title || '历史会话'
const shortDate = value => value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '时间未记录'
const clock = value => value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '—'
const fullDate = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未记录'
const runLabel = item => `${shortDate(item.createdAt)} · ${(item.prompt || sessionName(item.sessionId)).slice(0, 80)} · ${stateLabel(item.status)}`
const count = number => Number(number || 0).toLocaleString('zh-CN')
const cancelled = error => error?.code === 'ERR_CANCELED'

function selectStage(id) {
  selectedStageId.value = id
  selectedNodeId.value = loop.value.stages.find(stage => stage.id === id)?.latest?.id || ''
  followStep.value = false
}
function followCurrent() {
  if (!followStep.value) return
  selectedNodeId.value = loop.value.currentEntryId || flow.value.nodes.at(-1)?.id || ''
  selectedStageId.value = loop.value.currentStageId || loop.value.stages.find(stage => stage.entries.some(entry => entry.id === selectedNodeId.value))?.id || 'input'
}
watch(() => [loop.value.currentStageId, loop.value.currentEntryId, flow.value.nodes.length], () => {
  if (followStep.value) followCurrent()
})
watch(() => selectedStage.value?.entries, entries => {
  if (entries?.length && !entries.some(entry => entry.id === selectedNodeId.value)) selectedNodeId.value = entries.at(-1).id
})
watch(() => props.preferredSessionId, id => { if (id !== undefined) sessionFilter.value = id || '' })
watch(sessionFilter, () => {
  selectRun('')
  followNewest.value = true
  refreshRuns()
})

async function refreshRuns() {
  const version = ++listVersion
  clearTimeout(listTimer)
  listController?.abort()
  listController = new AbortController()
  loadingList.value = true
  try {
    const response = await api.get('/runs', { params: sessionFilter.value ? { sessionId: sessionFilter.value } : {}, signal: listController.signal })
    if (destroyed || version !== listVersion) return
    runs.value = Array.isArray(response.data) ? response.data : []
    listError.value = ''
    const newest = runs.value.find(isActiveRun) || runs.value[0]
    if (newest && (!selectedRunId.value || followNewest.value && newest.id !== selectedRunId.value)) selectRun(newest.id)
  } catch (error) {
    if (!destroyed && version === listVersion && !cancelled(error)) listError.value = errorMessage(error)
  } finally {
    if (!destroyed && version === listVersion) {
      loadingList.value = false
      listTimer = setTimeout(refreshRuns, 4000)
    }
  }
}
function selectRun(id, manual = false) {
  if (manual) followNewest.value = false
  const version = ++runVersion
  clearTimeout(runTimer)
  runController?.abort()
  selectedRunId.value = id || ''
  run.value = runs.value.find(item => item.id === id) || null
  events.value = []; selectedNodeId.value = ''; selectedStageId.value = ''; syncedAt.value = null; pollError.value = ''
  followStep.value = true
  loadingRun.value = Boolean(id)
  if (id) pollRun(id, version)
}
function followLatest() {
  if (!followNewest.value) return
  const newest = runs.value.find(isActiveRun) || runs.value[0]
  if (newest && newest.id !== selectedRunId.value) selectRun(newest.id)
}
function appendEvents(batch) {
  const known = new Set(events.value.map(event => event.id))
  events.value.push(...batch.filter(event => !known.has(event.id)))
  events.value.sort((a, b) => Number(a.id) - Number(b.id))
}
async function pollRun(id, version) {
  clearTimeout(runTimer)
  if (destroyed || version !== runVersion) return
  runController?.abort()
  runController = new AbortController()
  const signal = runController.signal
  const requestVersion = ++pollRequestVersion
  const stale = () => destroyed || version !== runVersion || requestVersion !== pollRequestVersion
  let more = false
  try {
    let after = events.value.at(-1)?.id || 0
    const [runResponse, eventResponse] = await Promise.all([
      api.get(`/runs/${id}`, { signal }), api.get(`/runs/${id}/events`, { params: { after }, signal }),
    ])
    if (stale()) return
    run.value = runResponse.data
    appendEvents(eventResponse.data)
    let batch = eventResponse.data
    let pages = 0
    // Drain pagination. Re-read the terminal tail because run/events requests race.
    let finalTail = !isActiveRun(run.value)
    while ((batch.length === 200 || finalTail) && pages++ < 20) {
      after = events.value.at(-1)?.id || 0
      const response = await api.get(`/runs/${id}/events`, { params: { after }, signal })
      if (stale()) return
      batch = response.data
      appendEvents(batch)
      finalTail = false
      if (batch.length && events.value.at(-1)?.id === after) break
    }
    more = batch.length === 200
    syncedAt.value = Date.now()
    pollError.value = ''
    loadingRun.value = false
    if (!selectedStageId.value) followCurrent()
  } catch (error) {
    if (stale() || cancelled(error)) return
    pollError.value = errorMessage(error)
    loadingRun.value = false
  } finally {
    if (!stale() && (isActiveRun(run.value) || pollError.value || more)) {
      runTimer = setTimeout(() => pollRun(id, version), pollError.value ? 3000 : more ? 300 : 1200)
    }
  }
}
async function refresh() {
  await refreshRuns()
  if (selectedRunId.value) await pollRun(selectedRunId.value, runVersion)
}
async function stopRun() {
  if (!run.value || stopping.value) return
  const id = run.value.id
  const version = runVersion
  stopping.value = true
  try {
    const response = await api.post(`/runs/${id}/stop`)
    if (version === runVersion) await pollRun(id, version)
    ElMessage.info(response.data?.status === 'stopped' ? '已停止任务' : `任务${stateLabel(response.data?.status)}`)
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { stopping.value = false }
}
function openWorkspace() {
  emit('open-session', run.value?.sessionId || sessionFilter.value || props.preferredSessionId || '')
}
onMounted(() => { refreshRuns(); clockTimer = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => {
  destroyed = true; listVersion++; runVersion++
  clearTimeout(listTimer); clearTimeout(runTimer); clearInterval(clockTimer)
  listController?.abort(); runController?.abort()
})
</script>

<template>
  <section class="workflow-view">
    <header class="workflow-heading">
      <div class="workflow-title">
        <h1>工作流程</h1>
        <span class="workflow-sub">Agent 主循环 · 固定环形流程</span>
      </div>
      <div class="workflow-actions">
        <el-button v-if="active" type="warning" plain :loading="stopping" @click="stopRun"><Icon name="stop" :size="13" />停止任务</el-button>
        <el-button :loading="loadingList || loadingRun" @click="refresh"><Icon name="refresh" :size="13" />刷新</el-button>
        <el-button @click="openWorkspace">返回工作台</el-button>
      </div>
    </header>

    <div class="workflow-toolbar">
      <div class="workflow-filters">
        <label><span>会话范围</span><el-select v-model="sessionFilter" aria-label="选择会话范围" placeholder="全部会话"><el-option label="全部会话" value="" /><el-option v-for="session in sessions" :key="session.id" :value="session.id" :label="session.title || '新的对话'" /></el-select></label>
        <label class="run-filter"><span>运行记录</span><el-select :model-value="selectedRunId" aria-label="选择运行记录" placeholder="选择一次任务运行" :loading="loadingList" @update:model-value="id => selectRun(id, true)"><el-option v-for="item in displayedRuns" :key="item.id" :value="item.id" :label="runLabel(item)" /></el-select></label>
        <el-checkbox v-model="followNewest" @change="followLatest">跟随新任务</el-checkbox>
      </div>
      <div v-if="run" class="run-metrics">
        <span><Icon name="clock" :size="13" />{{ elapsed }}</span>
        <span>{{ count(totalTokens) }} Token</span>
        <span>工具 {{ flow.completedTools }}/{{ flow.tools }}<template v-if="flow.failedTools"> · {{ flow.failedTools }} 次失败</template></span>
        <span class="last-sync">同步 {{ clock(syncedAt) }}</span>
      </div>
    </div>

    <div v-if="listError || pollError" class="connection-warning" role="status">
      <Icon name="clock" :size="15" />
      <div><strong>连接暂时中断，正在自动重试</strong><p>{{ pollError || listError }}<template v-if="syncedAt"> · 保留 {{ clock(syncedAt) }} 的观察结果。</template></p></div>
    </div>

    <div v-if="run" class="workflow-content">
      <section class="flow-panel" aria-label="Agent 执行流程图">
        <div class="panel-heading">
          <div class="panel-title">
            <Icon name="workflow" :size="14" />
            <h2>Agent 主循环</h2>
            <span class="current-step"><span class="state-dot" :class="{ live: active && !pollError && !listError, failed: run.status === 'failed' }"></span>{{ currentDescription }}</span>
            <span class="status-badge" :class="run.status">{{ stateLabel(run.status) }}</span>
          </div>
          <el-checkbox v-model="followStep" @change="followCurrent">跟随当前阶段</el-checkbox>
        </div>
        <div v-if="isLegacy" class="legacy-note">此历史记录只保存了部分阶段；图中仅点亮有执行记录的节点。</div>
        <WorkflowRing :stages="loop.stages" :edges="loop.edges" :nodes="flow.nodes" :current-stage-id="loop.currentStageId" :selected-stage-id="selectedStageId" :run-status="run?.status" :rounds="loop.rounds" :active="active" :disconnected="Boolean(pollError || listError)" :follow="followStep" @select-stage="selectStage" />
      </section>

      <aside class="flow-details" aria-label="执行步骤详情">
        <div class="panel-heading">
          <div class="panel-title"><Icon name="file" :size="13" /><h2>{{ selectedStage?.title || '阶段详情' }}</h2></div>
          <span v-if="selectedStage" class="visit-count">{{ selectedStage.visits }} 次记录</span>
        </div>
        <div v-if="stageEntries.length > 1" class="entry-picker"><label for="stage-entry">执行记录</label><el-select id="stage-entry" v-model="selectedNodeId" aria-label="选择阶段执行记录" @change="followStep = false"><el-option v-for="entry in stageEntries" :key="entry.id" :value="entry.id" :label="`${entry.round && entry.kind !== 'model' ? `第 ${entry.round} 轮 · ` : ''}${entry.title} · ${stateLabel(entry.status)}`" /></el-select></div>
        <div v-if="selectedNode" class="detail-content">
          <div class="detail-title"><span>{{ kindLabel(selectedNode) }}</span><h3>{{ selectedNode.title }}</h3><span class="status-badge" :class="selectedNode.status">{{ stateLabel(selectedNode.status) }}</span></div>
          <dl class="detail-facts">
            <div><dt>开始时间</dt><dd>{{ fullDate(selectedNode.startedAt) }}</dd></div>
            <div><dt>结束时间</dt><dd>{{ selectedNode.endedAt ? fullDate(selectedNode.endedAt) : selectedNode.status === 'running' ? '执行中' : '未记录' }}</dd></div>
            <div v-if="selectedNode.kind !== 'request' && selectedNode.kind !== 'result'"><dt>步骤耗时</dt><dd>{{ formatDuration(elapsedMs(selectedNode.startedAt, selectedNode.endedAt, now)) }}</dd></div>
            <div v-if="selectedNode.round"><dt>模型轮次</dt><dd>第 {{ selectedNode.round }} 轮</dd></div>
            <div v-if="selectedNode.inputTokens != null"><dt>本轮 Token</dt><dd>{{ count(selectedNode.inputTokens) }} 输入 / {{ count(selectedNode.outputTokens) }} 输出</dd></div>
          </dl>
          <section v-if="formatDetail(selectedNode.input)" class="detail-section"><h4>输入</h4><pre>{{ formatDetail(selectedNode.input) }}</pre></section>
          <section v-if="formatDetail(selectedNode.output)" class="detail-section"><h4>{{ selectedNode.kind === 'result' && run.status === 'failed' ? '错误信息' : '输出' }}</h4><pre :class="{ 'error-output': selectedNode.status === 'failed' }">{{ formatDetail(selectedNode.output) }}</pre></section>
          <p v-else class="detail-placeholder">{{ selectedNode.status === 'running' ? '等待此步骤返回结果…' : selectedNode.kind === 'request' ? '任务已提交给执行服务。' : '此步骤没有额外的文本输出。' }}</p>
        </div>
        <div v-else class="detail-empty">
          <Icon :name="selectedStage?.icon || 'circle'" :size="22" />
          <h3>{{ selectedStage ? '此阶段尚无执行记录' : '点击环上的节点' }}</h3>
          <p>{{ selectedStage?.description || '查看当前状态、输入输出，以及此阶段每一轮的执行记录。' }}</p>
        </div>
      </aside>
    </div>

    <div v-else class="workflow-empty">
      <Icon name="workflow" :size="18" />
      <p>{{ loadingList ? '正在读取运行记录…' : listError ? '恢复连接后，会自动读取任务状态。' : '还没有任务。开始一次对话后，环上的对应节点会自动点亮。' }}</p>
      <el-button @click="openWorkspace">前往工作台</el-button>
    </div>
  </section>
</template>

<style scoped src="./WorkflowView.css"></style>
