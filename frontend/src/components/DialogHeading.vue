<script setup>
import Icon from './Icon.vue'

defineProps({ id: String, icon: String, title: String, description: String })
</script>

<template>
  <div class="dialog-heading">
    <span class="dialog-heading-icon"><Icon :name="icon" :size="19" /></span>
    <div>
      <h2 :id="id">{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </div>
  </div>
</template>
