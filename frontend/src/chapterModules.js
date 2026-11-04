const specs = [
 ['s01','Agent 循环','input,context,model,decision,tools,feedback,output','主循环：模型请求工具，结果回填后继续；无需工具时返回。'],
 ['s02','工具分发','tools','按工具名分发文件、命令、任务与技能操作。'],
 ['s03','权限控制','','网页版有路径、隐藏文件和 Shell 开关防护；教程权限系统尚未完整接入。','partial'],
 ['s04','生命周期 Hooks','','教程的输入、工具前后、停止 Hooks 尚未接入。'],
 ['s05','待办计划','TodoWrite','记录和更新模型生成的待办计划。'],
 ['s06','子代理','','独立上下文的子任务循环；当前网页版未开放 task 工具。'],
 ['s07','技能加载','load_skill','按需读取技能内容。'],
 ['s08','上下文压缩','compact,compress','清理旧工具结果，必要时生成摘要；展示有事件的压缩操作。'],
 ['s09','长期记忆','','教程的跨会话记忆机制尚未接入；聊天记录保存不等于长期记忆。'],
 ['s10','任务系统','task_create,task_get,task_update,task_list','创建、查看和更新任务；教程的自动认领等扩展尚未开放。'],
 ['s11','后台任务','','异步命令及完成通知尚未开放。'],
 ['s12','定时调度','','Cron 定时任务尚未接入。'],
 ['s13','团队协作','','持久队友、消息、审批与工作区隔离尚未接入。'],
 ['s14','MCP 工具','','外部 MCP 服务发现与工具调用尚未接入。'],
 ['s15','Harness 集成','','网页版整合了基础循环、文件工具、任务、技能和压缩；完整教程 Harness 尚未接入。','partial'],
 ['s16','工作流编排','','教程的脚本编排、并行执行与检查点恢复尚未接入；此页为执行可视化。'],
 ['s17','目标持续循环','','独立目标判断器和自动继续执行尚未接入。'],
]
export function chapterModules(stages, runStatus) {
 const all = stages.flatMap(s => s.entries)
 return specs.map(([id,title,matches,description,mode]) => {
  let entries = id === 's01' ? all : all.filter(e => matches.split(',').includes(e.name) || (id === 's08' && e.phase === 'compact'))
  const supported = Boolean(matches)
  const latest = entries.at(-1)
  const status = !supported ? mode === 'partial' ? 'partial' : 'unavailable' : entries.some(e => e.status === 'running') ? 'running' : latest?.status || 'idle'
  return {id,title,description,entries,status: id === 's01' ? runStatus || 'idle' : status}
 })
}
export const moduleStatus = s => ({idle:'未触发',running:'执行中',completed:'已完成',failed:'失败',stopped:'已停止',interrupted:'已中断',unavailable:'未接入',partial:'部分接入',visited:'已执行',current:'执行中'})[s] || s
