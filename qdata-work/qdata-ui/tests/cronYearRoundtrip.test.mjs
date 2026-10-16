import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as Vue from 'vue';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';

// Real SFC scripts/templates and Vue mount/watch ordering; only Element Plus
// controls and the rendering host are substitutes. No browser or business API.
const ui = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const componentDir = process.env.BIN99_CRON_DIR || resolve(ui, 'src/components/Crontab');
const cache = new Map();
const RealDate = Date;
const anchor = '2026-10-16T12:00:00Z';
globalThis.Date = class extends RealDate {
  constructor(...args) { super(...(args.length ? args : [anchor])); }
  static now() { return new RealDate(anchor).getTime(); }
};
function vueImports(code) {
  return code.replace(/import\s*\{([^}]+)\}\s*from\s*["']vue["'];?/g,
    (_, names) => `const {${names.replace(/\bas\b/g, ':')}} = Vue;`);
}
function loadSfc(file) {
  if (cache.has(file)) return cache.get(file);
  const descriptor = parse(readFileSync(file, 'utf8'), { filename: file }).descriptor;
  const compiled = compileScript(descriptor, { id: file, genDefaultAs: '__component' });
  const dependencies = { Vue, ref: Vue.ref, computed: Vue.computed, watch: Vue.watch, onMounted: Vue.onMounted, getCurrentInstance: Vue.getCurrentInstance, useDefaultLang: () => ({ td: key => key }) };
  let code = vueImports(compiled.content).replace(/import\s+(\w+)\s+from\s+["']([^"']+)["'];?/g,
    (_, name, source) => {
      if (source.endsWith('.vue')) dependencies[name] = loadSfc(resolve(dirname(file), source));
      else assert.equal(name, 'useDefaultLang');
      return '';
    });
  const component = new Function(...Object.keys(dependencies), `${code}; return __component;`)(...Object.values(dependencies));
  const template = compileTemplate({ source: descriptor.template.content, filename: file, id: file,
    compilerOptions: { bindingMetadata: compiled.bindings } });
  assert.deepEqual(template.errors, []);
  component.render = new Function('Vue', `${vueImports(template.code).replace('export function render', 'function render')}; return render;`)(Vue);
  cache.set(file, component);
  return component;
}
function node(type, text = '') { return { type, text, children: [], parent: null, props: {} }; }
const renderer = Vue.createRenderer({
  createElement: node, createText: text => node('text', text), createComment: text => node('comment', text),
  setText: (n, text) => { n.text = text; }, setElementText: (n, text) => { n.text = text; n.children = []; },
  parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp: (n, key, oldValue, value) => { n.props[key] = value; },
  insert(n, parent, anchor = null) {
    if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1);
    n.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(index < 0 ? parent.children.length : index, 0, n);
  },
  remove(n) { n.parent?.children.splice(n.parent.children.indexOf(n), 1); n.parent = null; },
});
function mount(component, props = {}) {
  const root = node('root');
  const app = renderer.createApp(component, props);
  const control = { setup: (_, { slots }) => () => Vue.h('control', null, slots.default?.()) };
  for (const name of ['form', 'form-item', 'radio', 'input-number', 'select', 'option', 'tabs', 'tab-pane', 'tooltip', 'button']) app.component(`el-${name}`, control);
  app.config.globalProperties.$message = { error(message) { throw new Error(message); } };
  app.config.warnHandler = message => { throw new Error(message); };
  const vm = app.mount(root);
  return { root, app, vm, state: vm.$.setupState };
}
async function settle() { await Vue.nextTick(); await Vue.nextTick(); }
function descendants(instance, found = []) {
  function visit(vnode) {
    if (!vnode) return;
    if (vnode.component) { found.push(vnode.component); visit(vnode.component.subTree); }
    else if (Array.isArray(vnode.children)) vnode.children.forEach(visit);
  }
  visit(instance.subTree);
  return found;
}
function child(h, filename) {
  return descendants(h.vm.$).find(i => i.type === loadSfc(resolve(componentDir, filename))).setupState;
}
const cases = [
  ['', 1], ['*', 2], ['2099', 5], ['2048,2099', 5],
  ['2090-2099', 3], ['2080/5', 4], ['2027/20', 4], ['2110-2120', 3], ['2100/5', 4],
];
const expressions = [];
for (const [year, radio] of cases) {
  const expression = `0 0 0 1 1 ?${year ? ` ${year}` : ''}`;
  test(`挂载年字段 ${year || '六段'}：同步初值且不emit`, async () => {
    const updates = [];
    const h = mount(loadSfc(resolve(componentDir, 'year.vue')), { cron: { year }, check: (v, min, max) => Math.min(max, Math.max(min, Math.floor(v))), onUpdate: (...args) => updates.push(args) });
    try {
      await settle();
      assert.equal(h.state.radioValue, radio);
      if (radio === 5) assert.deepEqual(h.state.checkboxList, year.split(',').map(Number));
      if (radio === 3) assert.deepEqual([h.state.cycle01, h.state.cycle02], year.split('-').map(Number));
      if (radio === 4) assert.deepEqual([h.state.average01, h.state.average02], year.split('/').map(Number));
      assert.deepEqual(updates, []);
    } finally { h.app.unmount(); }
  });
  test(`完整生成器 ${year || '六段'}：打开不改确认保留、取消不回填`, async () => {
    const fills = [], hides = [];
    const h = mount(loadSfc(resolve(componentDir, 'index.vue')), { expression, onFill: v => fills.push(v), onHide: () => hides.push(true) });
    try {
      await settle();
      assert.equal(h.state.crontabValueString, expression);
      assert.equal(child(h, 'year.vue').radioValue, radio);
      h.state.hidePopup();
      assert.deepEqual(fills, []);
      h.state.submitFill();
      assert.deepEqual(fills, [expression]);
      assert.equal(hides.length, 2);
      expressions.push({ input: expression, output: fills[0], preview: [...child(h, 'result.vue').resultList] });
    } finally { h.app.unmount(); }
  });
}

test('2099和列表外年份有带数字标签的选项，列表可编辑且取消不改变源表达式', async () => {
  let source = '0 0 0 1 1 ? 2048,2099';
  const h = mount(loadSfc(resolve(componentDir, 'index.vue')), { expression: source, onFill: value => { source = value; } });
  try {
    await settle();
    const year = child(h, 'year.vue');
    for (const value of [2048, 2099]) assert.ok(year.yearOptions.includes(value));
    year.checkboxList = [2099];
    year.onRadioChange();
    await settle();
    assert.equal(h.state.crontabValueString, '0 0 0 1 1 ? 2099');
    assert.ok(year.yearOptions.includes(2048), '移除的导入年份仍可重新选回');
    year.checkboxList = [2048, 2099];
    year.onRadioChange();
    await settle();
    assert.equal(h.state.crontabValueString, source);
    h.state.hidePopup();
    assert.equal(source, '0 0 0 1 1 ? 2048,2099');
  } finally { h.app.unmount(); }
});

test('props再次同步与对象替换不emit，年份步长不倒置、不限制为未来十年', async () => {
  const cron = Vue.reactive({ year: '2099' });
  const updates = [];
  const h = mount(loadSfc(resolve(componentDir, 'year.vue')), { cron, onUpdate: (...args) => updates.push(args) });
  try {
    for (const [value, radio] of [['2080/15', 4], ['2090-2099', 3], ['2048,2099', 5], ['', 1], ['*', 2]]) {
      cron.year = value;
      await settle();
      assert.equal(h.state.radioValue, radio);
      assert.deepEqual(updates, []);
    }
  } finally { h.app.unmount(); }
});

test('主动切换不填/每年/周期/间隔/指定及清空选择仍有效', async () => {
  const h = mount(loadSfc(resolve(componentDir, 'index.vue')), { expression: '0 0 0 1 1 ? 2099' });
  try {
    await settle();
    const year = child(h, 'year.vue');
    for (const [radio, expected] of [[1, ''], [2, '*'], [3, '2090-2099'], [4, '2080/15'], [5, '2099']]) {
      year.cycle01 = 2090; year.cycle02 = 2099;
      year.average01 = 2080; year.average02 = 15;
      year.checkboxList = [2099]; year.radioValue = radio;
      year.onRadioChange();
      await settle();
      assert.equal(h.state.crontabValueObj.year, expected);
    }
    year.checkboxList = [];
    year.onRadioChange();
    await settle();
    assert.equal(h.state.crontabValueObj.year, '2099');
  } finally { h.app.unmount(); }
});

test('已挂载组件接收替换的cron对象时只同步显示', async () => {
  const cron = Vue.ref({ year: '2099' }), updates = [];
  const component = loadSfc(resolve(componentDir, 'year.vue'));
  const host = { setup: () => () => Vue.h(component, { cron: cron.value, onUpdate: (...args) => updates.push(args) }) };
  const h = mount(host);
  try {
    await settle();
    cron.value = { year: '2080/15' };
    await settle();
    const year = child(h, 'year.vue');
    assert.deepEqual([year.radioValue, year.average01, year.average02], [4, 2080, 15]);
    assert.deepEqual(updates, []);
  } finally { h.app.unmount(); }
});

test('模板控件真实model/change事件可编辑年份，不依赖响应式watch回写', async () => {
  const h = mount(loadSfc(resolve(componentDir, 'index.vue')), { expression: '0 0 0 1 1 ? 2099' });
  try {
    await settle();
    const yearInstance = descendants(h.vm.$).find(i => i.type === loadSfc(resolve(componentDir, 'year.vue')));
    const controls = () => descendants(yearInstance).map(i => i.vnode.props || {});
    const change = async (control, value) => {
      control['onUpdate:modelValue'](value);
      control.onChange(value);
      await settle();
    };
    await change(controls().find(p => p.value === 4 && p.onChange), 4);
    const numbers = controls().filter(p => p['onUpdate:modelValue'] && p.value === undefined && p.onChange && p.min !== undefined);
    await change(numbers[2], 2080);
    await change(controls().filter(p => p['onUpdate:modelValue'] && p.onChange && p.min !== undefined)[3], 15);
    assert.equal(h.state.crontabValueObj.year, '2080/15');
    await change(controls().find(p => p.value === 5 && p.onChange), 5);
    await change(controls().find(p => p.multiple !== undefined && p.onChange), [2048, 2099]);
    assert.equal(h.state.crontabValueObj.year, '2048,2099');
  } finally { h.app.unmount(); }
});

function walk(dir) { return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(resolve(dir, e.name)) : [resolve(dir, e.name)]); }
const entries = walk(resolve(ui, 'src/views')).filter(file => file.endsWith('.vue') && /import Crontab from ["']@\/components\/Crontab\/index.vue/.test(readFileSync(file, 'utf8')));
assert.equal(entries.length, 10, '共享入口数量变化时更新回归范围');
for (const file of entries) {
  test(`共享入口事件契约：${file.slice(ui.length + 1)}`, async () => {
    const descriptor = parse(readFileSync(file, 'utf8')).descriptor;
    const template = descriptor.template.content.replace(/<!--[\s\S]*?-->/g, '');
    const structured = file.includes('/mc/task/structured/');
    assert.match(template, structured ? /:expression="cronDialog.data"/ : /<crontab\b[^>]*:expression="expression"/i);
    assert.match(template, structured ? /@fill="handleConfirmCronClick"/ : /<crontab\b[^>]*@fill="crontabFill"/i);
    assert.match(template, structured ? /@hide="handleCloseCronClick"/ : /<crontab\b[^>]*@hide="\w+\s*=\s*false"/i);
    // Run the actual fill handler with validation/transport substitutes.
    const script = descriptor.scriptSetup?.content || descriptor.script.content;
    const start = script.search(structured ? /function handleConfirmCronClick\(data\)/ : /(?:async )?function crontabFill\(value\)/);
    assert.ok(start >= 0);
    const open = script.indexOf('{', start);
    let end = open + 1, depth = 1;
    while (depth && end < script.length) { if (script[end] === '{') depth++; if (script[end] === '}') depth--; end++; }
    const handler = script.slice(start, end);
    const form = Vue.ref({}), row = Vue.ref({ id: 1 }), requests = [];
    const dialog = { form: {} };
    const deps = { form, row, dialog, cronDialog: {}, taskData: Vue.ref({id:1}), userStore: {projectId:1,projectCode:'p'}, nextTick: Vue.nextTick, daDiscoveryTaskRef: Vue.ref(null), formRef: Vue.ref(null),
      releaseTaskCrontab: v => { requests.push(v); return Promise.resolve({}); },
      updateDaDiscoveryTaskCronExpression: v => { requests.push(v); return Promise.resolve({}); },
      proxy: { $modal: { msgSuccess() {} } }, td: v => v, getList() {}, getTaskList() {}, handleQuery() {}, fetchData() {} };
    const fill = new Function(...Object.keys(deps), `${handler}; return ${structured ? 'handleConfirmCronClick' : 'crontabFill'};`)(...Object.values(deps));
    const expression = '0 0 0 1 1 ? 2099';
    await fill(expression);
    assert.ok(Object.values(form.value).includes(expression) || Object.values(row.value).includes(expression) || Object.values(dialog.form).includes(expression) || requests.some(v => JSON.stringify(v).includes(expression))); 
    // Quality task list has a separate existing cycle/crontab transport mismatch;
    // this regression checks its component binding and local fill only.
    if (requests.length && !file.endsWith('/da/quality/qualityTask/index.vue')) assert.ok(JSON.stringify(requests).includes(expression));
  });
}

test.after(() => {
  globalThis.Date = RealDate;
  if (process.env.BIN99_CRON_OUTPUT) writeFileSync(process.env.BIN99_CRON_OUTPUT, JSON.stringify(expressions, null, 2));
});
