<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Icon from './Icon.vue'
import { api, errorMessage } from '../api'
import './EvaluationView.css'

const environment = ref(null), history = ref([]), current = ref(null), catalog = ref([])
const events = ref([]), error = ref(''), busy = ref(false), catalogLoading = ref(false)
const selectedTask = ref(''), tab = ref('trace'), artifact = ref(''), artifactLoading = ref(false)
const query = ref(''), chosen = ref([]), showNew = ref(false)
const traceElement = ref(null), follow = ref(true)
const form = ref({ name: 'SWE-Lite · 基线实验', limit: 1, maxSteps: 24, timeoutSeconds: 1200, testTimeoutSeconds: 900 })
let timer, dead = false, version = 0, artifactVersion = 0
const activeStates = ['running', 'preparing', 'stopping']
const running = computed(() => activeStates.includes(current.value?.status))
const anyActive = computed(() => history.value.some(run => activeStates.includes(run.status)))
const tasks = computed(() => current.value?.tasks || [])
const task = computed(() => tasks.value.find(item => item.instanceId === selectedTask.value))
const testSummary = computed(() => {
  const tests = task.value?.report?.tests_status
  if (!tests) return []
  return [['FAIL_TO_PASS', '需修复的测试'], ['PASS_TO_PASS', '原有回归测试']].map(([key, title]) => ({
    title, passed: tests[key]?.success?.length || 0, failed: tests[key]?.failure?.length || 0,
  }))
})
const filteredCatalog = computed(() => catalog.value.filter(item => `${item.instanceId} ${item.problem}`.toLowerCase().includes(query.value.toLowerCase())).slice(0, 80))
const trace = computed(() => {
  const grouped = []
  for (const event of events.value.filter(event => !selectedTask.value || !event.instanceId || event.instanceId === selectedTask.value)) {
    const previous = grouped.at(-1)
    if (event.eventType === 'text' && previous?.eventType === 'text' && previous.instanceId === event.instanceId) previous.content += event.content
    else grouped.push({ ...event })
  }
  return grouped
})
const rate = computed(() => current.value?.total ? `${((current.value.resolved || 0) / current.value.total * 100).toFixed(1)}%` : '—')
const tokens = computed(() => (current.value?.inputTokens || 0) + (current.value?.outputTokens || 0))
const progress = computed(() => current.value?.total ? (current.value.completed || 0) / current.value.total * 100 : 0)
const labels = { pending: '等待', preparing: '准备环境', running: '执行中', grading: '评分中', resolved: '已通过', unresolved: '未通过', error: '环境 / 评分错误', completed: '已结束', failed: '运行失败', stopping: '正在停止', stopped: '已停止', interrupted: '已中断' }
const label = state => labels[state] || state
const count = value => Number(value || 0).toLocaleString('zh-CN')
const date = value => value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
const duration = value => value == null ? '—' : value < 60 ? `${Math.round(value)} 秒` : `${Math.floor(value / 60)} 分 ${Math.round(value % 60)} 秒`
const elapsed = computed(() => current.value?.createdAt && current.value?.finishedAt ? duration((new Date(current.value.finishedAt) - new Date(current.value.createdAt)) / 1000) : running.value ? '进行中' : '—')
const comparison = computed(() => history.value.filter(run => run.action === 'evaluate' && run.total > 0).slice(0, 8))
function eventTitle(event) {
  if (event.eventType === 'phase' || event.eventType === 'tool_start' || event.eventType === 'tool_result') {
    try {
      const detail = JSON.parse(event.content)
      if (event.eventType === 'phase') return `${detail.label} · ${detail.status === 'running' ? '开始' : '完成'}`
      return `${detail.name} · ${event.eventType === 'tool_start' ? '调用' : detail.status === 'failed' ? '失败' : '返回'}`
    } catch { /* Older or interrupted payloads retain their generic label. */ }
  }
  if (event.type === 'task') return event.phase || label(event.status) || '题目环境'
  if (event.type === 'selected') return '题目已载入'
  if (event.type === 'finished') return '本轮评测结束'
  if (event.type === 'fatal') return '运行遇到问题'
  return ({ model_start: '调用模型', model_end: '模型返回', tool_start: '调用工具', tool_result: '工具结果', text: '模型输出', usage: 'Token 用量', phase: '执行阶段', done: '模型做题结束' })[event.eventType] || '环境日志'
}
function eventContent(event) {
  if (event.type === 'selected') return `本次选择 ${event.tasks?.length || 0} 题`
  if (event.type === 'agent' && event.eventType === 'usage') return `输入 ${count(event.inputTokens)} / 输出 ${count(event.outputTokens)}`
  const value = event.content ?? event.error ?? event.report ?? ''
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}
async function refreshEnvironment() {
  try { environment.value = (await api.get('/evaluations/environment', { timeout: 35000 })).data }
  catch (e) { error.value = errorMessage(e) }
}
async function refreshHistory() {
  history.value = (await api.get('/evaluations')).data
}
async function selectRun(id) {
  const ticket = ++version
  clearTimeout(timer); events.value = []; selectedTask.value = ''; artifact.value = ''; tab.value = 'trace'
  try {
    const result = await api.get(`/evaluations/${id}`)
    if (dead || ticket !== version) return
    current.value = result.data
    await poll(ticket)
  } catch (e) { if (ticket === version) error.value = errorMessage(e) }
}
async function poll(ticket = version) {
  clearTimeout(timer)
  const id = current.value?.id
  if (!id || dead || ticket !== version) return
  try {
    const run = (await api.get(`/evaluations/${id}`)).data
    let after = events.value.at(-1)?.id || 0
    const incoming = []
    let batch
    do {
      batch = (await api.get(`/evaluations/${id}/events`, { params: { after } })).data
      incoming.push(...batch)
      if (batch.length) after = batch.at(-1).id
      if (dead || ticket !== version) return
    } while (batch.length === 200)
    if (dead || ticket !== version) return
    current.value = run
    const known = new Set(events.value.map(event => event.id))
    events.value.push(...incoming.filter(event => !known.has(event.id))); error.value = ''
    if (!selectedTask.value && run.tasks?.length) selectedTask.value = run.tasks[0].instanceId
    await nextTick()
    if (follow.value && traceElement.value) traceElement.value.scrollTop = traceElement.value.scrollHeight
    await refreshHistory()
    if (activeStates.includes(run.status)) timer = setTimeout(() => poll(ticket), 1600)
    else await refreshEnvironment()
  } catch (e) {
    if (dead || ticket !== version) return
    error.value = errorMessage(e)
    timer = setTimeout(() => poll(ticket), 4000)
  }
}
async function start(setup = false) {
  if (busy.value || anyActive.value) return
  busy.value = true; error.value = ''
  try {
    const response = await api.post(setup ? '/evaluations/setup' : '/evaluations', setup ? {} : { ...form.value, instanceIds: chosen.value })
    showNew.value = false
    await refreshHistory(); await selectRun(response.data.id)
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function stop() {
  busy.value = true
  try { await api.post(`/evaluations/${current.value.id}/stop`); await poll() }
  catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function openNew() {
  showNew.value = true
  if (catalog.value.length || catalogLoading.value) return
  catalogLoading.value = true
  try { catalog.value = (await api.get('/evaluations/catalog', { timeout: 160000 })).data }
  catch (e) { ElMessage.error(errorMessage(e)) }
  finally { catalogLoading.value = false }
}
function toggleChosen(id) {
  chosen.value = chosen.value.includes(id) ? chosen.value.filter(value => value !== id) : [...chosen.value, id]
}
async function showArtifact(kind) {
  const ticket = ++artifactVersion, instance = selectedTask.value, id = current.value.id
  tab.value = kind; artifact.value = ''; artifactLoading.value = true
  try {
    const response = await api.get(`/evaluations/${id}/artifacts`, { params: { instance, kind } })
    if (ticket === artifactVersion && selectedTask.value === instance && current.value.id === id) artifact.value = response.data.content
  } catch (e) { if (ticket === artifactVersion) artifact.value = errorMessage(e) }
  finally { if (ticket === artifactVersion) artifactLoading.value = false }
}
function chooseTask(id) { selectedTask.value = id; tab.value = 'trace'; artifact.value = ''; artifactVersion++ }
function downloadReport() {
  const url = URL.createObjectURL(new Blob([JSON.stringify(current.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = `swe-lite-${current.value.id}.json`; link.click(); URL.revokeObjectURL(url)
}
onMounted(async () => {
  await refreshEnvironment()
  try { await refreshHistory(); if (!dead && history.value.length) await selectRun(history.value.find(run => activeStates.includes(run.status))?.id || history.value.find(run => run.action === 'evaluate' && run.total > 0)?.id || history.value[0].id) }
  catch (e) { error.value = errorMessage(e) }
})
onBeforeUnmount(() => { dead = true; version++; clearTimeout(timer) })
</script>

<template>
  <section class="evaluation-view">
    <header class="eval-heading">
      <div><div class="eval-eyebrow">AGENT EVALUATION / SWE-BENCH LITE</div><h1>让每一次改进，都有据可循<span>.</span></h1><p>真实仓库、真实模型、官方测试。把运行过程与最终结果放在一起看。</p></div>
      <button class="eval-primary" :disabled="busy || anyActive || !environment?.configured || !environment?.dockerReady || !environment?.imageReady" @click="openNew"><Icon name="plus" :size="16" />新建评测</button>
    </header>

    <div class="eval-environment">
      <span class="eval-env-status" :class="{ ready: environment?.dockerReady && environment?.imageReady }"><i></i>{{ environment?.message || '检查评测环境…' }}</span>
      <span>{{ environment?.model || '模型待配置' }}</span><span v-if="environment && !environment.configured">请先在服务端配置模型密钥</span>
      <button v-if="environment?.dockerReady && !environment?.imageReady" :disabled="busy || anyActive" @click="start(true)">准备评分环境 <Icon name="arrow" :size="13" /></button>
      <button class="eval-refresh" @click="refreshEnvironment" aria-label="刷新环境状态"><Icon name="refresh" :size="15" /></button>
    </div>
    <div v-if="error || current?.error" class="eval-error" role="alert">{{ error || current.error }}</div>

    <div class="eval-metrics">
      <article><span>任务通过率</span><strong>{{ rate }}</strong><small>已通过 {{ current?.resolved || 0 }} / 选定 {{ current?.total || 0 }} 题</small></article>
      <article><span>评测进度</span><strong>{{ current?.completed || 0 }}<em> / {{ current?.total || 0 }}</em></strong><div class="eval-progress"><i :style="{ width: `${progress}%` }"></i></div></article>
      <article><span>模型 Token 用量</span><strong>{{ count(tokens) }}</strong><small>输入 {{ count(current?.inputTokens) }} · 输出 {{ count(current?.outputTokens) }}</small></article>
      <article><span>总耗时</span><strong class="eval-time">{{ elapsed }}</strong><small>环境 / 评分错误 {{ current?.errors || 0 }} 题</small></article>
    </div>

    <div class="eval-layout">
      <aside class="eval-history eval-panel">
        <div class="eval-panel-title"><h2>实验记录</h2><span>{{ history.length }}</span></div>
        <div v-if="!history.length" class="eval-empty">还没有评测记录。<br />环境就绪后，新建一次基线实验。</div>
        <button v-for="run in history" :key="run.id" class="eval-history-item" :class="{ selected: current?.id === run.id }" @click="selectRun(run.id)"><strong>{{ run.name }}</strong><span>{{ date(run.createdAt) }}</span><div><b :class="['eval-badge', run.status]">{{ label(run.status) }}</b><small v-if="run.total">{{ run.resolved }} / {{ run.total }} 通过</small></div></button>
      </aside>

      <div class="eval-experiment eval-panel">
        <div class="eval-panel-title"><div><h2>{{ current?.name || '开始你的第一次评测' }}</h2><p>{{ current?.model || 'SWE-bench Lite' }}<template v-if="current?.config?.maxSteps"> · 每题最多 {{ current.config.maxSteps }} 轮 · 官方评分器 3.0.15</template></p></div><div class="eval-actions"><button v-if="current" @click="downloadReport"><Icon name="file" :size="14" />导出报告</button><button v-if="running" :disabled="busy || current?.status === 'stopping'" @click="stop"><Icon name="stop" :size="14" />停止</button></div></div>
        <div v-if="!current" class="eval-welcome"><Icon name="chart" :size="36" /><h3>建立基线，再比较改进</h3><p>先选 1 道题确认流程，再用固定题目比较不同版本的表现。</p><small>部分题目结果用于内部实验，不代表完整榜单分数。</small></div>
        <div v-else class="eval-workspace">
          <div v-if="tasks.length" class="eval-task-list">
            <button v-for="item in tasks" :key="item.instanceId" :class="{ selected: selectedTask === item.instanceId }" @click="chooseTask(item.instanceId)"><span :class="['eval-task-dot', item.status]"></span><div><strong>{{ item.instanceId }}</strong><small>{{ label(item.status) }} · {{ duration(item.durationSeconds) }}</small></div><Icon name="chevron" :size="13" /></button>
          </div>
          <div class="eval-detail">
            <template v-if="task"><div class="eval-task-heading"><div><h3>{{ task.instanceId }}</h3><p>{{ task.repo }} · {{ task.baseCommit?.slice(0, 10) }}</p></div><b :class="['eval-badge', task.status]">{{ label(task.status) }}</b></div><details class="eval-problem"><summary>查看题目说明</summary><pre>{{ task.problem }}</pre></details><div v-if="task.error || task.agentError" class="eval-task-error">{{ task.error || task.agentError }}</div><div v-if="testSummary.length" class="eval-test-summary"><div v-for="group in testSummary" :key="group.title"><span>{{ group.title }}</span><strong>{{ group.passed }}<small> / {{ group.passed + group.failed }} 通过</small></strong><em v-if="group.failed">{{ group.failed }} 项未通过</em></div></div></template>
            <div class="eval-tabs"><button :class="{ selected: tab === 'trace' }" @click="tab = 'trace'">执行过程</button><template v-if="task"><button :class="{ selected: tab === 'patch' }" @click="showArtifact('patch')">代码补丁</button><button :class="{ selected: tab === 'tests' }" @click="showArtifact('tests')">测试输出</button><button :class="{ selected: tab === 'report' }" @click="showArtifact('report')">评分明细</button></template><button v-if="tab === 'trace'" class="eval-follow" @click="follow = !follow">{{ follow ? '跟随进度 ✓' : '跟随进度' }}</button></div>
            <div v-if="tab === 'trace'" ref="traceElement" class="eval-trace" aria-live="polite"><div v-if="!trace.length" class="eval-empty">等待执行记录…</div><details v-for="event in trace" :key="event.id" class="eval-event"><summary><span>{{ date(event.createdAt) }}</span><strong>{{ eventTitle(event) }}</strong><small v-if="event.eventType === 'text' || event.type === 'log'">{{ String(event.content).slice(0, 65) }}</small><Icon name="chevron" :size="12" /></summary><pre>{{ eventContent(event) }}</pre></details></div>
            <pre v-else class="eval-artifact">{{ artifactLoading ? '正在读取…' : artifact || '文件内容为空' }}</pre>
          </div>
        </div>
      </div>
    </div>

    <section v-if="comparison.length" class="eval-comparison eval-panel"><div class="eval-panel-title"><h2>实验结果对照</h2><span>仅相同题目、模型和预算下可直接比较</span></div><table><thead><tr><th>实验</th><th>题目</th><th>通过率</th><th>Token</th><th>环境错误</th></tr></thead><tbody><tr v-for="item in comparison" :key="item.id" @click="selectRun(item.id)"><td>{{ item.name }}<small>{{ item.model }}</small></td><td>{{ item.total }}</td><td>{{ (item.resolved / item.total * 100).toFixed(1) }}%</td><td>{{ count(item.inputTokens + item.outputTokens) }}</td><td>{{ item.errors }}</td></tr></tbody></table></section>
    <footer class="eval-footnote"><Icon name="shield" :size="13" />通过率按本次选定题数计算，环境错误与未完成题也保留在分母中。Token 来自模型响应；未配置价格时不估算费用。</footer>

    <el-dialog v-model="showNew" title="新建真实模型评测" width="min(720px, 94vw)" class="eval-dialog">
      <div class="eval-form"><label>实验名称<input v-model="form.name" maxlength="100" /></label><div class="eval-form-grid"><label>默认题数<input v-model.number="form.limit" type="number" min="1" max="300" /></label><label>每题最大轮数<input v-model.number="form.maxSteps" type="number" min="1" max="40" /></label><label>做题时限（秒）<input v-model.number="form.timeoutSeconds" type="number" min="60" max="7200" /></label><label>测试时限（秒）<input v-model.number="form.testTimeoutSeconds" type="number" min="60" max="7200" /></label></div><div class="eval-picker-title"><strong>指定题目（可选）</strong><button @click="chosen = []">清空选择 {{ chosen.length ? `(${chosen.length})` : '' }}</button></div><input v-model="query" placeholder="搜索题目 ID、仓库或问题内容" aria-label="搜索题目" /><p>不指定时使用题库顺序的前 {{ form.limit }} 题；指定后按选择列表运行。</p><div class="eval-picker"><div v-if="catalogLoading" class="eval-empty">正在加载官方题库…</div><button v-for="item in filteredCatalog" :key="item.instanceId" :class="{ selected: chosen.includes(item.instanceId) }" @click="toggleChosen(item.instanceId)"><Icon :name="chosen.includes(item.instanceId) ? 'check' : 'circle'" :size="15" /><span>{{ item.instanceId }}</span><small>{{ item.repo }}</small></button></div><p>使用服务端配置的 {{ environment?.model }} 真实调用模型；首次运行某题需下载对应环境。</p></div>
      <template #footer><el-button @click="showNew = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!form.name.trim() || anyActive" @click="start(false)">开始评测 · {{ chosen.length || form.limit }} 题</el-button></template>
    </el-dialog>
  </section>
</template>
