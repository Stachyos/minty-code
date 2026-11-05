export function compareEvaluations(baseline, candidate) {
 const reasons=[]
 if (!baseline || !candidate) return {comparable:false,reasons:['请选择基线和当前实验'],rows:[]}
 const a=new Map((baseline.tasks || []).map(task=>[task.instanceId,task]))
 const b=new Map((candidate.tasks || []).map(task=>[task.instanceId,task]))
 if (!a.size || a.size!==b.size || [...a.keys()].some(id=>!b.has(id))) reasons.push('题目集合不同')
 if (baseline.model!==candidate.model) reasons.push('模型不同')
 for(const key of ['maxSteps','timeoutSeconds','testTimeoutSeconds']) if(baseline.config?.[key]!==candidate.config?.[key]) reasons.push('执行预算不同')
 if(baseline.manifest?.datasetSha256 && candidate.manifest?.datasetSha256 && baseline.manifest.datasetSha256!==candidate.manifest.datasetSha256) reasons.push('题库版本不同')
 if(baseline.status!=='completed' || candidate.status!=='completed') reasons.push('实验尚未完整结束')
 const rows=[...new Set([...a.keys(),...b.keys()])].map(id=>{
  const before=a.get(id),after=b.get(id)
  const change=!before || !after ? 'missing' : ['error','stopped','pending'].includes(before.status) || ['error','stopped','pending'].includes(after.status) ? 'infrastructure' :
   before.status!=='resolved' && after.status==='resolved' ? 'improved' : before.status==='resolved' && after.status!=='resolved' ? 'regressed' : 'unchanged'
  return {id,before:before?.status || 'missing',after:after?.status || 'missing',change}
 })
 return {comparable:reasons.length===0,reasons:[...new Set(reasons)],rows,
  improved:rows.filter(row=>row.change==='improved').length,regressed:rows.filter(row=>row.change==='regressed').length,
  infrastructure:rows.filter(row=>row.change==='infrastructure').length,
  tokenDelta:(candidate.inputTokens||0)+(candidate.outputTokens||0)-(baseline.inputTokens||0)-(baseline.outputTokens||0)}
}
