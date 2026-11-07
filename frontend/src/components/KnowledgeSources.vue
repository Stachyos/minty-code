<script setup>
import { computed } from 'vue'
import { parseContent } from '../workflow'

const props = defineProps({ events: { type: Array, default: () => [] } })
defineEmits(['open-source'])
const sources = computed(() => {
  const matches = new Map()
  for (const event of props.events) {
    if (event.type !== 'knowledge') continue
    for (const source of parseContent(event.content).sources || []) matches.set(source.reference, source)
  }
  return [...matches.values()]
})
</script>

<template>
  <details v-if="sources.length" class="knowledge-sources">
    <summary>检索到的项目资料 · {{ sources.length }} 个片段</summary>
    <article v-for="source in sources" :key="source.reference">
      <button @click="$emit('open-source', source)">
        {{ source.title }} · 第 {{ source.startLine === source.endLine ? source.startLine : `${source.startLine}–${source.endLine}` }} 行
      </button>
      <small v-if="source.kind === 'workspace'">任务开始或恢复时的文件片段</small>
      <small v-else-if="source.source">{{ source.source }}</small>
      <pre>{{ source.excerpt }}</pre>
    </article>
  </details>
</template>

<style scoped>
.knowledge-sources { border: 1px solid var(--line); border-radius: 9px; padding: 10px 12px; margin: 12px 0; font-size: 13px; }
summary { cursor: pointer; color: var(--muted); }
article { border-top: 1px solid var(--line); margin-top: 8px; padding-top: 8px; }
button { color: var(--mint-deep); text-align: left; overflow-wrap: anywhere; }
small { display: block; margin-top: 4px; color: var(--muted); }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 150px; overflow: auto; font-size: 12px; }
</style>
