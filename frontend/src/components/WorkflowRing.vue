<script setup>
import { computed, ref } from 'vue'
import { chapterModules, moduleStatus, integrationLabel } from '../chapterModules.js'
import { formatDetail, stateLabel } from '../workflow.js'
const props=defineProps({stages:Array,edges:Array,nodes:Array,currentStageId:String,selectedStageId:String,runStatus:String,rounds:Number,active:Boolean,disconnected:Boolean,follow:Boolean})
const emit=defineEmits(['select-stage'])
const modules=computed(()=>chapterModules(props.stages,props.runStatus,props.nodes))
const selected=ref(null)
const open=ref(false)
const detail=computed(()=>selected.value?.type==='module' ? modules.value.find(m=>m.id===selected.value.id) : props.stages.find(s=>s.id===selected.value?.id))
function select(id,type){selected.value={id,type};open.value=true;if(type==='stage')emit('select-stage',id)}
const positions={input:[100,45],context:[100,155],model:[400,155],decision:[700,155],tools:[700,295],feedback:[100,295],output:[700,45]}
const lines={'input-context':'M100 75V125','context-model':'M185 155H315','model-decision':'M485 155H615','decision-tools':'M700 185V265','tools-feedback':'M615 295H185','feedback-context':'M100 265V185','decision-output':'M700 125V75'}
const current=computed(()=>props.stages.find(s=>s.id===props.currentStageId))
</script>
<template>
 <div class="architecture">
  <div class="diagram-top"><span>主循环</span><span>{{ disconnected ? '连接中断 · 保留最近状态' : active ? '当前：'+(current?.title || '等待进度') : moduleStatus(runStatus || 'idle') }} · {{ rounds || 0 }} 轮</span></div>
  <svg class="main-loop" viewBox="0 0 800 345" role="group" aria-label="固定主循环流程图" preserveAspectRatio="xMidYMid meet">
   <defs><marker id="simple-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto"><path d="M1 1L9 5L1 9" fill="none" stroke="context-stroke" /></marker></defs>
   <path v-for="edge in edges" :key="edge.id" :d="lines[edge.id]" class="edge" :class="edge.status" marker-end="url(#simple-arrow)" />
   <text x="714" y="225" class="path-label">是</text><text x="714" y="105" class="path-label">否</text><text x="400" y="282" text-anchor="middle" class="path-label">工具结果 → 下一轮</text>
   <g v-for="stage in stages" :key="stage.id" role="button" tabindex="0" :aria-label="stage.title+'，'+moduleStatus(stage.status)" class="stage" :class="[stage.status,{selected:selected?.id===stage.id}]" @click="select(stage.id,'stage')" @keydown.enter="select(stage.id,'stage')" @keydown.space.prevent="select(stage.id,'stage')">
    <rect :x="positions[stage.id][0]-85" :y="positions[stage.id][1]-30" width="170" height="60" rx="8" />
    <text :x="positions[stage.id][0]" :y="positions[stage.id][1]-4" text-anchor="middle" class="stage-title">{{ stage.title }}</text>
    <text :x="positions[stage.id][0]" :y="positions[stage.id][1]+18" text-anchor="middle" class="stage-status">{{ moduleStatus(stage.status) }}</text>
   </g>
  </svg>
  <div class="modules">
   <div class="modules-heading"><span>功能部件 · S01—S17</span><span>接入状态 / 本次执行 · 点击查看证据</span></div>
   <div class="module-grid"><button v-for="m in modules" :key="m.id" class="module" :class="[m.status,m.integration]" @click="select(m.id,'module')"><span class="module-name"><small>{{m.id.toUpperCase()}}</small><b>{{m.title}}</b></span><em>{{integrationLabel(m.integration)}}<template v-if="m.integration !== 'unavailable'"> · {{moduleStatus(m.status)}}</template></em></button></div>
  </div>
 </div>
 <el-drawer v-model="open" :title="detail?.title || '执行详情'" size="min(480px, 94vw)" append-to-body>
  <div v-if="detail" class="module-detail"><p class="detail-status"><template v-if="detail.integration">{{integrationLabel(detail.integration)}} · </template>{{moduleStatus(detail.status)}}</p><p>{{detail.description}}</p><p v-if="!detail.entries.length">{{detail.integration==='unavailable' ? '当前尚未接入此部件，没有执行记录。' : detail.integration==='partial' ? '仅接入描述中的部分能力，本次没有匹配的执行记录。' : '本次任务没有触发此功能；不代表功能未接入。'}}</p>
   <section v-for="entry in [...detail.entries].reverse()" :key="entry.id"><h3>{{entry.title}} <small>{{stateLabel(entry.status)}}</small></h3><p v-if="entry.startedAt">{{new Date(entry.startedAt).toLocaleString()}}</p><h4 v-if="formatDetail(entry.input)">输入</h4><pre v-if="formatDetail(entry.input)">{{formatDetail(entry.input)}}</pre><h4 v-if="formatDetail(entry.output)">输出</h4><pre v-if="formatDetail(entry.output)">{{formatDetail(entry.output)}}</pre></section>
  </div>
 </el-drawer>
</template>
<style scoped>
.architecture{flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;background:var(--surface)}
.diagram-top{display:flex;justify-content:space-between;gap:12px;flex-shrink:0;padding:7px 16px 0;font-size:11px;color:var(--faint)}
.main-loop{flex:1;min-height:0;width:100%;display:block}
.edge{fill:none;stroke:#e3e6e4;stroke-width:1.4}
.edge.current{stroke:#5f9472;stroke-width:2}
.edge.visited{stroke:#bbc7be}
.stage{cursor:pointer;outline:none}
.stage rect{fill:#fff;stroke:#e0e4e2;stroke-width:1.2;transition:fill .15s,stroke .15s}
.stage:hover rect{stroke:#c9d4cd}
.stage.current rect,.stage.selected rect,.stage:focus-visible rect{fill:#f2f8f4;stroke:#5f9472;stroke-width:1.6}
.stage.failed rect{fill:#fdf7f4;stroke:#d9b4a6}
.stage-title{font-family:inherit;font-size:15px;fill:#232824}
.stage-status,.path-label{font-family:inherit;font-size:12px;fill:#9aa19b}
.stage.current .stage-status{fill:#4b7a5d}
.stage.failed .stage-status{fill:#a45a44}
.modules{flex-shrink:0;padding:8px 12px 10px;border-top:1px solid var(--line);background:var(--surface)}
.modules-heading{display:flex;justify-content:space-between;gap:10px;margin-bottom:7px;font-size:10.5px;color:var(--faint)}
.module-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:4px}
.module{display:flex;flex-direction:column;justify-content:center;gap:1px;min-width:0;height:36px;padding:3px 8px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--ink-2);text-align:left;transition:border-color .15s,background .15s,color .15s}
.module:hover,.module:focus-visible{border-color:var(--mint-line);background:var(--mint-soft);color:var(--ink);outline:none}
.module-name{display:flex;align-items:baseline;gap:4px;min-width:0;font-size:11.5px}
.module-name b{min-width:0;font-weight:400;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.module small{flex-shrink:0;font-size:9.5px;color:var(--faint)}
.module em{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex-shrink:0;font-size:9.5px;font-style:normal;color:var(--faint);line-height:1.2}
.module.running{border-color:var(--mint-line);background:var(--mint-soft)}
.module.running em{color:var(--mint-deep)}
.module.failed em{color:var(--danger)}
.module.visited em{color:var(--ink-2)}
.module-detail{font-size:12.5px;line-height:1.8;color:var(--ink-2)}
.module-detail>p{margin-bottom:10px}
.detail-status{font-weight:600;color:var(--ink)}
.module-detail section{padding:12px 0;border-top:1px solid var(--line)}
.module-detail h3{margin:0;font-size:13px;font-weight:600;color:var(--ink)}
.module-detail small{margin-left:6px;font-size:11px;font-weight:400;color:var(--muted)}
.module-detail h4{margin:10px 0 4px;font-size:11px;font-weight:500;color:var(--muted)}
.module-detail pre{margin:0;padding:10px;border-radius:8px;background:var(--surface-2);font:11.5px/1.7 Consolas,monospace;color:var(--ink-2);white-space:pre-wrap;overflow-wrap:anywhere}
.module-detail section>p{font-size:11px;color:var(--faint)}
@media(min-width:1500px){.module-grid{grid-template-columns:repeat(9,minmax(0,1fr))}}
@media(max-width:1080px){.module-grid{grid-template-columns:repeat(6,minmax(0,1fr))}}
@media(max-width:800px){.module-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.architecture{padding:0}.diagram-top,.modules-heading{font-size:10.5px}.module-name{font-size:11px}.module small{display:none}}
</style>
