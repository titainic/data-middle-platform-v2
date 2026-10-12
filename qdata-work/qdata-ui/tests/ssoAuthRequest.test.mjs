import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('authorization codes with plus and ampersand stay in the form body and out of sessionStorage', () => {
  const requestSource = readFileSync(new URL('../src/utils/request.js', import.meta.url), 'utf8');
  const requestBody = requestSource.match(/service\.interceptors\.request\.use\(config => \{([\s\S]*?)\n\}, error => \{/)?.[1];
  assert.ok(requestBody, 'the real request interceptor must be found');
  const persisted = [];
  const interceptor = new Function('getToken', 'getStoredLang', 'cache', 'axios', 'i18n', 'buildEnv', 'tansParams', 'cancelTokens',
    `return config => { ${requestBody.replaceAll('import.meta.env', 'buildEnv')} };`)(
    () => null,
    () => 'zh-CN',
    { session: { getJSON: () => null, setJSON: (key, value) => persisted.push({ key, value }) } },
    { CancelToken: { source: () => ({ token: 'test-cancel' }) } },
    {},
    { VITE_APP_ENV: 'test', VITE_APP_OPEN_PROXY_PUT_DELETE_REQUEST: 'false' },
    () => '',
    []
  );
  const apiSource = readFileSync(new URL('../src/api/system/sso-auth.js', import.meta.url), 'utf8')
    .replace(/^import request from .*\n/m, '').replaceAll('export function', 'function');
  const codeLogin = new Function('request', `${apiSource}\nreturn codeLogin;`)(interceptor);
  const config = codeLogin('part+one&part=two');

  assert.equal(config.url, '/oauth2/codeLogin');
  assert.equal(config.method, 'post');
  assert.equal(config.headers['Content-Type'], 'application/x-www-form-urlencoded');
  assert.equal(config.headers.repeatSubmit, false);
  assert.equal(config.headers.isToken, false);
  assert.equal(config.data, 'code=part%2Bone%26part%3Dtwo');
  assert.equal(new URLSearchParams(config.data).get('code'), 'part+one&part=two');
  assert.deepEqual(persisted, [], 'request interceptor must not store this code');
});
