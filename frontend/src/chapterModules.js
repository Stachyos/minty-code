const specs = [
 ['s01','Agent 循环','available','已接入模型、工具、结果回填与有预算的继续执行。'],
 ['s02','工具分发','available','已接入文件浏览、代码搜索、按行读取、多文件补丁、差异和隔离测试，以及任务与技能工具。'],
 ['s03','权限控制','partial','文件工具限制在会话目录；工作台命令在断网容器中运行，配置限制内存、CPU 与进程数。操作审批和审计待补齐。'],
 ['s04','生命周期 Hooks','available','记录输入、工具前后、结束、暂停与恢复。恢复事件记录文件核对的结果。'],
 ['s05','待办计划','available','支持 TodoWrite 与持久化任务计划，可在恢复后继续更新。'],
 ['s06','子代理','unavailable','底层保留子代理代码；当前工作台和评测没有开放子代理工具。'],
 ['s07','技能加载','available','已接入工作区 skills 目录的按名称加载；没有配置技能时不会虚构内容。'],
 ['s08','上下文压缩','available','工作台保留原始目标、计划、约束和步骤证据，按相关性选取历史，并可回查工具原始记录。'],
 ['s09','长期记忆','unavailable','跨会话知识检索与长期记忆尚未接入；聊天保存不等于长期记忆。'],
 ['s10','任务系统','available','单任务持久保存目标、约束、计划、进度和预算，支持暂停和从检查点继续。'],
 ['s11','后台任务','available','工作台可启动、查询和停止隔离后台命令，浏览器关闭后服务仍执行并记录结果。'],
 ['s12','定时调度','unavailable','Cron 定时任务尚未接入。'],
 ['s13','团队协作','unavailable','持久队友、消息通信、并行修改和冲突整合尚未接入。'],
 ['s14','MCP 工具','unavailable','外部 MCP 服务发现与工具调用尚未接入。'],
 ['s15','Harness 集成','partial','工作台已有隔离命令、检查点、预算、进程清理和测试证据；统一的环境复现和审计待补齐。'],
 ['s16','工作流编排','unavailable','步骤依赖、条件分支和并行编排待接入。'],
 ['s17','目标持续循环','partial','支持在原预算内恢复同一任务并核对已完成操作；跨多次运行推进目标待接入。'],
]
export function chapterModules(stages = [], runStatus, nodes = null) {
 const all = nodes || stages.flatMap(stage => stage.entries || [])
 return specs.map(([id,title,integration,description]) => {
  const entries = all.filter(entry => {
   if (['s01','s15'].includes(id)) return true
   if (id === 's02') return entry.kind === 'tool'
   if (id === 's03') return entry.kind === 'tool' || entry.kind === 'hook'
   if (id === 's04') return entry.kind === 'hook'
   if (id === 's05') return ['TodoWrite','update_progress'].includes(entry.name)
   if (id === 's07') return entry.name === 'load_skill'
   if (id === 's08') return entry.phase === 'compact' || ['compress','compact'].includes(entry.name)
   if (id === 's10') return entry.kind === 'recovery' || ['task_create','task_get','task_update','task_list','update_progress','review_step'].includes(entry.name)
   if (id === 's11') return entry.kind === 'background' || ['start_background','background_status','stop_background'].includes(entry.name)
   if (id === 's17') return ['verification','completion_check','recovery'].includes(entry.kind)
   return false
  })
  const status = integration === 'unavailable' ? 'unavailable' : entries.some(entry => entry.status === 'running') ? 'running' : entries.at(-1)?.status || 'idle'
  return {id,title,integration,description,entries,status: id === 's01' ? runStatus || 'idle' : status}
 })
}
export const integrationLabel = value => ({available:'已接入',partial:'部分接入',unavailable:'未接入'})[value] || value
export const moduleStatus = s => ({idle:'未触发',running:'执行中',completed:'已执行',skipped:'已跳过',failed:'失败',stopped:'已停止',interrupted:'已中断',budget_exhausted:'预算耗尽',unavailable:'未接入',partial:'部分接入',visited:'已执行',current:'执行中'})[s] || s
