import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref } from 'vue';
import { parse, compileScript } from '@vue/compiler-sfc';
import { joinApiPath } from '../src/utils/apiUrl.js';

const filename = new URL('../src/views/sys/tool/swagger/index.vue', import.meta.url);
const { descriptor, errors } = parse(readFileSync(filename, 'utf8'));
assert.deepEqual(errors, []);
const script = compileScript(descriptor, { id: 'swagger-url-regression' }).content;

// 执行真实入口的编译脚本；仅替换浏览器开页和标签关闭，不发网络请求。
const createComponent = new Function('baseAPI', 'joinApiPath', 'ref', 'getCurrentInstance', 'window', 'iFrame',
  script.replace(/^import .*$/gm, '')
    .replaceAll('import.meta.env.VITE_APP_BASE_API', 'baseAPI')
    .replace('export default', 'return'));

const currentPage = 'http://10.131.135.95:8080/sys/tool/swagger';
const cases = [
  ['/', '/doc.html'],
  ['', '/doc.html'],
  [undefined, '/doc.html'],
  [null, '/doc.html'],
  ['/prod-api', '/prod-api/doc.html'],
  ['/prod-api/', '/prod-api/doc.html'],
  ['/gateway/api///', '/gateway/api/doc.html'],
  ['prod-api/', 'prod-api/doc.html'],
  ['https://api.example.test:8443/gateway/', 'https://api.example.test:8443/gateway/doc.html'],
  ['http://localhost:8080/', 'http://localhost:8080/doc.html'],
  ['//api.example.test/prod-api/', '//api.example.test/prod-api/doc.html'],
];

for (const [base, expected] of cases) {
  test(`系统接口基址 ${String(base)}：实际开页URL和解析主机正确，关闭原标签`, () => {
    const opened = [];
    let closed = 0;
    const component = createComponent(base, joinApiPath, ref,
      () => ({ proxy: { $tab: { closePage() { closed++; } } } }),
      { open(...args) { opened.push(args); } }, {});
    component.setup({}, { expose() {} });

    assert.deepEqual(opened, [[expected, '_blank']]);
    assert.equal(closed, 1);
    const actual = new URL(opened[0][0], currentPage);
    const expectedURL = new URL(expected, currentPage);
    assert.equal(actual.host, expectedURL.host);
    assert.equal(actual.origin, expectedURL.origin);
    assert.equal(actual.pathname, expectedURL.pathname);
    if (!base || base.startsWith('/') && !base.startsWith('//')) {
      assert.equal(actual.origin, new URL(currentPage).origin);
      assert.notEqual(actual.hostname, 'doc.html');
    }
  });
}

test('旧根基址直拼会被解析为外部doc.html主机', () => {
  const previous = new URL('/' + '/doc.html', currentPage);
  assert.equal(previous.hostname, 'doc.html');
  assert.notEqual(previous.origin, new URL(currentPage).origin);
});
