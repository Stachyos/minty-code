const specs = [
 ['s01','Agent 循环','available','已接入模型、工具、结果回填与有预算的继续执行。'],
 ['s02','工具分发','available','已接入文件浏览、代码搜索、按行读取、多文件补丁、差异和隔离测试，以及任务与技能工具。'],
 ['s03','权限控制','partial','已接入路径/隐藏文件/链接检查、Shell 开关与测试容器隔离；完整审批和通用运行隔离仍待实现。'],
 ['s04','生命周期 Hooks','available','已接入 input、before_tool、after_tool、stop 扩展点和实际执行事件；Hook 异常会阻止对应操作。'],
 ['s05','待办计划','available','已接入 TodoWrite，可记录、更新模型生成的待办计划。'],
 ['s06','子代理','unavailable','底层保留子代理代码；当前工作台和评测没有开放子代理工具。'],
 ['s07','技能加载','available','已接入工作区 skills 目录的按名称加载；没有配置技能时不会虚构内容。'],
 ['s08','上下文压缩','available','已接入旧工具结果清理及阈值/手动摘要压缩；记录只显示实际触发的操作。'],
 ['s09','长期记忆','unavailable','跨会话知识检索与长期记忆尚未接入；聊天保存不等于长期记忆。'],
 ['s10','任务系统','partial','创建、查看、更新和列出任务已接入；自动认领和跨运行调度尚未开放。'],
 ['s11','后台任务','unavailable','面向模型的异步命令及完成通知尚未开放。'],
 ['s12','定时调度','unavailable','Cron 定时任务尚未接入。'],
 ['s13','团队协作','unavailable','持久队友、消息通信、并行修改和冲突整合尚未接入。'],
 ['s14','MCP 工具','unavailable','外部 MCP 服务发现与工具调用尚未接入。'],
 ['s15','Harness 集成','partial','第一阶段单 Agent 闭环已接入：共享工具、生命周期、验证、修改证据和固定评测；多 Agent 等完整扩展仍未接入。'],
 ['s16','工作流编排','unavailable','DAG 编排、并行执行与检查点恢复尚未接入；流程图展示实际运行。'],
 ['s17','目标持续循环','partial','已接入修改/测试证据判断，测试失败可在剩余轮数内继续修复；跨运行自动续跑和通用目标验收仍未接入。'],
]
export function chapterModules(stages = [], runStatus, nodes = null) {
 const all = nodes || stages.flatMap(stage => stage.entries || [])
 return specs.map(([id,title,integration,description]) => {
  const entries = all.filter(entry => {
   if (['s01','s15'].includes(id)) return true
   if (id === 's02') return entry.kind === 'tool'
   if (id === 's03') return entry.kind === 'tool' || entry.kind === 'hook'
   if (id === 's04') return entry.kind === 'hook'
   if (id === 's05') return entry.name === 'TodoWrite'
   if (id === 's07') return entry.name === 'load_skill'
   if (id === 's08') return entry.phase === 'compact' || ['compress','compact'].includes(entry.name)
   if (id === 's10') return ['task_create','task_get','task_update','task_list'].includes(entry.name)
   if (id === 's17') return ['verification','completion_check'].includes(entry.kind)
   return false
  })
  const status = integration === 'unavailable' ? 'unavailable' : entries.some(entry => entry.status === 'running') ? 'running' : entries.at(-1)?.status || 'idle'
  return {id,title,integration,description,entries,status: id === 's01' ? runStatus || 'idle' : status}
 })
}
export const integrationLabel = value => ({available:'已接入',partial:'部分接入',unavailable:'未接入'})[value] || value
export const moduleStatus = s => ({idle:'未触发',running:'执行中',completed:'已执行',failed:'失败',stopped:'已停止',interrupted:'已中断',unavailable:'未接入',partial:'部分接入',visited:'已执行',current:'执行中'})[s] || s
