import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { acceptSsoCallback, beginSsoLogin } from '../src/utils/ssoState.mjs';

const page = readFileSync(new URL('../src/views/sys/sso.vue', import.meta.url), 'utf8');
const routeBody = page.match(/beforeRouteEnter\(to, from, next\)\s*\{([\s\S]*?)\n\s*\},\n\s*methods:/)?.[1];
assert.ok(routeBody, 'the SSO route entry must be testable');

function route(query, authenticated = false) {
  const entries = new Map();
  const storage = {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key)
  };
  const crypto = { getRandomValues: bytes => bytes.fill(7) };
  const state = beginSsoLogin(storage, '/ads?medium=air&site=3', crypto, 1000);
  const beforeRouteEnter = new Function('acceptSsoCallback', 'sessionStorage', 'getToken',
    `return function(to, from, next) { ${routeBody} };`)(
    (saved, values, loggedIn) => acceptSsoCallback(saved, values, loggedIn, 1001),
    storage,
    () => authenticated ? 'existing-session' : null
  );
  let exchanges = 0;
  const view = { failed: false, code: null, fullPath: '', checkCode: () => { exchanges += 1; } };
  beforeRouteEnter({ query: { state, ...query } }, null, enter => enter(view));
  return { view, exchanges, pending: entries.size };
}

test('missing code and provider access_denied show failure without exchanging or logging out', () => {
  for (const query of [{}, { error: 'access_denied' }]) {
    const result = route(query);
    assert.equal(result.view.failed, true);
    assert.equal(result.exchanges, 0);
    assert.equal(result.pending, 0);
  }
  assert.ok(!page.includes('logOut('), 'SSO failure must not clear an existing local session');
});

test('a logged-in cross-account callback is rejected before code exchange', () => {
  const result = route({ code: 'other-account' }, true);
  assert.equal(result.view.failed, true);
  assert.equal(result.exchanges, 0);
  assert.equal(result.pending, 0);
});

test('a valid callback exchanges once and uses the saved return path', () => {
  const result = route({ code: 'valid-code' });
  assert.equal(result.view.failed, false);
  assert.equal(result.view.code, 'valid-code');
  assert.equal(result.view.fullPath, '/ads?medium=air&site=3');
  assert.equal(result.exchanges, 1);
  assert.equal(result.pending, 0);
});
