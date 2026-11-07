<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { api, errorMessage } from '../api'
import Icon from './Icon.vue'

const props = defineProps({ modelValue: String, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const open = ref(false)
const listing = ref(null)
const loading = ref(false)
const error = ref('')
let request

async function browse(path) {
  request?.abort()
  const controller = request = new AbortController()
  loading.value = true
  error.value = ''
  listing.value = null
  try {
    const { data } = await api.get('/projects/directories', { params: { path }, signal: controller.signal })
    if (!controller.signal.aborted) listing.value = data
  } catch (e) {
    if (!controller.signal.aborted) error.value = errorMessage(e)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}
function toggle() {
  open.value = !open.value
  if (open.value) void browse(props.modelValue || undefined)
  else request?.abort()
}
function choose() {
  if (!listing.value?.selectable) return
  emit('update:modelValue', listing.value.path)
  open.value = false
}
onBeforeUnmount(() => request?.abort())
</script>

<template>
  <div class="directory-picker">
    <div class="dialog-field-label">项目文件夹 <small>可选</small></div>
    <div class="directory-current">
      <span class="directory-icon"><Icon name="folder" :size="18" /></span>
      <div class="directory-summary">
        <strong>{{ modelValue ? '使用本地文件夹' : '使用独立工作区' }}</strong>
        <span :title="modelValue">{{ modelValue || '选择文件夹后，Agent 可直接修改其中的文件。' }}</span>
      </div>
      <el-button :disabled="disabled" :aria-expanded="open" @click="toggle">{{ open ? '收起' : modelValue ? '更换' : '选择文件夹' }}</el-button>
    </div>
    <section v-if="open" class="directory-browser" aria-label="浏览文件夹">
      <nav class="directory-nav" aria-label="文件夹位置">
        <button type="button" class="directory-home" @click="browse()">此电脑</button>
        <template v-if="listing?.path">
          <Icon name="chevron" :size="12" />
          <span :title="listing.path">{{ listing.path }}</span>
          <button type="button" class="icon-button directory-up" title="上一级" aria-label="上一级" @click="browse(listing.parent)">
            <Icon name="arrow" :size="15" />
          </button>
        </template>
      </nav>
      <div class="directory-list" :aria-busy="loading">
        <p v-if="loading" role="status"><span class="loading-dot"></span>正在读取文件夹…</p>
        <p v-else-if="error" class="text-error" role="alert">{{ error }}</p>
        <p v-else-if="!listing?.entries.length" role="status">这里没有子文件夹。</p>
        <button v-for="entry in listing?.entries" :key="entry.path" type="button" @click="browse(entry.path)">
          <Icon name="folder" :size="17" /><span>{{ entry.name }}</span><Icon name="chevron" :size="13" />
        </button>
      </div>
      <div class="directory-actions">
        <el-button text @click="toggle">取消选择</el-button>
        <el-button type="primary" :disabled="!listing?.selectable || disabled" @click="choose">使用此文件夹</el-button>
      </div>
    </section>
    <button v-if="modelValue && !open" type="button" class="text-button unlink-directory" :disabled="disabled"
      @click="emit('update:modelValue', '')">解除目录关联</button>
  </div>
</template>

<style scoped>
.directory-picker { display: grid; gap: 8px; }
.dialog-field-label small { margin-left: 4px; color: var(--muted); font-size: 11px; font-weight: 400; }
.directory-current { display: flex; align-items: center; gap: 10px; padding: 11px; border: 1px solid var(--mint-line); border-radius: 12px; background: var(--mint-soft); }
.directory-icon { display: grid; place-items: center; width: 34px; height: 34px; flex: none; border: 1px solid var(--mint-line); border-radius: 9px; background: #fff; color: var(--mint-deep); }
.directory-summary { display: grid; gap: 2px; flex: 1; min-width: 0; }
.directory-summary strong { font-size: 13px; font-weight: 600; color: var(--ink); }
.directory-summary span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); font-size: 12px; }
.directory-browser { overflow: hidden; border: 1px solid var(--line-2); border-radius: 11px; }
.directory-nav, .directory-actions { display: flex; align-items: center; gap: 6px; padding: 8px 10px; background: var(--surface-2); }
.directory-nav { min-height: 44px; border-bottom: 1px solid var(--line); }
.directory-nav > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.directory-home { flex: none; padding: 3px 5px; border-radius: 5px; font-weight: 600; color: var(--mint-deep); }
.directory-up { margin-left: auto; flex: none; }
.directory-list { min-height: 68px; max-height: min(28vh, 218px); overflow: auto; padding: 4px; }
.directory-list p { padding: 14px 8px; font-size: 12px; color: var(--ink-2); }
.directory-list p .loading-dot { margin-right: 6px; }
.directory-list button { display: flex; align-items: center; gap: 9px; width: 100%; padding: 7px 8px; border-radius: 7px; text-align: left; }
.directory-list button:hover, .directory-list button:focus-visible { background: var(--mint-soft); }
.directory-list button svg { color: var(--mint-deep); }
.directory-list button span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.directory-actions { justify-content: flex-end; border-top: 1px solid var(--line); }
.unlink-directory { justify-self: start; }
</style>
