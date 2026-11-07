<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { formatDuration, parseContent, stateLabel } from '../workflow'

const props = defineProps({ run: Object, events: { type: Array, default: () => [] }, busy: Boolean })
defineEmits(['pause', 'resume'])
const now = ref(Date.now())
const open = ref(false)
let timer
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => clearInterval(timer))

const progress = computed(() => parseContent(props.run?.progress))
const elapsed = computed(() => {
  const start = props.run?.attemptStartedAt ? Date.parse(props.run.attemptStartedAt) : null
  return (props.run?.elapsedMillis || 0) + (start === null ? 0 : Math.max(0, now.value - start))
})
const completed = computed(() => (progress.value.plan || []).filter(item => item.status === 'completed').length)
const tokenUsed = computed(() => (props.run?.inputTokens || 0) + (props.run?.outputTokens || 0))
const jobs = computed(() => {
  const latest = new Map()
  for (const event of props.events) {
    if (event.type === 'recovery') {
      for (const job of latest.values()) if (job.status === 'running') job.status = 'stopped'
    }
    if (event.type !== 'background') continue
    const update = parseContent(event.content)
    if (update.jobId) latest.set(update.jobId, { ...latest.get(update.jobId), ...update })
  }
  if (props.run?.status !== 'running') {
    for (const job of latest.values()) {
      if (job.status === 'running') job.status = props.run?.cleanupPending ? 'cleanup_pending' : 'stopped'
    }
  }
  return [...latest.values()]
})
const recovery = computed(() => parseContent(props.events.filter(event => event.type === 'recovery').at(-1)?.content))
const number = value => Number(value || 0).toLocaleString('zh-CN')
</script>

<template>
  <section v-if="run?.maxRounds" class="task-progress-panel" aria-label="任务进度与预算">
    <div class="task-heading">
      <button class="task-toggle" :aria-expanded="open" @click="open = !open">任务进度与预算 {{ open ? '▴' : '▾' }}</button>
      <span v-if="progress.plan?.length">{{ completed }}/{{ progress.plan.length }} 步</span>
      <span>{{ run.roundsUsed || 0 }}/{{ run.maxRounds }} 轮</span>
      <span class="task-actions">
        <button v-if="run.status === 'running' && run.progress !== '{}'" :disabled="busy" @click="$emit('pause')">暂停</button>
        <button v-if="run.resumable" :disabled="busy" @click="$emit('resume')">继续任务</button>
      </span>
    </div>
    <p v-if="run.status === 'interrupted' || run.status === 'budget_exhausted'" class="task-notice">{{ run.error }}</p>
    <p v-if="run.cleanupPending" class="task-notice">本次容器尚待清理。请启动 Docker 后继续任务或重启后端。</p>
    <div v-if="open" class="task-body">
      <div><strong>原始目标</strong><p class="task-goal">{{ run.prompt }}</p></div>
      <div v-if="progress.constraints?.length"><strong>约束</strong><ul><li v-for="item in progress.constraints" :key="item">{{ item }}</li></ul></div>
      <div><strong>计划</strong>
        <ol v-if="progress.plan?.length"><li v-for="(item, i) in progress.plan" :key="i">{{ item.step }} · {{ item.status === 'completed' ? '已完成' : item.status === 'in_progress' ? '进行中' : '待处理' }}</li></ol>
        <p v-else>暂未记录计划。</p>
      </div>
      <div v-if="progress.openIssues?.length"><strong>待解决</strong><ul><li v-for="item in progress.openIssues" :key="item">{{ item }}</li></ul></div>
      <div v-if="progress.steps?.length"><strong>最近步骤</strong><ul><li v-for="step in progress.steps.slice(-5)" :key="step.id">#{{ step.id }} {{ step.tool }} · {{ step.status === 'skipped' ? '已跳过' : stateLabel(step.status) }} <span v-if="step.paths?.length">· {{ step.paths.join('、') }}</span></li></ul></div>
      <div v-if="recovery.workspaceFingerprint"><strong>恢复核对</strong><p>上次检查点之后有 {{ recovery.changedCount }} 个文件变动<span v-if="recovery.changedPaths?.length"> · {{ recovery.changedPaths.join('、') }}</span><span v-if="recovery.interruptedStep"> · 中断步骤 #{{ recovery.interruptedStep }}</span></p></div>
      <dl class="task-budget">
        <div><dt>时间</dt><dd>已用 {{ formatDuration(elapsed) }} · 剩余 {{ formatDuration(Math.max(0, run.timeLimitSeconds * 1000 - elapsed)) }}</dd></div>
        <div><dt>模型轮数</dt><dd>已用 {{ run.roundsUsed || 0 }} · 剩余 {{ Math.max(0, run.maxRounds - (run.roundsUsed || 0)) }}</dd></div>
        <div><dt>Token</dt><dd>已报告 {{ number(tokenUsed) }} · 剩余 {{ number(Math.max(0, run.maxTokens - tokenUsed)) }}</dd></div>
      </dl>
      <div><strong>执行环境</strong><p>文件工具仅能操作当前会话目录。命令在断网 Docker 容器里使用工作区副本，命令产生的文件留在容器内。</p></div>
      <div v-if="jobs.length"><strong>后台命令</strong>
        <ul><li v-for="job in jobs" :key="job.jobId">{{ job.command || job.jobId.slice(0, 8) }} · {{ job.status === 'cleanup_pending' ? '待清理' : stateLabel(job.status) }}<span v-if="job.exitCode != null"> · 退出码 {{ job.exitCode }}</span></li></ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.task-progress-panel { margin: 8px 24px 0; padding: 9px 14px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface-2); font-size: 12px; }
.task-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; color: var(--muted); }
.task-toggle { font-weight: 600; color: var(--ink); }
.task-actions { margin-left: auto; color: var(--mint-deep); }
.task-notice { margin: 6px 0 0; color: var(--muted); }
.task-body { display: grid; gap: 8px; max-height: 240px; overflow: auto; margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--line); line-height: 1.55; }
.task-body :is(p, ul, ol, dl) { margin: 2px 0; }
.task-body :is(ul, ol) { padding-left: 20px; }
.task-goal { white-space: pre-wrap; overflow-wrap: anywhere; }
.task-budget { display: flex; gap: 8px 18px; flex-wrap: wrap; }
.task-budget dt { font-weight: 600; }
.task-budget dd { margin: 0; color: var(--muted); }
@media (max-width: 760px) { .task-progress-panel { margin-inline: 12px; } }
</style>
