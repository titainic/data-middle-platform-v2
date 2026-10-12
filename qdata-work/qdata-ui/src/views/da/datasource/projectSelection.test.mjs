import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeProjectSelection } from './projectSelection.mjs';
test('changing one page preserves assignments on other pages', () => {
  assert.deepEqual(mergeProjectSelection([{projectId:1},{projectId:2}], [{id:1}], []), [{projectId:2}]);
});
test('selection carries the actual code and name', () => {
  assert.deepEqual(mergeProjectSelection([], [{id:3}], [{id:3,code:'real-code',name:'Project'}]), [{projectId:3,projectCode:'real-code',projectName:'Project'}]);
});
test('empty search page does not drop assignments', () => {
  assert.deepEqual(mergeProjectSelection([{projectId:2}], [], []), [{projectId:2}]);
});
test('dialog changes do not mutate the saved selection', () => {
  const saved=[{projectId:1}];mergeProjectSelection(saved,[{id:1}],[]);assert.deepEqual(saved,[{projectId:1}]);
});
