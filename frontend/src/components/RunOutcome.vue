<script setup>
import { computed, ref } from 'vue'
import Icon from './Icon.vue'
const props = defineProps({ run: Object, events: { type: Array, default: () => [] } })
const panel = ref('')
const open = computed({ get: () => !!panel.value, set: value => { if (!value) panel.value = '' } })
function parse(value) { try { return typeof value === 'string' ? JSON.parse(value) : value } catch { return null } }
const verification = computed(() => parse(props.run?.verification) || parse(props.events.filter(event => event.type === 'verification').at(-1)?.content))
const changes = computed(() => parse(props.run?.changes) || verification.value?.diff || parse(props.events.filter(event => event.type === 'changes').at(-1)?.content))
const tests = computed(() => verification.value?.tests)
const label = computed(() => ({ verified: '测试已通过', validation_failed: '验证未通过', unverified: '修改尚未验证', no_changes: '本次未修改文件' })[verification.value?.outcome] || (['running','queued'].includes(props.run?.status) ? '正在处理' : '此记录未保存验证证据'))
const counts = computed(() => tests.value?.counts || {})
const testState = computed(() => ({ passed: '通过', failed: '失败', error: '测试环境或收集错误', unavailable: '测试环境不可用', timeout: '测试超时', no_tests: '没有发现测试' })[tests.value?.status] || '未运行测试')
</script>
<template>
  <section v-if="run" class="run-outcome" aria-label="修改与验证结果">
    <div class="outcome-top"><strong :class="verification?.outcome"><Icon :name="verification?.outcome === 'verified' ? 'check' : 'shield'" :size="14" />{{ label }}</strong><span>{{ changes?.totalFiles || 0 }} 个文件修改</span><span v-if="tests">测试：{{ testState }}<template v-if="counts.total"> · {{ counts.passed || 0 }}/{{ counts.total }}</template></span><div class="outcome-actions"><button @click="panel = 'diff'">查看修改</button><button @click="panel = 'tests'">验证详情</button></div></div>
    <p v-if="verification?.modifiedTests?.length">测试文件或测试配置发生变化，本次结果需要人工复核。</p><p v-else-if="tests && !verification.testsCurrent">测试期间或之后代码发生变化，需要重新验证。</p><p v-else-if="verification?.outcome === 'verified'">已运行测试通过；仍需结合原始要求检查验收范围。</p>
    <el-drawer v-model="open" :title="panel === 'diff' ? '本次文件修改' : '验证详情'" size="min(760px, 94vw)">
      <template v-if="panel === 'diff'"><p v-if="!changes?.totalFiles" class="outcome-empty">没有记录到文件修改。</p><article v-for="file in changes?.files || []" :key="file.path" class="outcome-file"><h3>{{ file.path }} <small>{{ ({created:'新增',deleted:'删除',modified:'修改'})[file.kind] || file.kind }}</small></h3><pre>{{ file.patch || '未保存文本差异' }}</pre><p v-if="file.truncated">此文件差异预览已截断。</p></article><p v-if="changes?.truncated">修改文件较多，此处仅展示部分。</p></template>
      <template v-else><p>{{ verification?.reason || '此运行没有保存独立验证结果，不能据此判断任务是否做对。' }}</p><p class="outcome-empty">{{ verification?.scope }}</p><template v-if="tests"><dl class="outcome-facts"><div><dt>测试工具</dt><dd>{{ tests.runner }}</dd></div><div><dt>结果</dt><dd>{{ testState }}</dd></div><div><dt>退出码</dt><dd>{{ tests.exitCode ?? '—' }}</dd></div><div><dt>耗时</dt><dd>{{ tests.durationSeconds }} 秒</dd></div><div><dt>通过 / 失败 / 跳过</dt><dd>{{ counts.passed || 0 }} / {{ counts.failed || 0 }} / {{ counts.skipped || 0 }}</dd></div></dl><pre class="outcome-test-output">{{ tests.output }}</pre></template></template>
    </el-drawer>
  </section>
</template>
<style scoped>
.run-outcome{margin:10px 24px 0;padding:12px 15px;border:1px solid var(--line);border-radius:9px;background:var(--surface-2);font-size:11px}.outcome-top{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.outcome-top strong{font-weight:500;display:flex;align-items:center;gap:6px;color:var(--muted)}.outcome-top strong.verified{color:var(--mint-deep)}.outcome-top strong.validation_failed{color:var(--danger)}.outcome-top strong.unverified{color:#9a7a4b}.outcome-top>span{color:var(--muted)}.outcome-actions{display:flex;gap:14px;margin-left:auto;color:var(--mint-deep)}.run-outcome>p{font-size:10px;color:var(--muted);margin-top:8px}.outcome-empty{color:var(--muted);font-size:12px;line-height:1.8;margin:12px 0}.outcome-file{margin-bottom:20px}.outcome-file h3{font-size:13px;font-weight:500;overflow-wrap:anywhere}.outcome-file small{color:var(--muted);font-size:10px;margin-left:8px}.outcome-file pre,.outcome-test-output{padding:14px;background:var(--surface-2);font:11px/1.8 Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;border:1px solid var(--line);border-radius:7px}.outcome-facts{font-size:12px}.outcome-facts>div{display:flex;padding:8px 0;border-bottom:1px solid var(--line)}.outcome-facts dt{color:var(--muted);width:150px}.outcome-facts dd{margin:0}@media(max-width:760px){.run-outcome{margin:8px 12px 0}.outcome-actions{margin-left:0}}
</style>
