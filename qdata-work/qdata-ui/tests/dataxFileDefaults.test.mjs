import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Execute the actual node factories without loading browser-only graph plugins.
const source = readFileSync(new URL('../src/views/dpp/utils/opBase.js', import.meta.url), 'utf8');
const factories = source.slice(source.indexOf('export const createDataNode ='), source.indexOf('let divMenuContainer ='))
  .replaceAll('export const ', 'const ')
  .replaceAll('import.meta.env.VITE_APP_BASE_API', "'/'");
const { createDataNode, getDefaultTaskParams } = new Function('cuPort', 'td', 'console', 'window',
  `${factories}; return { createDataNode, getDefaultTaskParams };`)(
  {}, key => key, { log() {} }, { location: { origin: 'http://test.invalid' } });

for (const componentType of [1, 3, 6, 7, 2, 4]) {
  test(`component ${componentType} initializes file artifacts only for Excel/CSV, on create and reset`, () => {
    for (const type of [componentType, String(componentType)]) {
      const data = { componentType: type, type: 1, taskParams: { type: 1 } };
      const node = createDataNode({ createNode: value => value }, data);
      const reset = getDefaultTaskParams(data);
      for (const params of [node.data.taskParams, reset]) {
        assert.equal(Object.hasOwn(params, 'csvFile'), [2, 4].includes(componentType));
        if ([2, 4].includes(componentType)) assert.equal(params.csvFile, '');
      }
    }
  });
}
