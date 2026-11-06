import test from 'node:test'
import assert from 'node:assert/strict'
import { chapterModules } from './chapterModules.js'
test('all 17 chapters stay visible with honest integration states',()=>{
 const result=chapterModules([],null)
 assert.equal(result.length,17)
 for(const id of ['s06','s09','s12','s13','s14','s16']) assert.equal(result.find(m=>m.id===id).integration,'unavailable')
 for(const id of ['s03','s15','s17']) assert.equal(result.find(m=>m.id===id).integration,'partial')
 for(const id of ['s01','s02','s04','s05','s07','s08','s10','s11']) assert.equal(result.find(m=>m.id===id).integration,'available')
 assert.equal(result.find(m=>m.id==='s02').status,'idle')
})
test('integration and observed execution remain separate, including tools, hooks and verification',()=>{
 const nodes=[{id:'tool',kind:'tool',name:'search_text',status:'completed'},
  {id:'hook',kind:'hook',name:'before_tool',status:'completed'},
  {id:'v',kind:'verification',status:'failed'}]
 const result=chapterModules([], 'completed', nodes)
 assert.equal(result.find(m=>m.id==='s02').status,'completed')
 assert.equal(result.find(m=>m.id==='s04').entries.length,1)
 assert.equal(result.find(m=>m.id==='s17').status,'failed')
 assert.equal(result.find(m=>m.id==='s17').integration,'partial')
 assert.equal(result.find(m=>m.id==='s07').status,'idle')
})
test('tool and compression states use only matching observed entries',()=>{
 const entries=[{id:'t1',name:'task_create',status:'running'},{id:'p1',phase:'compact',status:'completed'}]
 const result=chapterModules([{entries}], 'running')
 assert.equal(result.find(m=>m.id==='s10').status,'running')
 assert.equal(result.find(m=>m.id==='s08').status,'completed')
 assert.equal(result.find(m=>m.id==='s07').status,'idle')
 assert.equal(result.find(m=>m.id==='s13').entries.length,0)
})
