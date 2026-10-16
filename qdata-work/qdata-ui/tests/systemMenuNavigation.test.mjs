import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { setImmediate } from 'node:timers/promises';
import { parse } from '@vue/compiler-sfc';
import { createMemoryHistory, createRouter } from 'vue-router';

const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');
const stub = { render: () => null };

// 保留实际路由记录，仅替换依赖浏览器的 Layout 和页面懒加载。
function routes(path) {
  const source = read(path)
    .replace(/import Layout from [^;\n]+;?/, 'const Layout = stub;')
    .replace(/\(\) => import\('[^']+'\)/g, 'stub')
    .replace('export default', 'return');
  return new Function('stub', source)(stub);
}

const publicRoutes = routes('router/system/public/index.js');
const detailRoutes = routes('router/system/dynamic/index.js');

// BIN-69/08-runtime-menu.json：目录1=sys，菜单100=user、101=role、105=dict。
// SysMenuServiceImpl#getRouteName 在未配置名称时将 path 首字母大写。
function addMenu(router, base = '/sys') {
  router.addRoute({ path: base, component: stub, children: ['user', 'role', 'dict'].map(path => ({
    path, name: path[0].toUpperCase() + path.slice(1), component: stub,
  })) });
}

function createMenuRouter(base = '/sys') {
  const router = createRouter({ history: createMemoryHistory(), routes: [...publicRoutes, ...detailRoutes] });
  addMenu(router, base);
  return router;
}

function pageFunction(path, name) {
  const { descriptor, errors } = parse(read(path));
  assert.deepEqual(errors, []);
  const script = descriptor.scriptSetup.content;
  const start = script.indexOf(`function ${name}(`);
  assert.ok(start >= 0, `${name} exists`);
  let depth = 1, end = script.indexOf('{', start) + 1;
  for (; depth; end++) {
    if (script[end] === '{') depth++;
    if (script[end] === '}') depth--;
  }
  return script.slice(start, end);
}

function tabFor(router, closed) {
  const script = read('plugins/tab.js')
    .replace(/^import .*$/gm, '')
    .replace('export default', 'return');
  return new Function('router', 'useTagsViewStore', script)(router, () => ({
    delView: route => closed.push(route.fullPath),
  }));
}

async function authRoleHarness(base = '/sys') {
  const router = createMenuRouter(base), closed = [], writes = [], messages = [], navigations = [];
  await router.push('/system/user-auth/role/5');
  const tab = tabFor(router, closed);
  const proxy = {
    $tab: { closeOpenPage: target => { const navigation = tab.closeOpenPage(target); navigations.push(navigation); return navigation; } },
    $modal: { msgSuccess: message => messages.push(message) },
  };
  let resolveSave;
  const saved = new Promise(resolve => { resolveSave = resolve; });
  const path = 'views/sys/system/user/authRole.vue';
  const actions = new Function('proxy', 'form', 'roleIds', 'updateAuthRole', 'td', `
    ${pageFunction(path, 'close')}
    ${pageFunction(path, 'submitForm')}
    return { close, submitForm };
  `)(proxy, { value: { userId: '5' } }, { value: ['5'] }, request => { writes.push(request); return saved; }, key => key);
  return { ...actions, router, closed, writes, messages, resolveSave, navigations };
}

test('复现根因：动态同名 User 移除静态 /system/user，旧路径匹配404', () => {
  const router = createRouter({ history: createMemoryHistory(), routes: publicRoutes });
  assert.equal(router.resolve('/system/user').name, 'User');
  addMenu(router);
  assert.equal(router.resolve({ name: 'User' }).path, '/sys/user');
  assert.equal(router.resolve('/system/user').matched.at(-1).path, '/:pathMatch(.*)*');
});

test('保存分支：成功前留在分配页，成功后关闭当前标签并到有效用户列表', async () => {
  const h = await authRoleHarness();
  h.submitForm();
  assert.deepEqual(h.writes, [{ userId: '5', roleIds: '5' }]);
  assert.equal(h.router.currentRoute.value.name, 'AuthRole');
  assert.deepEqual(h.closed, []);
  h.resolveSave({ code: 200 });
  await setImmediate();
  await Promise.all(h.navigations);
  assert.equal(h.router.currentRoute.value.path, '/sys/user');
  assert.equal(h.router.currentRoute.value.name, 'User');
  assert.deepEqual(h.closed, ['/system/user-auth/role/5']);
  assert.deepEqual(h.messages, ['sys.system.user.authSuccess']);
});

test('返回分支：不写角色，关闭分配标签并返回 /sys/user', async () => {
  const h = await authRoleHarness();
  h.close();
  await Promise.all(h.navigations);
  assert.deepEqual(h.writes, []);
  assert.deepEqual(h.messages, []);
  assert.deepEqual(h.closed, ['/system/user-auth/role/5']);
  assert.equal(h.router.currentRoute.value.path, '/sys/user');
  assert.equal(h.router.currentRoute.value.name, 'User');
});

test('按名称导航随已注册菜单路径变化，不依赖浏览器历史', async () => {
  const h = await authRoleHarness('/custom-system');
  h.close();
  await Promise.all(h.navigations);
  assert.equal(h.router.currentRoute.value.path, '/custom-system/user');
});

for (const [page, name, entry] of [
  ['role/authUser.vue', 'Role', '/system/role-auth/user/5'],
  ['dict/data.vue', 'Dict', '/system/dict-data/index/1'],
]) {
  test(`${name} 返回处理函数使用有效菜单，动态详情入口保持可用`, async () => {
    const router = createMenuRouter(), closed = [];
    await router.push(entry);
    const close = new Function('proxy', `${pageFunction(`views/sys/system/${page}`, 'handleClose')}; return handleClose;`)({
      $tab: tabFor(router, closed),
    });
    close();
    await setImmediate();
    assert.equal(router.currentRoute.value.name, name);
    assert.equal(router.currentRoute.value.path, `/sys/${name.toLowerCase()}`);
    assert.deepEqual(closed, [entry]);
    assert.equal(router.resolve(`/system/${name.toLowerCase()}`).matched.at(-1).path, '/:pathMatch(.*)*');
  });
}

test('三个详情页的 activeMenu 与运行菜单一致', () => {
  const router = createMenuRouter();
  for (const [entry, name] of [
    ['/system/user-auth/role/5', 'User'], ['/system/role-auth/user/5', 'Role'], ['/system/dict-data/index/1', 'Dict'],
  ]) {
    const activeMenu = router.resolve(entry).meta.activeMenu;
    assert.equal(activeMenu, router.resolve({ name }).path);
    assert.equal(router.resolve(activeMenu).name, name);
  }
});

test('项目成员提示入口通过 RouterLink 导向已注册 User 菜单', () => {
  const { descriptor, errors } = parse(read('views/dpp/setting/projectUserRel/user.vue'));
  assert.deepEqual(errors, []);
  const template = descriptor.template.content;
  const expression = template.match(/<router-link\s+:to="([^"]+)"/)[1];
  const target = new Function(`return (${expression});`)();
  assert.equal(createMenuRouter().resolve(target).path, '/sys/user');
});
