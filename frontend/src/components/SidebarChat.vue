<script setup>
defineProps({ chat: Object, selected: Boolean })
defineEmits(['open', 'delete'])
</script>

<template>
  <div class="chat-row" :class="{ selected }">
    <button class="session-item" :aria-current="selected ? 'page' : undefined" :title="chat.title" @click="$emit('open', chat)">
      <span>{{ chat.title || '新聊天' }}</span>
    </button>
    <el-dropdown class="sidebar-more" trigger="click" @command="$emit('delete', chat)">
      <button class="sidebar-more-button" :aria-label="`聊天「${chat.title || '新聊天'}」的操作`">⋯</button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="delete" class="sidebar-delete">删除聊天</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.chat-row { display: flex; align-items: center; border-radius: 8px; padding-right: 3px; }
.chat-row:hover, .chat-row:focus-within { background: var(--hover); }
.chat-row.selected { background: var(--active); color: var(--ink); font-weight: 500; }
.session-item { flex: 1; min-width: 0; padding-left: var(--chat-indent, 8px); background: transparent; }
</style>
