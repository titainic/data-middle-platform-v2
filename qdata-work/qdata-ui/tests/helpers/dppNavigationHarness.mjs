import { readFileSync } from 'node:fs';
import * as vue from 'vue';
import { parse as parseModule } from '@babel/parser';
import { parse as parseSfc } from '@vue/compiler-sfc';
import { createMemoryHistory, createRouter } from 'vue-router';

export const read = path => readFileSync(new URL(`../../src/${path}`, import.meta.url), 'utf8');
export const turn = () => new Promise(resolve => setImmediate(resolve));
export const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const vueDeps = Object.fromEntries(Object.entries(vue).filter(([key]) => key !== 'version' && /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(key)));
function evaluate(source, dependencies, result) {
  const ast = parseModule(source, { sourceType: 'module' });
  for (const node of [...ast.program.body].reverse()) {
    if (node.type === 'ImportDeclaration') source = source.slice(0, node.start) + source.slice(node.end);
  }
  source = source.replaceAll('import.meta.env', 'runtimeEnv')
    .replace(/import\.meta\.glob\([^)]*\)/g, '{}').replace('export default function', 'function').replace(/export default \w+;?/, '').replace(/\bexport /g, '');
  return new Function(...Object.keys(dependencies), `${source}\nreturn ${result};`)(...Object.values(dependencies));
}
const renderer = vue.createRenderer({
  createComment: () => ({ closest: () => null }), createText: () => ({}), createElement: () => ({ closest: () => null }),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
});
export function menuTree(project = '6', { query = '{"type":1}', nested = false } = {}) {
  return [
    { path: '/att', name: 'Att', meta: { title: '基础管理' }, children: [
      { path: 'rule/auditRule', name: 'AuditRule', meta: { title: '稽查规则' } },
      { path: 'sourceSystem', name: 'SourceSystem', meta: { title: '来源系统' } },
    ] },
    { path: '/dpp', name: 'Dpp', meta: { title: '数据研发' }, children: [
      nested ? { path: 'setting', name: 'Setting', children: [
        { path: 'dataSource', name: 'DataSource', query, meta: { project } },
      ] } : { path: 'dataSource', name: 'DataSource', query, meta: { project } },
      { path: 'etl', name: 'Etl', children: [
        { path: 'dataDev', name: 'DataDev', meta: { project } },
      ] },
    ] },
  ];
}

// Executes production Navbar, TopNav, permission actions, and useProjectOptions.
// HTTP, storage, page renderer, and unrelated host services are offline substitutes.
export async function harness(t, { path = '/dpp/dataSource', initialMenus = menuTree(),
  closeTabs, readSource = read, projects = [{ id: 5, code: 'five' }, { id: 6, code: 'six' }] } = {}) {
  const menuRequests = [], projectRequests = [], updates = [], errors = [], closed = [];
  const storage = new Map([['qdataProjectId', '6']]);
  const localStorage = { getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)), removeItem: key => storage.delete(key) };
  const userStore = vue.reactive({ id: 1, projectId: 6, projectCode: 'six' });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/:pathMatch(.*)*', component: { render: () => null } },
  ] });
  router.beforeEach(to => to.path.startsWith('/redirect/') ?
    { path: to.path.slice('/redirect'.length), query: to.query } : undefined);
  await router.push(path);
  const route = vue.reactive({});
  const syncRoute = () => Object.assign(route, router.currentRoute.value);
  syncRoute(); router.afterEach(syncRoute);
  const appStore = vue.reactive({ sidebar: { hide: false }, toggleSideBarHide(value) { this.sidebar.hide = value; } });
  const settingsStore = vue.reactive({ theme: '#fff', topNav: true });
  const bus = { on() {}, off() {} };
  const consoleStub = { log() {}, warn() {}, error: (...args) => errors.push(args.map(String).join(' ')) };
  const common = { ...vueDeps, localStorage, runtimeEnv: {}, console: consoleStub,
    useRoute: () => route, useRouter: () => router, useUserStore: () => userStore,
    useAppStore: () => appStore, useSettingsStore: () => settingsStore,
    defaultSettings: { navbarLogoRoutes: [] }, constantRoutes: [], dynamicRoutes: [],
    useDefaultLang: () => ({ td: key => key, locale: vue.ref('zh-CN') }),
    useLocale: () => ({ changeLocale() {} }),
    getRoutersDpp: id => { const request = { ...deferred(), id }; menuRequests.push(request); return request.promise; },
  };
  let permissionStore;
  evaluate(read('store/system/permission.js'), { ...common,
    defineStore: (name, definition) => {
      permissionStore = vue.reactive(definition.state());
      for (const [key, fn] of Object.entries(definition.actions)) permissionStore[key] = fn.bind(permissionStore);
      return () => permissionStore;
    }, Layout: {}, ParentView: {}, InnerLink: {},
  }, 'usePermissionStore');
  permissionStore.updateTopbarRoutes(initialMenus);
  const update = permissionStore.updateTopbarRoutes;
  permissionStore.updateTopbarRoutes = data => { updates.push(data); update(data); };
  common.usePermissionStore = () => permissionStore;
  const useProjectOptions = evaluate(readSource('composables/useProjectOptions.js'), { ...common, bus,
    PROJECT_OPTIONS_CHANGED: 'changed', currentUser: () => {
      const request = deferred(); projectRequests.push(request); return request.promise;
    },
  }, 'useProjectOptions');
  const scriptSource = path => parseSfc(readSource(path)).descriptor.scriptSetup.content;
  let navbar, topNav;
  const TopNav = {
    setup(_, { expose }) {
      topNav = evaluate(scriptSource('components/TopNav/index.vue'), { ...common,
        window: { removeEventListener() {} }, defineEmits: () => (name, value) => navbar?.getRouter(value),
        defineExpose: expose, isHttp: path => /^https?:/.test(path),
        useTagsViewStore: () => ({ visitedViews: [] }), onMounted() {},
      }, '{handleSelect, activeMenu, childrenMenus}');
      return () => { topNav.activeMenu.value; return null; };
    },
  };
  const app = renderer.createApp({
    setup() {
      navbar = evaluate(scriptSource('layout/components/Navbar.vue'), { ...common, useProjectOptions,
        useI18n: () => ({ t: key => key, locale: vue.ref('zh-CN') }),
        useLocaleStore: () => ({ getCurrentLocale: { lang: 'zh-CN' } }),
        useWindowSize: () => ({ width: vue.ref(1440) }), useTagsViewStore: () => ({ visitedViews: [] }),
        packageInfo: {}, defineEmits: () => () => {}, avatarFallback: '',
        window: { location: { protocol: 'http:', host: 'offline' } },
        WebSocket: class { close() {} }, getNum() {},
        listNotice: async () => ({ rows: [] }), listMessage: async () => ({ data: { rows: [] } }),
      }, '{getRouter, projectIdChange, refreshProjectOptions}');
      return () => vue.h(TopNav, { ref: 'topNavRef' });
    },
  });
  const tab = evaluate(read('plugins/tab.js').replace('export default {', 'const tab = {'), {
    router, useTagsViewStore: () => ({ delCachedView: async () => {}, delView: async () => {},
      delAllViews: async () => {} }),
  }, 'tab');
  app.config.globalProperties.$tab = { ...tab, closeAllPage() {
    closed.push(route.fullPath); return closeTabs ? closeTabs() : tab.closeAllPage();
  } };
  app.config.errorHandler = error => errors.push(error.message);
  app.mount({});
  let unmounted = false;
  const unmount = () => { if (!unmounted) { app.unmount(); unmounted = true; } };
  t.after(unmount);
  await turn();
  const navigate = async target => { await router.push(target); await turn(); };
  const settleMenus = async () => {
    projectRequests.forEach(request => request.resolve({ code: 200, data: projects }));
    await turn();
    for (const request of menuRequests) request.resolve({ code: 200, data: menuTree(String(request.id)) });
    await turn();
  };
  return { ...navbar, userStore, permissionStore, router, route, topNav, menuRequests, projectRequests,
    updates, errors, closed, storage, navigate, settleMenus, unmount };
}
