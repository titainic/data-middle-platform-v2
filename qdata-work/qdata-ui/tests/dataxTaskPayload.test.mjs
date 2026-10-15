import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { sanitizeDataXTaskPayload } from '../src/utils/dataxTaskPayload.js';

// Capture the actual API wrapper's request without making network or data writes.
const apiSource = readFileSync(new URL('../src/api/dpp/task/index.js', import.meta.url), 'utf8')
  .replace(/^import .*$/gm, '').replaceAll('export function ', 'function ');
const requests = [];
const api = new Function('request', 'sanitizeDataXTaskPayload', `${apiSource}; return {
  createProcessDefinitions, createEtlTaskFrontPostposition, updateProcessDefinitions
};`)(value => { requests.push(value); return value; }, sanitizeDataXTaskPayload);

const id = '9007199254740993';
const reference = {
  datasourceId: id, datasourceType: 'MySql', datasourceName: 'source', dbname: 'source_db',
  databaseName: 'chosen_db', target_asset_id: 'asset-1',
};
const connection = {
  ...reference, ip: 'client-host', host: 'client-host', port: 3306,
  username: 'client-user', password: 'fixture-client', jdbcUrl: 'jdbc:client-host',
  datasourceConfig: JSON.stringify({ username: 'client-user', password: 'fixture-client',
    host: 'client-host', port: 3306, dbname: 'source_db', unknownConnectionOption: 'value' }),
  unknownConnectionOption: 'value',
};
const node = (key, source = connection) => ({
  code: key, taskType: 'DATAX', version: 2, componentType: key === 'writerDatasource' ? '91' : '1',
  taskParams: { [key]: source, columns: ['id', 'name', 'amt', 'created'],
    table_name: 'src', target_table_name: 'dst', target_asset_id: 'asset-1',
    writeModeType: 2, preSql: '', postSql: '', querySql: "select 'username' from src" },
});
const definitions = () => [node('readerDatasource'), node('writerDatasource'), node('datasources')];
const payload = () => ({
  id: '10', taskType: 'DATAX', actuator: 'DATAX', draftJson: '{"taskType":"DATAX"}',
  taskDefinitionList: JSON.stringify(definitions()), taskRelationJson: '[{"preTaskCode":"reader"}]',
  locations: [{ taskCode: 'reader', x: 0, y: 0 }], projectId: '1', projectCode: id,
});

for (const [method, path] of [
  ['createProcessDefinitions', 'createEtlTask'],
  ['createEtlTaskFrontPostposition', 'createEtlTaskFrontPostposition'],
  ['updateProcessDefinitions', 'updateEtlTask'],
]) {
  for (const serialized of [true, false]) {
    test(`${method} strips connections before transport (${serialized ? 'JSON' : 'array'})`, () => {
      const original = payload();
      if (!serialized) original.taskDefinitionList = definitions();
      const before = structuredClone(original);
      const query = { localSave: true };
      const request = api[method](original, query);
      assert.equal(request.url, `/dpp/etlTask/${path}`);
      assert.equal(request.method, 'post');
      if (method === 'updateProcessDefinitions') assert.equal(request.params, query);
      assert.deepEqual(original, before, 'saving must not mutate the live canvas');
      const nodes = serialized ? JSON.parse(request.data.taskDefinitionList) : request.data.taskDefinitionList;
      for (const [index, key] of ['readerDatasource', 'writerDatasource', 'datasources'].entries()) {
        assert.deepEqual(nodes[index].taskParams[key], reference);
        assert.deepEqual(nodes[index], { ...definitions()[index], taskParams: {
          ...definitions()[index].taskParams, [key]: reference,
        } });
      }
      assert.deepEqual({ ...request.data, taskDefinitionList: '' }, { ...original, taskDefinitionList: '' });
      assert.equal(nodes[0].taskParams.readerDatasource.datasourceId, id);
      assert.ok(!JSON.stringify(request.data).includes('client-user'));
      assert.ok(!JSON.stringify(request.data).includes('client-host'));
      assert.ok(!JSON.stringify(request.data).includes('fixture-client'));
      assert.ok(!JSON.stringify(request.data).includes('datasourceConfig'));
    });
  }
}

test('legacy IDs and file reader placeholders remain usable; config objects are discarded too', () => {
  const data = { taskType: 'DATAX', taskDefinitionList: [
    node('readerDatasource', { id, datasource_id: id, datasourceConfig: { username: 'client-user' } }),
    node('readerDatasource', { datasourceId: '', datasourceConfig: '{}' }),
    node('writerDatasource', null), { taskParams: { csvFile: '/offline/fixture.csv' } },
  ] };
  const nodes = sanitizeDataXTaskPayload(data).taskDefinitionList;
  assert.deepEqual(nodes[0].taskParams.readerDatasource, { id, datasource_id: id });
  assert.deepEqual(nodes[1].taskParams.readerDatasource, { datasourceId: '' });
  assert.equal(nodes[2].taskParams.writerDatasource, null);
  assert.equal(nodes[3].taskParams.csvFile, '/offline/fixture.csv');
});

for (const engine of [{ actuator: 'DATAX' }, { draftJson: '{"taskType":"DATAX"}' }, { draftJson: { taskType: 'DATAX' } }]) {
  test(`engine fallback ${JSON.stringify(engine)} still strips connection information`, () => {
    const data = { ...engine, taskDefinitionList: definitions() };
    assert.deepEqual(sanitizeDataXTaskPayload(data).taskDefinitionList[0].taskParams.readerDatasource, reference);
  });
}

test('Spark and tasks without definitions keep their existing request behavior', () => {
  for (const data of [
    { taskType: 'SPARK', taskDefinitionList: 'untouched' }, { draftJson: '' },
    { taskType: 'DATAX' }, { taskType: 'DATAX', taskDefinitionList: null },
  ]) assert.equal(sanitizeDataXTaskPayload(data), data);
});

test('invalid DataX definitions fail before a request can leak the original payload', () => {
  const count = requests.length;
  assert.throws(() => api.updateProcessDefinitions({ ...payload(), taskDefinitionList: 'invalid-json' }));
  assert.throws(() => api.createEtlTaskFrontPostposition({ ...payload(), taskDefinitionList: '{}' }));
  assert.equal(requests.length, count);
});
