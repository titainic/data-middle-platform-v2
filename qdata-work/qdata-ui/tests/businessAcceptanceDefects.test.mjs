import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref } from 'vue';
import { parse } from '@vue/compiler-sfc';
import { createI18n } from 'vue-i18n';
import zhSys from '../src/locales/zh-CN/sys/index.js';
import { isYearWithinHorizon } from '../src/components/Crontab/yearPolicy.mjs';

const script = file => parse(readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')).descriptor.scriptSetup.content;
const detail = script('views/dpp/task/developTask/detail/index.vue');

test('真实保存组装保留责任人、Cron和所属项目，数值责任人转为字符串', () => {
    const persisted = { id: '37', code: '9007199254740999', version: 1, type: '3', projectId: '19', projectCode: '9999999999999999', personCharge: '1', crontab: '0 * * * * ?' };
    const form = ref({ ...persisted, sql: 'select 1' });
    const nodeData = ref({ name: 'fix_task', personCharge: 2, projectId: '20', projectCode: '2020' });
    const configViewRef = ref({ form: { taskType: 'SQL', componentType: 'SQL' } });
    const source = detail.slice(detail.indexOf('const dataJson ='), detail.indexOf('// Great save'));
    const build = new Function('form', 'nodeData', 'configViewRef', `${source}; return dataJson;`)(form, nodeData, configViewRef);
    const payload = JSON.parse(JSON.stringify(build()));
    assert.equal(payload.personCharge, '2');
    assert.equal(payload.crontab, persisted.crontab);
    assert.equal(payload.projectId, '19');
    assert.equal(payload.projectCode, persisted.projectCode);
    assert.equal(JSON.parse(payload.taskDefinitionList)[0].code, persisted.code);
    assert.equal(JSON.parse(payload.taskDefinitionList)[0].taskParams.sql, 'select 1');
});

test('草稿调用首次保存、已有定义调用更新，业务失败显示后端msg并恢复按钮', async () => {
    const source = detail.slice(detail.indexOf('const handleExportData ='), detail.indexOf('const handleSuccess ='));
    const calls = [], messages = [];
    const loading = ref(false), formStatus = ref('-1');
    const deps = {
        loading, formStatus, form: ref({ id: '37', sql: 'select 1' }), nodeData: ref({ name: 'fix_task' }),
        taskConfigDialogVisible: ref(false), configViewRef: ref({ configRef: { validate: async () => true } }),
        dataJson: () => ({ id: '37', personCharge: '1', crontab: '0 * * * * ?', projectId: '19' }),
        createProcessDefinition: async payload => { calls.push(['create', payload]); return { code: 200 }; },
        updateProcessDefinition: async payload => { calls.push(['update', payload]); return { code: 500, msg: '责任人不能为空' }; },
        handleSuccess: () => calls.push(['success']), handleError: error => { throw error; },
        proxy: { $modal: { msgWarning: text => messages.push(text) } }, td: (key, fallback) => fallback,
    };
    const save = new Function(...Object.keys(deps), `${source}; return handleExportData;`)(...Object.values(deps));
    await save(); formStatus.value = '0'; await save();
    assert.deepEqual(calls.map(call => call[0]), ['create', 'success', 'update']);
    assert.deepEqual(messages, ['责任人不能为空']);
    assert.equal(loading.value, false);
    loading.value = true; await save();
    assert.equal(calls.length, 3);
});

test('真实用户删除/禁用处理函数用翻译第三参数插值，支持批量和精度安全ID', async () => {
    const user = script('views/sys/system/user/index.vue');
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': { sys: zhSys } } });
    const confirms = [], requests = [];
    const deps = { ids: ref(['9007199254740999', '15']),
        td: (key, fallback, params) => i18n.global.te(key) ? i18n.global.t(key, params) : fallback,
        proxy: { $modal: { confirm: text => { confirms.push(text); return Promise.resolve(); }, msgSuccess() {} } },
        delUser: id => { requests.push(id); return Promise.resolve(); },
        changeUserStatus: (id, status) => { requests.push([id, status]); return Promise.resolve(); }, getList() {},
    };
    const source = user.slice(user.indexOf('function handleDelete(row)'), user.indexOf('/** Export button action */'))
        + user.slice(user.indexOf('function handleStatusChange(row)'), user.indexOf('/** More actions */'));
    const handlers = new Function(...Object.keys(deps), `${source}; return {handleDelete, handleStatusChange};`)(...Object.values(deps));
    handlers.handleDelete({});
    handlers.handleStatusChange({ userId: '9007199254740999', userName: 'fix_user', status: '1' });
    await new Promise(resolve => setImmediate(resolve));
    assert.match(confirms[0], /9007199254740999,15/);
    assert.match(confirms[1], /停用|禁用/);
    assert.match(confirms[1], /fix_user/);
    assert.match(confirms[1], /9007199254740999/);
    assert.equal(requests.length, 2);
});

test('Cron显式年、区间和间隔受五年窗口约束，保留六段和每年周期语义', () => {
    for (const year of ['', '*', '2026', '2031', '2026-2031', '2027/5', '2026,2031']) assert.equal(isYearWithinHorizon(year, 2026), true, year);
    for (const year of ['2099', '2026-2099', '2032', '2027/20', '2099/1', '2031-2026', '2026/0', 'abc']) assert.equal(isYearWithinHorizon(year, 2026), false, year);
});
