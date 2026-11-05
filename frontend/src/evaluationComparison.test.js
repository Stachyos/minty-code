import test from 'node:test'
import assert from 'node:assert/strict'
import { compareEvaluations } from './evaluationComparison.js'
test('comparison separates regressions and infrastructure failures from gains',()=>{
 const base={status:'completed',model:'same',config:{maxSteps:24},tasks:[{instanceId:'a',status:'resolved'},{instanceId:'b',status:'unresolved'},{instanceId:'c',status:'error'}]}
 const next={...base,tasks:[{instanceId:'a',status:'unresolved'},{instanceId:'b',status:'resolved'},{instanceId:'c',status:'unresolved'}]}
 const result=compareEvaluations(base,next)
 assert.equal(result.comparable,true);assert.equal(result.improved,1);assert.equal(result.regressed,1);assert.equal(result.infrastructure,1)
})
test('different tasks, models or budgets cannot claim a directly comparable improvement',()=>{
 const a={status:'completed',model:'one',tasks:[{instanceId:'a',status:'resolved'}],config:{maxSteps:24}}
 const b={...a,model:'two',tasks:[{instanceId:'b',status:'resolved'}],config:{maxSteps:40}}
 assert.equal(compareEvaluations(a,b).comparable,false)
 assert.equal(compareEvaluations(a,b).reasons.length,3)
})
