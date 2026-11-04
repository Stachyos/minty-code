import test from 'node:test'
import assert from 'node:assert/strict'
import { chapterModules } from './chapterModules.js'
test('all 17 chapters stay visible with honest integration states',()=>{
 const result=chapterModules([],null)
 assert.equal(result.length,17)
 for(const id of ['s06','s09','s11','s12','s13','s14','s16','s17']) assert.equal(result.find(m=>m.id===id).status,'unavailable')
 assert.equal(result.find(m=>m.id==='s03').status,'partial')
})
test('tool and compression states use only matching observed entries',()=>{
 const entries=[{id:'t1',name:'task_create',status:'running'},{id:'p1',phase:'compact',status:'completed'}]
 const result=chapterModules([{entries}], 'running')
 assert.equal(result.find(m=>m.id==='s10').status,'running')
 assert.equal(result.find(m=>m.id==='s08').status,'completed')
 assert.equal(result.find(m=>m.id==='s07').status,'idle')
 assert.equal(result.find(m=>m.id==='s13').entries.length,0)
})
