import test from 'node:test';
import assert from 'node:assert/strict';
import { acceptSsoCallback, beginSsoLogin, buildSsoAuthorizeUrl } from '../src/utils/ssoState.mjs';

function storage() {
  const entries = new Map();
  return {
    entries,
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key)
  };
}

let seed = 0;
const cryptoProvider = {
  getRandomValues(bytes) {
    bytes.forEach((_, index) => { bytes[index] = (seed + index) & 255; });
    seed += 1;
    return bytes;
  }
};

test('authorization state is random, URL encoded and binds a path containing ampersands', () => {
  const saved = storage();
  const path = '/ads?medium=water&indicator=pH&value=a+b';
  const state = beginSsoLogin(saved, path, cryptoProvider, 1000);
  const url = new URL(buildSsoAuthorizeUrl('https://idp.example/', 'client+&', 'https://app.example/', state));
  assert.match(state, /^[0-9a-f]{64}$/);
  assert.equal(url.searchParams.get('state'), state);
  assert.equal(url.searchParams.get('client_id'), 'client+&');
  assert.equal(url.searchParams.get('redirect_uri'), 'https://app.example/sso');
  assert.ok(!url.href.includes(path), 'return path stays in sessionStorage, not the provider URL');
  const nextState = beginSsoLogin(saved, path, cryptoProvider, 1001);
  assert.notEqual(nextState, state);
  assert.deepEqual(acceptSsoCallback(saved, { state: nextState, code: 'a+b&c' }, false, 1002),
    { code: 'a+b&c', returnPath: path });
  assert.equal(saved.entries.size, 0, 'the authorization ticket is consumed before exchange');
  assert.equal(acceptSsoCallback(saved, { state: nextState, code: 'replay' }, false, 1003), null);
});

test('missing, mismatched and stale state never authorize code exchange', () => {
  const saved = storage();
  assert.equal(acceptSsoCallback(saved, { state: 'missing', code: 'code' }, false), null);
  const state = beginSsoLogin(saved, '/index', cryptoProvider, 1000);
  assert.equal(acceptSsoCallback(saved, { state: `${state}x`, code: 'code' }, false, 1001), null);
  assert.equal(acceptSsoCallback(saved, { state, code: 'code' }, false, 1002), null);
  const expired = beginSsoLogin(saved, '/index', cryptoProvider, 1000);
  assert.equal(acceptSsoCallback(saved, { state: expired, code: 'code' }, false, 601001), null);
  assert.equal(saved.entries.size, 0);
});

test('logged-in cross-account callback and provider rejection consume state without code exchange', () => {
  const saved = storage();
  const loggedInState = beginSsoLogin(saved, '/index', cryptoProvider, 1000);
  assert.equal(acceptSsoCallback(saved, { state: loggedInState, code: 'other-account' }, true, 1001), null);
  assert.equal(acceptSsoCallback(saved, { state: loggedInState, code: 'other-account' }, false, 1002), null);
  const denied = beginSsoLogin(saved, '/index', cryptoProvider, 1003);
  assert.equal(acceptSsoCallback(saved, { state: denied, error: 'access_denied' }, false, 1004), null);
  const missingCode = beginSsoLogin(saved, '/index', cryptoProvider, 1005);
  assert.equal(acceptSsoCallback(saved, { state: missingCode }, false, 1006), null);
  assert.equal(saved.entries.size, 0);
});

test('external return paths are rejected before creating an authorization ticket', () => {
  const saved = storage();
  assert.throws(() => beginSsoLogin(saved, '//evil.example/path', cryptoProvider), /Invalid/);
  assert.equal(saved.entries.size, 0);
});

test('starting a new SSO exchange removes a legacy session cache entry containing an old code', () => {
  const saved = storage();
  saved.setItem('sessionObj', JSON.stringify({ url: '/oauth2/codeLogin?code=old-code' }));
  beginSsoLogin(saved, '/index', cryptoProvider, 1000);
  assert.equal(saved.getItem('sessionObj'), null);
  assert.equal(saved.entries.size, 1, 'only the new one-time state remains');
});
