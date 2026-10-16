import test from 'node:test';
import assert from 'node:assert/strict';
import { harness, menuTree, turn, deferred } from './helpers/dppNavigationHarness.mjs';

function assertAtt(h, updates) {
  assert.equal(h.router.currentRoute.value.fullPath, '/att/rule/auditRule');
  assert.equal(h.topNav.activeMenu.value, '/att');
  assert.ok(h.permissionStore.sidebarRouters.every(menu => menu.parentPath === '/att'));
  assert.equal(h.updates.length, updates, 'stale response must not mutate any permission menu');
}
for (const mode of ['normal', 'slow-response', 'cached-microtask']) {
  for (let round = 1; round <= 3; round++) {
    test(`${mode} ${round}/3: project 6↔5, data development, then basic management wins`, async t => {
      const h = await harness(t);
      h.userStore.projectId = round % 2 ? 6 : 5;
      await h.settleMenus();
      h.userStore.projectId = round % 2 ? 5 : 6;
      const pending = h.projectIdChange();
      const request = h.menuRequests.at(-1);
      if (mode === 'cached-microtask') request.resolve({ code: 200, data: menuTree(String(request.id)) });
      const development = h.router.push('/dpp/etl/dataDev');
      // Real TopNav emits the user's selection before the route commits.
      h.topNav.handleSelect('/att', null, false);
      const basic = h.router.push('/att/rule/auditRule');
      await Promise.all([development, basic]); await turn();
      const updates = h.updates.length;
      if (mode === 'slow-response') await new Promise(resolve => setTimeout(resolve, 25));
      request.resolve({ code: 200, data: menuTree(String(request.id)) });
      await pending; await turn();
      assertAtt(h, updates);
      assert.deepEqual(h.errors, []);
    });
  }
}

test('staying in DPP accepts the latest project menus and keeps a permitted page/query', async t => {
  const h = await harness(t, { path: '/dpp/etl/dataDev?tab=sql' }); await h.settleMenus();
  h.userStore.projectId = 5;
  const pending = h.projectIdChange();
  h.menuRequests.at(-1).resolve({ code: 200, data: menuTree('5') });
  await pending; await turn();
  assert.equal(h.route.fullPath, '/dpp/etl/dataDev?tab=sql');
  assert.equal(h.topNav.activeMenu.value, '/dpp');
  assert.equal(h.permissionStore.sidebarRouters[0].meta.project, '5');
  assert.equal(h.closed.length, 0);
});

for (const nested of [false, true]) {
  for (const query of ['{"type":1}', '{broken', 'null', '[1,2]', '5', { type: '3' }]) {
    test(`fallback nested=${nested} query=${JSON.stringify(query)} always passes an object`, async t => {
      const h = await harness(t, { path: '/dpp/no-longer-allowed' });
      // Do not settle initialization: the newest manual request must take precedence.
      h.userStore.projectId = 5;
      const pending = h.projectIdChange();
      h.menuRequests.at(-1).resolve({ code: 200, data: menuTree('5', { nested, query }) });
      await pending; await turn();
      assert.equal(h.route.path, nested ? '/dpp/setting/dataSource' : '/dpp/dataSource');
      assert.equal(h.topNav.activeMenu.value, '/dpp');
      assert.ok(h.permissionStore.sidebarRouters.every(menu => menu.parentPath === '/dpp'));
      assert.deepEqual(h.route.query, query === '{"type":1}' ? { type: '1' } :
        typeof query === 'object' ? query : {});
      assert.ok(!h.route.fullPath.includes('?0='));
      assert.deepEqual(h.errors, []);
    });
  }
}

test('6→5→6 and same-project overlapping loads discard older responses', async t => {
  const h = await harness(t); await h.settleMenus();
  const requests = [];
  for (const id of [6, 5, 6]) {
    h.userStore.projectId = id;
    requests.push([h.projectIdChange(), h.menuRequests.at(-1)]);
  }
  const updates = h.updates.length;
  for (const [pending, request] of requests.slice(0, 2)) {
    request.resolve({ code: 200, data: menuTree('stale') }); await pending;
  }
  assert.equal(h.updates.length, updates);
  requests[2][1].resolve({ code: 200, data: menuTree('latest') }); await requests[2][0];
  assert.equal(h.updates.length, updates + 1);
});

test('leaving and reentering DPP invalidates the old navigation session', async t => {
  const h = await harness(t); await h.settleMenus();
  const pending = h.projectIdChange(), old = h.menuRequests.at(-1);
  await h.navigate('/att/rule/auditRule');
  await h.navigate('/dpp/etl/dataDev');
  const updates = h.updates.length;
  old.resolve({ code: 200, data: menuTree('stale') }); await pending;
  assert.equal(h.updates.length, updates);
  const latest = h.menuRequests.at(-1);
  assert.notEqual(latest, old, 'returning to DPP requests fresh menus');
  latest.resolve({ code: 200, data: menuTree('6') }); await turn();
  assert.equal(h.updates.length, updates + 1);
});

test('navigation during asynchronous tab cleanup cancels a planned fallback', async t => {
  const tabs = deferred();
  const h = await harness(t, { path: '/dpp/removed', closeTabs: () => tabs.promise });
  h.userStore.projectId = 5;
  const pending = h.projectIdChange();
  h.menuRequests.at(-1).resolve({ code: 200, data: menuTree('5') }); await turn();
  assert.equal(h.closed.length, 1);
  await h.navigate('/att/rule/auditRule');
  const updates = h.updates.length;
  tabs.resolve(); await pending; await turn();
  assertAtt(h, updates);
});

for (const initialPath of ['/dpp/dataSource', '/att/sourceSystem']) {
  test(`initial/options menu callback from ${initialPath} cannot rebuild menus after leaving`, async t => {
    const h = await harness(t, { path: initialPath });
    h.projectRequests[0].resolve({ code: 200, data: [{ id: 6, code: 'six' }] }); await turn();
    const request = h.menuRequests.at(-1);
    await h.navigate('/att/rule/auditRule'); const updates = h.updates.length;
    request.resolve({ code: 200, data: menuTree('late-initialization') }); await turn();
    assertAtt(h, updates);
  });
}

test('unmount invalidates outstanding project-menu responses', async t => {
  const h = await harness(t); await h.settleMenus();
  const pending = h.projectIdChange(), request = h.menuRequests.at(-1), updates = h.updates.length;
  h.unmount(); request.resolve({ code: 200, data: menuTree('unmounted') }); await pending;
  assert.equal(h.updates.length, updates);
});

test('empty menu and failed requests do not throw or erase the selected project', async t => {
  const h = await harness(t); await h.settleMenus();
  const empty = h.projectIdChange(); h.menuRequests.at(-1).resolve({ code: 200, data: [] }); await empty;
  assert.equal(h.route.path, '/dpp/dataSource');
  const failed = h.projectIdChange(); h.menuRequests.at(-1).reject(new Error('offline network failure')); await failed;
  assert.equal(h.userStore.projectId, 6);
  assert.equal(h.errors.length, 1);
});
