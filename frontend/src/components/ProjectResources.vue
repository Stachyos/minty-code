<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import DialogHeading from './DialogHeading.vue'
import Icon from './Icon.vue'
import { api, errorMessage } from '../api'

const props = defineProps({ project: { type: Object, required: true }, preferredResource: String })
const emit = defineEmits(['close'])
const resources = ref([])
const tab = ref('document')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const formError = ref('')
const editor = ref(false)
const preview = ref(null)
const draft = ref({})
const base = `/projects/${props.project.id}`

const entries = computed(() => resources.value.filter(entry => entry.kind === tab.value))
const kinds = { document: '知识库', memory: '项目记忆', skill: '技能' }
const descriptions = {
  document: '保存项目文档和代码片段，Agent 回答时会检索相关内容并标出来源。',
  memory: '记录已确认的约定、决策和经验，同一项目的新会话也能查到。',
  skill: '保存可重复使用的操作流程。由你核对并启用后，项目里的 Agent 才能加载。'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    resources.value = (await api.get(`${base}/resource-list`)).data
    if (props.preferredResource) {
      const entry = resources.value.find(item => item.id === props.preferredResource)
      if (entry) {
        tab.value = entry.kind
        await read(entry)
      } else {
        error.value = '该资料已移除，可回到会话查看当时保存的引用片段。'
      }
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function read(entry, forEdit = false) {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    const { data } = await api.get(`${base}/resources/${entry.id}`)
    if (forEdit) edit(data)
    else preview.value = data
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

function edit(entry) {
  preview.value = null
  draft.value = entry ? { ...entry } : { kind: tab.value, title: '', source: '', content: '', verified: false }
  if (draft.value.kind === 'skill') draft.value.verified = false
  formError.value = ''
  editor.value = true
}

async function importText(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  formError.value = ''
  try {
    if (file.size > 96000) throw new Error('请选择 24000 字符以内的文本文件')
    const content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
    if (content.length > 24000 || content.includes('\0')) throw new Error('请选择 24000 字符以内的文本文件')
    draft.value.content = content
    draft.value.title ||= file.name.slice(0, 120)
    draft.value.source ||= file.name.slice(0, 300)
  } catch (e) {
    formError.value = e.message || '无法读取此文本文件'
  }
}

async function saveResource() {
  if (saving.value || !draft.value.title?.trim() || !draft.value.content?.trim()) return
  const { kind, title, source, content, verified } = draft.value
  const payload = { kind, title: title.trim(), source, content, verified }
  saving.value = true
  formError.value = ''
  try {
    const response = draft.value.id
      ? await api.put(`${base}/resources/${draft.value.id}`, payload)
      : await api.post(`${base}/resources`, payload)
    resources.value = [response.data, ...resources.value.filter(item => item.id !== response.data.id)]
    editor.value = false
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function remove(entry) {
  try {
    await ElMessageBox.confirm(`从项目移除「${entry.title}」？`, '移除资料', { type: 'warning', confirmButtonType: 'danger', confirmButtonText: '移除', cancelButtonText: '取消' })
  } catch {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await api.delete(`${base}/resources/${entry.id}`)
    resources.value = resources.value.filter(item => item.id !== entry.id)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-drawer :model-value="true" :title="`${project.name} · 项目资料`" size="min(560px, 94vw)"
    @update:model-value="emit('close')">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="loading" class="project-muted" role="status">正在加载项目资料…</p>
    <section v-else class="project-panel">
        <header class="project-tabs-header">
          <div class="project-tabs" aria-label="项目资料类型">
            <button v-for="(label, kind) in kinds" :key="kind" :aria-pressed="tab === kind" @click="tab = kind">{{ label }}</button>
          </div>
          <el-button size="small" type="primary" @click="edit()">添加{{ tab === 'document' ? '文档' : tab === 'memory' ? '记忆' : '技能' }}</el-button>
        </header>
        <p class="project-muted">{{ descriptions[tab] }}</p>
        <p v-if="!entries.length" class="project-muted">此分类暂无内容。</p>
        <div v-for="entry in entries" :key="entry.id" class="project-row">
          <button class="resource-title" :disabled="saving" @click="read(entry)"><Icon name="file" :size="16" /><strong>{{ entry.title }}</strong>
            <small v-if="entry.kind === 'skill'">{{ entry.verified ? '已确认启用' : '待确认' }}</small>
          </button>
          <div class="project-actions">
            <el-button size="small" text :disabled="saving" @click="read(entry, true)">编辑</el-button>
            <el-button size="small" text :disabled="saving" @click="remove(entry)">移除</el-button>
          </div>
        </div>
    </section>
    <el-dialog append-to-body align-center :model-value="!!preview" width="720px" @update:model-value="preview = null">
      <template #header="{ titleId }"><DialogHeading :id="titleId" icon="book" :title="preview?.title" :description="preview?.source" /></template>
      <pre class="project-preview">{{ preview?.content }}</pre>
    </el-dialog>
    <el-dialog append-to-body align-center v-model="editor" width="720px" :close-on-click-modal="!saving"
      :close-on-press-escape="!saving" :show-close="!saving">
      <template #header="{ titleId }"><DialogHeading :id="titleId" icon="book" :title="draft.id ? '编辑项目资料' : '添加项目资料'" :description="project.name" /></template>
      <form id="project-resource-form" class="project-editor" @submit.prevent="saveResource">
        <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
        <label>{{ draft.kind === 'skill' ? '技能名称（如 release-check）' : '标题' }}<el-input v-model="draft.title" maxlength="120" /></label>
        <label>{{ draft.kind === 'skill' ? '技能用途 / 验证依据' : '出处（可选）' }}<el-input v-model="draft.source" maxlength="300" /></label>
        <label v-if="draft.kind === 'document'" class="import-text">从文件导入<input type="file" accept=".txt,.md,.rst,.adoc,.py,.java,.js,.ts,.vue,.sql" @change="importText" /></label>
        <label>内容<el-input v-model="draft.content" type="textarea" :rows="9" maxlength="24000" show-word-limit /></label>
        <el-checkbox v-if="draft.kind === 'skill'" v-model="draft.verified">我已核对这个版本，允许项目 Agent 加载</el-checkbox>
      </form>
      <template #footer>
        <el-button :disabled="saving" @click="editor = false">取消</el-button>
        <el-button form="project-resource-form" type="primary" native-type="submit" :loading="saving" :disabled="!draft.title?.trim() || !draft.content?.trim()">保存</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.project-panel header, .project-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.project-actions, .resource-title { display: flex; align-items: center; gap: 8px; }
.project-actions :deep(.el-button + .el-button) { margin-left: 0; }
.project-panel .project-muted, .project-muted { margin: 9px 0; color: var(--muted); font-size: 13px; }
.project-row { padding: 10px 0; border-top: 1px solid var(--line); font-size: 13px; }
.project-row strong { overflow-wrap: anywhere; }
.project-row small { color: var(--muted); }
.project-tabs { display: flex; flex-wrap: wrap; gap: 4px; }
.project-tabs button { padding: 7px 10px; border-radius: 7px; color: var(--muted); font-weight: 600; }
.project-tabs button[aria-pressed="true"] { background: var(--mint-soft, #e6f3ec); color: var(--mint-deep); }
.resource-title { text-align: left; }
.project-editor { display: grid; gap: 12px; }
.project-editor label { display: grid; gap: 4px; }
.project-editor .import-text { display: flex; flex-wrap: wrap; }
.project-preview { margin: 0; padding: 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-2); white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 680px) {
  .project-row, .project-tabs-header { flex-wrap: wrap; }
}
</style>
