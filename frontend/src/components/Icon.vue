<script setup>
defineProps({ name: String, size: { type: [Number, String], default: 20 } })
const paths = {
  leaf: 'M12 21v-7M12 16C3 17 2 7 3 3c7-1 12 5 9 13Zm0 0C11 7 16 3 22 4c0 7-3 12-10 12Z',
  plus: 'M12 5v14M5 12h14',
  compose: 'M12 5H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7M10 14l4-1 7-7-3-3-7 7-1 4Z',
  chat: 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2V11.5a9.5 9.5 0 0 1 19 0ZM7 9h10M7 13h6',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  chart: 'M4 3v18h18M9 16v-5M14 16V7M19 16V4',
  workflow: 'M9 3h6v5H9zM3 16h6v5H3zM15 16h6v5h-6zM12 8v4M6 16v-4h12v4',
  settings: 'm9 3-1 3-3 1-2 3 2 2v3l2 2 3-1 2 2 3-2 3 1 2-3-1-3 2-2-2-3-3-1-1-3h-3ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  arrow: 'M12 19V5M6 11l6-6 6 6',
  stop: 'M6 6h12v12H6z',
  chevron: 'm9 5 7 7-7 7',
  folder: 'M3 7V4h6l3 3h9v13H3V7Z',
  code: 'm8 7-5 5 5 5m8-10 5 5-5 5m-3-13-2 16',
  terminal: 'm4 6 6 6-6 6m9 0h7',
  file: 'M14 2H4v20h16V8l-6-6Zm0 0v6h6M8 13h8M8 17h5',
  check: 'm5 12 4 4L19 6',
  circle: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  refresh: 'M20 8a8 8 0 0 0-14-3L3 8m0-5v5h5M4 16a8 8 0 0 0 14 3l3-3m0 5v-5h-5',
  close: 'm6 6 12 12M6 18 18 6',
  spark: 'm12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3 3-7Z',
  book: 'M12 5C9 2 4 3 2 4v16c3-2 7-2 10 0m0-15c3-3 8-2 10-1v16c-3-2-7-2-10 0V5Z',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 7v5l3 2',
  shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6l-9-4Zm-5 10 3 3 6-7',
  copy: 'M8 8h13v13H8zM16 8V3H3v13h5',
  menu: 'M4 6h16M4 12h16M4 18h16',
  sun: 'M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0Z',
  bolt: 'm13 2-9 12h7l-1 8 10-13h-8l1-7Z',
  layers: 'm12 3 10 5-10 5L2 8l10-5ZM2 12l10 5 10-5M2 16l10 5 10-5',
}
</script>
<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.circle" /></svg>
</template>
