import test from 'node:test';
import assert from 'node:assert/strict';
import { finishLogout, logoutAndClear } from '../src/store/system/logoutFlow.mjs';

function session() {
  return {
    token: 'local-session', id: 42, name: 'account', nickName: 'Account',
    phonenumber: 'not-a-real-phone', avatar: 'avatar', roles: ['user'],
    permissions: ['read'], projectId: 'project-1'
  };
}

test('failed local logout still removes the token and project state and navigates', async () => {
  const user = session();
  const serverFailure = new Error('logout unavailable');
  const removed = [];
  const calls = [];
  const localLogout = () => logoutAndClear(user, () => {
    calls.push('request');
    assert.equal(user.token, 'local-session');
    return Promise.reject(serverFailure);
  }, { removeItem: key => removed.push(key) }, () => calls.push('removeToken'));

  await assert.rejects(finishLogout(null, localLogout, () => calls.push('navigate')),
    error => error === serverFailure);
  assert.deepEqual(user, {
    token: '', id: '', name: '', nickName: '', phonenumber: '', avatar: '',
    roles: [], permissions: [], projectId: ''
  });
  assert.deepEqual(removed, ['qdataProjectId']);
  assert.deepEqual(calls, ['request', 'removeToken', 'navigate']);
});

test('failed SSO provider logout still performs local logout and navigates', async () => {
  const calls = [];
  const providerFailure = new Error('provider unavailable');
  await assert.rejects(finishLogout(
    () => { calls.push('provider'); return Promise.reject(providerFailure); },
    () => { calls.push('local'); },
    () => calls.push('navigate')),
  error => error === providerFailure);
  assert.deepEqual(calls, ['provider', 'local', 'navigate']);
});

test('successful SSO logout keeps provider, local and navigation order', async () => {
  const calls = [];
  await finishLogout(
    () => { calls.push('provider'); },
    () => { calls.push('local'); },
    () => calls.push('navigate'));
  assert.deepEqual(calls, ['provider', 'local', 'navigate']);
});
