<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import Icon from './Icon.vue'
import SidebarChat from './SidebarChat.vue'

const props = defineProps({
  projects: Array, sessions: Array, selectedId: String, loading: Boolean, projectError: String,
  sessionsError: String
})
defineEmits(['create-project', 'create-chat', 'open-chat', 'open-resources', 'set-directory', 'delete-project', 'delete-chat', 'reload'])
const section = ref(null)
const projectsOpen = ref(true)
const projectLimit = ref(5)
const recentLimit = ref(12)
const collapsed = ref(new Set())
const allChats = ref(new Set())

const sortedSessions = computed(() => [...props.sessions].sort((a, b) =>
  String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || ''))))
const grouped = computed(() => {
  const groups = new Map()
  for (const session of sortedSessions.value) {
    if (!session.projectId) continue
    if (!groups.has(session.projectId)) groups.set(session.projectId, [])
    groups.get(session.projectId).push(session)
  }
  return groups
})
const recent = computed(() => sortedSessions.value.slice(0, recentLimit.value))
const visibleProjects = computed(() => {
  const first = props.projects.slice(0, projectLimit.value)
  const activeProject = props.sessions.find(item => item.id === props.selectedId)?.projectId
  const selected = props.projects.find(project => project.id === activeProject)
  return selected && !first.includes(selected) ? [...first, selected] : first
})

function toggle(set, id) {
  if (set.has(id)) set.delete(id)
  else set.add(id)
}
function projectChats(id) {
  const chats = grouped.value.get(id) || []
  if (allChats.value.has(id)) return chats
  const first = chats.slice(0, 3)
  const selected = chats.find(chat => chat.id === props.selectedId)
  return selected && !first.includes(selected) ? [...first, selected] : first
}
watch(() => props.selectedId, id => {
  const project = props.sessions.find(item => item.id === id)?.projectId
  if (project) {
    projectsOpen.value = true
    collapsed.value.delete(project)
  }
})

function focusProjects() {
  projectsOpen.value = true
  nextTick(() => {
    section.value?.scrollIntoView({ block: 'start' })
    section.value?.focus({ preventScroll: true })
  })
}
defineExpose({ focusProjects })
</script>

<template>
  <div class="project-sidebar">
    <section ref="section" tabindex="-1" aria-label="项目">
      <div class="section-heading">
        <button class="section-toggle" :aria-expanded="projectsOpen" @click="projectsOpen = !projectsOpen">
          项目 <Icon name="chevron" :size="13" :class="{ expanded: projectsOpen }" />
        </button>
        <button class="folder-action" title="新建项目" aria-label="新建项目" @click="$emit('create-project')"><Icon name="plus" :size="17" /></button>
      </div>
      <template v-if="projectsOpen">
        <p v-if="projectError" class="project-empty" role="status">{{ projectError }} <button class="text-button" @click="$emit('reload')">重试</button></p>
        <p v-else-if="!projects.length" class="project-empty">项目对话会显示在这里。</p>
        <section v-for="project in visibleProjects" :key="project.id" class="project-group" :aria-label="project.name">
          <div class="project-folder">
            <button class="folder-label" :aria-expanded="!collapsed.has(project.id)" :title="project.localDirectory || project.name" @click="toggle(collapsed, project.id)">
              <Icon name="folder" :size="17" /><span>{{ project.name }}</span>
            </button>
            <button class="folder-action" :title="'在 ' + project.name + ' 新建聊天'"
              :aria-label="'在 ' + project.name + ' 新建聊天'" @click="$emit('create-chat', project)">
              <Icon name="plus" :size="15" />
            </button>
            <button class="folder-action" :title="'查看 ' + project.name + ' 的资料'"
              :aria-label="'查看 ' + project.name + ' 的资料'" @click="$emit('open-resources', project)"><Icon name="book" :size="15" /></button>
            <el-dropdown class="sidebar-more" trigger="click" @command="action => $emit(action, project)">
              <button class="sidebar-more-button" :aria-label="`项目「${project.name}」的操作`">⋯</button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="set-directory">设置本地目录</el-dropdown-item>
                  <el-dropdown-item command="delete-project" divided class="sidebar-delete">删除项目</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div v-if="!collapsed.has(project.id)" class="project-chats">
            <button v-if="!grouped.get(project.id)?.length" class="list-more"
              @click="$emit('create-chat', project)">新建项目聊天</button>
            <SidebarChat v-for="chat in projectChats(project.id)" :key="chat.id" :chat="chat" :selected="selectedId === chat.id"
              @open="$emit('open-chat', $event)" @delete="$emit('delete-chat', $event)" />
            <button v-if="(grouped.get(project.id)?.length || 0) > 3" class="list-more" @click="toggle(allChats, project.id)">
              {{ allChats.has(project.id) ? '收起对话' : '更多对话' }}
            </button>
          </div>
        </section>
        <button v-if="projects.length > projectLimit" class="list-more" @click="projectLimit += 5">展开显示</button>
      </template>
    </section>
    <section aria-label="最近">
      <div class="section-heading recent-heading"><span>最近</span></div>
      <p v-if="loading && !sessions.length" class="project-empty" role="status">正在加载…</p>
      <p v-else-if="sessionsError" class="project-empty" role="status">对话列表加载失败 <button class="text-button" @click="$emit('reload')">重试</button></p>
      <p v-else-if="!sessions.length" class="project-empty">还没有对话。</p>
      <SidebarChat v-for="chat in recent" :key="chat.id" :chat="chat" :selected="selectedId === chat.id"
        @open="$emit('open-chat', $event)" @delete="$emit('delete-chat', $event)" />
      <button v-if="sessions.length > recentLimit" class="list-more" @click="recentLimit += 12">更多最近对话</button>
    </section>
  </div>
</template>

<style scoped>
.project-sidebar { flex: 1; min-height: 0; overflow: auto; padding: 5px 0 12px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 4px; padding: 14px 7px 7px; color: var(--muted); font-size: 13px; }
.section-toggle { display: flex; align-items: center; gap: 6px; }
.section-toggle .expanded { transform: rotate(90deg); }
.recent-heading { padding-top: 23px; }
.project-folder { display: flex; align-items: center; gap: 1px; border-radius: 8px; padding-right: 3px; }
.project-folder:hover, .project-folder:focus-within { background: var(--hover); }
.folder-label { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; padding: 7px 7px; text-align: left; font-size: 13.5px; }
.folder-label > svg { flex: none; color: var(--muted); }
.folder-label > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.folder-action { flex: none; display: grid; place-items: center; width: 25px; height: 26px; border-radius: 6px; color: var(--muted); }
.folder-action:hover, .folder-action:focus-visible { background: var(--active); color: var(--ink); }
.project-chats { margin-bottom: 4px; --chat-indent: 32px; }
.project-empty { margin: 6px 8px; font-size: 12px; color: var(--muted); line-height: 1.5; }
.list-more { padding: 6px 8px; font-size: 12px; text-align: left; color: var(--muted); }
.project-chats .list-more { padding-left: 32px; }
</style>
