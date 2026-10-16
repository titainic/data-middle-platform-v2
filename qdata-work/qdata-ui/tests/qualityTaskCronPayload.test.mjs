import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';

// Run the real page setup, its Cron dialog template and the real API wrapper.
// Only the generator UI and bottom-level request transport are substitutes;
// no browser, credentials, backend or scheduler is used by this test.
const pageUrl = new URL('../src/views/da/quality/qualityTask/index.vue', import.meta.url);
const { descriptor, errors } = parse(readFileSync(pageUrl, 'utf8'));
assert.deepEqual(errors, []);
const setup = compileScript(descriptor, { id: 'quality-task-cron-payload', genDefaultAs: '__component' });
const setupCode = setup.content.replace(/import\s+[\s\S]*?\sfrom\s*["'][^"']+["'];?/g, '');

function findNode(node, predicate) {
  if (predicate(node)) return node;
  for (const child of node.children || []) {
    const match = findNode(child, predicate);
    if (match) return match;
  }
}

const dialogNode = findNode(descriptor.template.ast, node => node.tag === 'el-dialog');
const template = compileTemplate({
  source: dialogNode.loc.source,
  filename: 'qualityTask.vue',
  id: 'quality-task-cron-payload',
  compilerOptions: { bindingMetadata: setup.bindings },
});
assert.deepEqual(template.errors, []);
const renderCode = template.code
  .replace(/import \{([^}]+)\} from "vue"/g, (_, bindings) =>
    `const {${bindings.replace(/ as /g, ': ')}} = Vue;`)
  .replace('export function render', 'function render');
const render = new Function('Vue', `${renderCode}; return render;`)(Vue);

const apiCode = readFileSync(new URL('../src/api/da/quality/qualityTask.js', import.meta.url), 'utf8')
  .replace(/import request from [^\n]+/, '')
  .replace(/export function /g, 'function ');
const apiFactory = new Function('request', `${apiCode}; return {
  listDppQualityTask, qualityProjects, updateDaDiscoveryTaskCronExpression
};`);
const updateUrl = '/dpp/qualityTask/updateDaDiscoveryTaskCronExpression';
const unexpected = () => { throw new Error('Unexpected operation in offline Cron test'); };
const node = (type, text = '') => ({ type, text, children: [], parent: null, props: {} });
const renderer = Vue.createRenderer({
  createElement: node, createText: text => node('text', text), createComment: text => node('comment', text),
  setText: (n, text) => { n.text = text; }, setElementText: (n, text) => { n.text = text; },
  parentNode: n => n?.parent,
  nextSibling: n => n?.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp: (n, key, previous, value) => { n.props[key] = value; },
  insert(n, parent, anchor) {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1);
    n.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    if (index < 0) parent.children.push(n); else parent.children.splice(index, 0, n);
  },
  remove(n) {
    if (n?.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1);
    if (n) n.parent = null;
  },
});
const flush = async () => { await Promise.resolve(); await Vue.nextTick(); };

async function harness(t) {
  const requests = [], messages = [], warnings = [];
  let generator;
  const Crontab = Vue.defineComponent({
    props: ['expression'], emits: ['fill', 'hide'],
    setup(props, { emit }) {
      generator = { props, confirm: value => emit('fill', value), cancel: () => emit('hide') };
      return () => null;
    },
  });
  const request = config => {
    assert.ok([updateUrl, '/dpp/qualityTask/list', '/dpp/qualityTask/projects'].includes(config.url));
    requests.push(JSON.parse(JSON.stringify(config)));
    return Promise.resolve({ code: 200, data: config.url.endsWith('/list') ? { rows: [], total: 0 } : [] });
  };
  const dependencies = {
    ref: Vue.ref, computed: Vue.computed, reactive: Vue.reactive, toRefs: Vue.toRefs,
    getCurrentInstance: Vue.getCurrentInstance, onActivated: Vue.onActivated,
    ...apiFactory(request), treeData: [], Crontab,
    createEtlTaskFront: unexpected, delDppQualityTask: unexpected,
    updateDppQualityTaskStatus: unexpected, startDppQualityTask: unexpected,
    cronToZh: value => value, DataViewDialog: {}, DeptTree: {},
    useRoute: () => ({}), useRouter: () => ({}), useUserStore: () => ({}),
    deptUserTree: async () => ({ data: [] }), listAttQualityCat: async () => ({ data: [] }),
    useDefaultLang: () => ({ td: value => value }),
  };
  const component = new Function(...Object.keys(dependencies), `${setupCode}; return __component;`)(...Object.values(dependencies));
  component.render = render;
  const app = renderer.createApp(component);
  // Keep the original dialog's v-model and event bindings. Render its slot only when open.
  app.component('el-dialog', Vue.defineComponent({
    inheritAttrs: false,
    props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? slots.default() : null,
  }));
  Object.assign(app.config.globalProperties, {
    useDict: (...names) => Object.fromEntries(names.map(name => [name, Vue.ref([])])),
    handleTree: value => value, $modal: { msgSuccess: value => messages.push(value) },
  });
  app.config.warnHandler = message => warnings.push(message);
  const vm = app.mount(node('root'));
  t.after(() => { app.unmount(); assert.deepEqual(warnings, []); });
  await flush();
  const state = vm.$.setupState;
  return {
    state, messages, updates: () => requests.filter(config => config.url === updateUrl),
    async open(row) {
      state.handleJobLog(row); await flush();
      assert.equal(state.openCron, true); assert.equal(generator.props.expression, row.cycle);
    },
    async confirm(value) { generator.confirm(value); await flush(); },
    async cancel() { generator.cancel(); await flush(); },
  };
}

function taskRow(overrides = {}) {
  return { id: '10701', cycle: '0 0 1 * * ?', status: '1', capabilities: { schedule: true }, ...overrides };
}

for (const [name, original, confirmed, id] of [
  ['six fields', '0 0 1 * * ?', '0 30 2 * * ?', '10701'],
  ['seven fields including 2099', '0 0 0 1 1 ? 2099', '0 15 0 1 1 ? 2099', 10702],
  ['six to seven fields', '0 0 1 * * ?', '0 45 3 1 1 ? 2099', '10703'],
  ['unchanged six fields', '0 0 1 * * ?', '0 0 1 * * ?', '10704'],
  ['unchanged seven fields', '0 15 0 1 1 ? 2099', '0 15 0 1 1 ? 2099', '10705'],
]) {
  test(`Cron confirmation sends the confirmed cycle: ${name}`, async t => {
    const h = await harness(t);
    const row = taskRow({ cycle: original, id });
    await h.open(row); await h.confirm(confirmed);
    assert.deepEqual(h.updates(), [{ url: updateUrl, method: 'post', data: { cycle: confirmed, status: '1', id: Number(id) } }]);
    assert.equal(row.status, '1'); assert.equal(h.messages.length, 1);
  });
}

test('cancel emits hide, closes the real dialog binding and sends zero updates', async t => {
  const h = await harness(t);
  const row = taskRow();
  await h.open(row); await h.cancel();
  assert.equal(h.state.openCron, false);
  assert.deepEqual(h.updates(), []); assert.equal(h.messages.length, 0);
  assert.equal(row.cycle, '0 0 1 * * ?');
});

test('two consecutive confirmations each send their latest expression', async t => {
  const h = await harness(t);
  await h.open(taskRow());
  await h.confirm('0 30 2 * * ?');
  await h.confirm('0 45 3 1 1 ? 2099');
  assert.deepEqual(h.updates().map(config => config.data), [
    { cycle: '0 30 2 * * ?', status: '1', id: 10701 },
    { cycle: '0 45 3 1 1 ? 2099', status: '1', id: 10701 },
  ]);
});

test('reopening for another row binds the current task ID and confirmation', async t => {
  const h = await harness(t);
  await h.open(taskRow()); await h.confirm('0 30 2 * * ?'); await h.cancel();
  await h.open(taskRow({ id: '10709', cycle: '0 0 0 1 1 ? 2099' }));
  await h.confirm('0 15 0 1 1 ? 2099');
  assert.deepEqual(h.updates()[1].data, { cycle: '0 15 0 1 1 ? 2099', status: '1', id: 10709 });
});

test('the real entry retains schedule capability, disabled status, warehouse and permission guards', () => {
  const entry = findNode(descriptor.template.ast, node => node.tag === 'el-button' &&
    node.props.some(prop => prop.name === 'on' && prop.exp?.content === 'handleJobLog(scope.row)'));
  const disabled = entry.props.find(prop => prop.name === 'bind' && prop.arg?.content === 'disabled').exp.content;
  const isDisabled = new Function('scope', `return ${disabled};`);
  assert.equal(isDisabled({ row: taskRow() }), false);
  for (const row of [taskRow({ status: '0' }), taskRow({ capabilities: {} }), taskRow({ warehouseAssessmentId: '7' })]) {
    assert.equal(isDisabled({ row }), true);
  }
  assert.equal(entry.props.find(prop => prop.name === 'hasPermi').exp.content, "['da:qualityTask:schedule']");
});
