import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref, computed } from 'vue';
import { parse } from '@vue/compiler-sfc';

// Exercise the actual form handlers with controlled API responses, without a browser or live data writes.
const file = new URL('../src/views/dpp/task/integratioTask/components/output/tableForm.vue', import.meta.url);
const source = parse(readFileSync(file, 'utf8')).descriptor.scriptSetup.content
  .replace(/^import\s+[\s\S]*?from\s+["'][^"']+["'];?\s*$/gm, '');

function formHarness(lookup = async query => ({ data: [{ assetId: query.tableName === 'air' ? '2' : '3' }] })) {
  const emitted = [], errors = [], columns = [], lookups = [];
  const dependencies = {
    ref, computed, getCurrentInstance: () => ({ proxy: { $modal: { msgError: e => errors.push(e) }, resetForm() {} } }),
    defineProps: () => ({ visible: true }), defineEmits: () => (...args) => emitted.push(args),
    useDefaultLang: () => ({ td: x => x }), useUserStore: () => ({ projectId: '7', projectCode: '9007199254740993' }),
    watch() {}, typeList: [], console: { log() {}, warn() {} },
    listDaDatasource: async () => ({ data: { rows: [] } }), getHiveDatabases: async () => ({ data: ['ods_iot_db', 'other_db'] }),
    getTablesByDataSourceId: async () => ({ data: [{ tableName: 'ods_iot_db.air' }, { tableName: 'ods_iot_db.soil' }] }),
    getOutputTableAssets: async query => { lookups.push(query); return lookup(query); },
    getColumnByAssetId: async query => { columns.push(query); return { data: [] }; },
    getNodeUniqueKey: async () => ({ data: 'node1' }), handleType2TaskParams: () => ({ target_columns: [], columns: [] }),
  };
  const form = new Function(...Object.keys(dependencies), `${source}; return {
    form, dpModelRefs, saving, handleChange, handleDatabaseChange, resetAndFetchTables, saveData, TablesByDataSource, resolveOutputAsset
  };`)(...Object.values(dependencies));
  form.form.value = { code: 'node1', taskParams: {
    writerDatasource: { datasourceType: 'Hive', datasourceId: 1, databaseName: 'ods_iot_db', dbname: 'default', target_asset_id: 1 },
    target_asset_id: 'ods_iot_db.air', target_table_name: 'ods_iot_db.air', tableFields: [], toColumnsList: [], writeModeType: 2,
  } };
  form.dpModelRefs.value = { validate: async () => true };
  form.TablesByDataSource.value = [{ tableName: 'ods_iot_db.air' }, { tableName: 'ods_iot_db.soil' }];
  return { ...form, emitted, errors, columns, lookups };
}

test('connection 1 saves distinct air/soil identities and keeps the canonical table for column queries', async () => {
  const h = formHarness();
  for (const [table, id] of [['air', '2'], ['soil', '3']]) {
    h.form.value.taskParams.target_table_name = `ods_iot_db.${table}`;
    await h.handleChange(`ods_iot_db.${table}`);
    await h.saveData();
    const params = h.emitted.at(-1)[1].taskParams;
    assert.equal(params.target_asset_id, id); assert.equal(params.writerDatasource.target_asset_id, id);
    assert.equal(params.target_datasource_id, '1'); assert.equal(params.target_table_name, `ods_iot_db.${table}`);
    assert.deepEqual(h.columns.at(-1), { id: 1, tableName: `ods_iot_db.${table}` });
    assert.deepEqual(h.lookups.at(-1), { datasourceId: 1, databaseName: 'ods_iot_db', tableName: table, projectId: '7', projectCode: '9007199254740993' });
  }
});

test('saving a legacy draft repairs the datasource ID without requiring a table reselection', async () => {
  const h = formHarness(); await h.saveData();
  assert.equal(h.emitted[0][1].taskParams.writerDatasource.target_asset_id, '2');
});

test('new datasource and database changes clear the old asset identity', async () => {
  const h = formHarness();
  await h.resetAndFetchTables({ datasourceType: 'Hive', datasourceConfig: '{"dbname":"default"}', id: 1 });
  assert.equal(h.form.value.taskParams.writerDatasource.target_asset_id, '');
  h.form.value.taskParams.writerDatasource.target_asset_id = '2';
  await h.handleDatabaseChange();
  assert.equal(h.form.value.taskParams.writerDatasource.target_asset_id, '');
  assert.equal(h.form.value.taskParams.target_asset_id, '');
});

test('asset lookup failure is visible and cannot emit a successful save', async () => {
  const h = formHarness(async () => { throw new Error('asset access denied'); });
  await h.saveData();
  assert.equal(h.emitted.length, 0); assert.deepEqual(h.errors, ['asset access denied']);
  assert.equal(h.saving.value, false); assert.equal(h.form.value.taskParams.writerDatasource.target_asset_id, '');
});

test('ambiguous registration cannot choose an arbitrary asset', async () => {
  const h = formHarness(async () => ({ data: [{ assetId: '2' }, { assetId: '3' }] }));
  await h.saveData(); assert.equal(h.emitted.length, 0); assert.equal(h.errors.length, 1);
});

test('asset identities above Number.MAX_SAFE_INTEGER stay exact', async () => {
  const h = formHarness(async () => ({ data: [{ id: 9007199254740992, assetId: '9007199254740993' }] }));
  await h.saveData(); assert.equal(h.emitted[0][1].taskParams.writerDatasource.target_asset_id, '9007199254740993');
});

test('unregistered physical tables never inherit the previous datasource or asset ID', async () => {
  const h = formHarness(async () => ({ data: [] })); await h.saveData();
  assert.equal(h.emitted[0][1].taskParams.writerDatasource.target_asset_id, '');
});

test('a slow response cannot attach the old asset to a newly selected table', async () => {
  let complete;
  const h = formHarness(query => query.tableName === 'air' ? new Promise(resolve => { complete = resolve; }) : Promise.resolve({ data: [{ assetId: '3' }] }));
  const old = h.resolveOutputAsset();
  h.form.value.taskParams.target_table_name = 'ods_iot_db.soil';
  await h.resolveOutputAsset(); complete({ data: [{ assetId: '2' }] });
  await assert.rejects(old, /目标表已变化/);
  assert.equal(h.form.value.taskParams.writerDatasource.target_asset_id, '3');
});

test('repeated save clicks share one pending submission', async () => {
  let complete;
  const h = formHarness(() => new Promise(resolve => { complete = resolve; }));
  const first = h.saveData(); await Promise.resolve(); await h.saveData();
  complete({ data: [{ assetId: '2' }] }); await first;
  assert.equal(h.emitted.length, 1); assert.equal(h.lookups.length, 1);
});

test('ordinary database outputs keep saving without an asset lookup', async () => {
  const h = formHarness(); h.form.value.taskParams.writerDatasource.datasourceType = 'MySql';
  h.form.value.taskParams.target_table_name = 'mysql_air'; await h.saveData();
  assert.equal(h.lookups.length, 0); assert.equal(h.emitted.length, 1);
  assert.equal(h.emitted[0][1].taskParams.target_table_name, 'mysql_air');
});

test('missing precision-safe identity blocks the save instead of using a rounded number', async () => {
  const h = formHarness(async () => ({ data: [{ id: 9007199254740992 }] }));
  await h.saveData(); assert.equal(h.emitted.length, 0);
  assert.match(h.errors[0], /资产身份无效/);
});
