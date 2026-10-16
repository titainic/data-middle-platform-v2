import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref, computed, proxyRefs, nextTick } from 'vue';
import { parse } from '@vue/compiler-sfc';

// Execute the real page setup, preview handler and template column expression.
// Replace HTTP and lifecycle hooks so no server, database or timer is used.
const page = parse(readFileSync(new URL('../src/views/dp/warehouse/index.vue', import.meta.url), 'utf8')).descriptor;
const script = page.scriptSetup.content.replace(/^import .*;$/gm, '');
const dialog = page.template.content.match(/<el-dialog v-model="dataVisible"[\s\S]*?<\/el-dialog>/)[0];
const columnSource = dialog.match(/v-for="key in ([^"]+)"/)[1];
const columnBinding = /^[A-Za-z_$][\w$]*$/.test(columnSource) ? `, ${columnSource}` : '';
const evaluateColumns = new Function('state', `with (state) { return (${columnSource}); }`);
const fields = [
  { name: 'id', type: 'INT' },
  { name: 'name', type: 'STRING' },
  { name: 'amt', type: 'DECIMAL(10,2)' },
  { name: 'created', type: 'TIMESTAMP' },
];

function harness(response = async () => []) {
  const requests = [], messages = [];
  const dependencies = {
    ref, computed, proxyRefs, nextTick, evaluateColumns,
    useMediaQuery: () => ref(false),
    useRoute: () => ({ query: {} }), useRouter: () => ({}),
    onMounted() {}, onBeforeUnmount() {}, onBeforeRouteLeave() {},
    ElMessage: { error: message => messages.push(message) },
    request: async options => {
      requests.push(options);
      assert.equal(options.method, 'get');
      return { data: await response(options) };
    },
  };
  const state = new Function(...Object.keys(dependencies), `${script}
    return { form, dataRows, dataVisible, busy, error, showData,
      headers: () => evaluateColumns(proxyRefs({ dataRows, form${columnBinding} })) };
  `)(...Object.values(dependencies));
  state.form.value = { id: 70, columns: structuredClone(fields) };
  return { ...state, requests, messages };
}

test('empty model 70 preview keeps the four registered columns and empty-state message', async () => {
  const h = harness();
  await h.showData();
  assert.deepEqual(h.headers(), ['id', 'name', 'amt', 'created']);
  assert.deepEqual(h.dataRows.value, []);
  assert.equal(h.dataVisible.value, true);
  assert.equal(h.busy.value, false);
  assert.equal(h.requests[0].url, '/dp/warehouse/models/70/data');
  assert.match(dialog, /:data="dataRows"/);
  assert.match(dialog, /:key="key" :prop="key" :label="key"/);
  assert.match(dialog, /<p v-if="!dataRows.length">目标表暂无数据。<\/p>/);
});

test('nonempty preview preserves actual first-row keys, order and all row values', async () => {
  const rows = [
    { amt: '12345678.90', id: 7, extra: null, Name: '样本' },
    { amt: '0.01', id: 8, extra: false, Name: '', laterOnly: '2026-10-16 20:00:00' },
  ];
  const h = harness(async () => rows);
  await h.showData();
  assert.deepEqual(h.headers(), ['amt', 'id', 'extra', 'Name']);
  assert.deepEqual(h.dataRows.value, rows);
  assert.equal(h.dataRows.value[0].amt, '12345678.90');
  assert.equal(h.dataVisible.value, true);
});

test('a nonempty result with an empty first row retains the original zero-column behavior', async () => {
  const h = harness(async () => [{}, { id: 8 }]);
  await h.showData();
  assert.deepEqual(h.headers(), []);
  assert.equal(h.dataRows.value.length, 2);
});

test('empty results with absent, null or empty model fields have no fabricated columns', async () => {
  const h = harness();
  for (const columns of [undefined, null, []]) {
    h.form.value.columns = columns;
    await h.showData();
    assert.deepEqual(h.headers(), []);
    assert.equal(h.error.value, '');
    assert.equal(h.dataVisible.value, true);
  }
});

test('blank and invalid field names are skipped without mutating the model definition', async () => {
  const columns = [null, {}, { name: null }, { name: 42 }, { name: '' }, { name: ' \t\n' }, { name: ' name ' }];
  const h = harness();
  h.form.value.columns = structuredClone(columns);
  await h.showData();
  assert.deepEqual(h.headers(), ['name']);
  assert.deepEqual(h.form.value.columns, columns);
  h.form.value.columns = columns.slice(0, -1);
  assert.deepEqual(h.headers(), []);
});

test('duplicate fallback names appear once in registration order', async () => {
  const h = harness();
  h.form.value.columns = ['created', ' id ', 'id', 'amt', 'created'].map(name => ({ name }));
  await h.showData();
  assert.deepEqual(h.headers(), ['created', 'id', 'amt']);
});

test('reopening after a model switch uses the current model fields', async () => {
  const h = harness();
  await h.showData();
  assert.deepEqual(h.headers(), ['id', 'name', 'amt', 'created']);
  h.dataVisible.value = false;
  h.form.value = { id: '9007199254740993', columns: [{ name: 'partition_day' }, { name: 'total' }] };
  await h.showData();
  assert.deepEqual(h.headers(), ['partition_day', 'total']);
  assert.equal(h.requests[1].url, '/dp/warehouse/models/9007199254740993/data');
});

test('repeated previews switch between actual row keys and model fields without stale columns', async () => {
  let rows = [{ actual: 'kept' }];
  const h = harness(async () => rows);
  await h.showData();
  assert.deepEqual(h.headers(), ['actual']);
  rows = [];
  await h.showData();
  assert.deepEqual(h.headers(), ['id', 'name', 'amt', 'created']);
  rows = [{ changed: null }];
  await h.showData();
  assert.deepEqual(h.headers(), ['changed']);
  assert.deepEqual(h.dataRows.value, rows);
});

test('a failed request keeps the existing error feedback and does not open the preview', async () => {
  const h = harness(async () => { throw new Error('preview unavailable'); });
  await h.showData();
  assert.equal(h.dataVisible.value, false);
  assert.equal(h.busy.value, false);
  assert.equal(h.error.value, 'preview unavailable');
  assert.deepEqual(h.messages, ['preview unavailable']);
});
