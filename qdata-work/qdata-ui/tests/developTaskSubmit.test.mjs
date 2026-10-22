import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref } from 'vue';
import { parse } from '@vue/compiler-sfc';

const source = path => parse(readFileSync(new URL(`../src/views/dpp/task/developTask/${path}`, import.meta.url), 'utf8')).descriptor.scriptSetup.content;
const dialog = source('add/add.vue'), parent = source('index.vue');
const childSource = dialog.slice(dialog.indexOf('const submitForm ='), dialog.indexOf('/**\n * check dolphinscheduler api'));
const parentSource = parent.slice(parent.indexOf('const submitTask ='), parent.indexOf('const leftWidth ='));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness(options = {}) {
    const events = [], requests = [], warnings = [], routes = [];
    const deps = {
        saveLoading: ref(false), userStore: { projectId: '19', projectCode: '9007199254740999' },
        createEtlTaskFront: async payload => { requests.push(payload); return options.response ? options.response() : { code: 200, data: { id: '9007199254740997' } }; },
        proxy: { $modal: { msgSuccess() {}, msgWarning: message => warnings.push(message) } },
        td: (_key, fallback) => fallback, handleQuery() {}, routeTo: (...args) => routes.push(args),
    };
    const submitTask = new Function(...Object.keys(deps), `${parentSource}; return submitTask;`)(...Object.values(deps));
    const child = {
        submitting: ref(false), props: { submitTask: options.legacy ? null : submitTask },
        form: ref({ name: 'fix_bin199', scheduler: options.scheduler || 'QUARTZ' }),
        daDiscoveryTaskRef: ref({ validate: options.validate || (async () => true) }),
        checkDSUpStart: options.checkDS || (async () => true), enforceQuartzJDBC() {},
        getNodeUniqueKey: options.key || (async () => ({ data: '9007199254740998' })),
        userStore: deps.userStore, templateAct: ref({}), emit: (...args) => events.push(args), proxy: deps.proxy,
        td: deps.td,
    };
    const actions = new Function(...Object.keys(child), `${childSource}; return { saveClose, saveData };`)(...Object.values(child));
    return { ...child, ...actions, submitTask, ...deps, events, requests, warnings, routes };
}

test('校验等待期三连点及交替点击只校验一次、保存一次', async () => {
    const validation = deferred(); let validations = 0;
    const state = harness({ validate: () => { validations++; return validation.promise; } });
    const first = state.saveClose();
    assert.equal(state.submitting.value, true);
    await state.saveClose(); await state.saveData();
    assert.equal(validations, 1); assert.equal(state.requests.length, 0);
    validation.resolve(true); await first;
    assert.equal(state.requests.length, 1);
    assert.equal(state.requests[0].projectCode, '9007199254740999');
    assert.deepEqual(state.events, [['update:visible', false]]);
    assert.equal(state.submitting.value, false);
});

test('节点编码与网络等待期持续互斥，成功前不关闭弹窗', async () => {
    const key = deferred(), response = deferred();
    const state = harness({ key: () => key.promise, response: () => response.promise });
    const first = state.saveClose(); await tick();
    await state.saveClose(); await state.saveData();
    assert.equal(state.requests.length, 0);
    key.resolve({ data: '9007199254740998' }); await tick();
    await state.saveClose(); await state.saveData();
    assert.equal(state.requests.length, 1); assert.deepEqual(state.events, []);
    assert.equal(state.submitting.value, true); assert.equal(state.saveLoading.value, true);
    response.resolve({ code: 200 }); await first;
    assert.deepEqual(state.events, [['update:visible', false]]);
    assert.equal(state.submitting.value, false); assert.equal(state.saveLoading.value, false);
});

for (const mode of ['validationReject', 'validationFalse', 'keyReject', 'networkReject', 'businessReject']) {
    test(`${mode} 失败保留表单并解锁，随后可重试成功`, async () => {
        let fail = true;
        const state = harness({
            validate: async () => { if (fail && mode === 'validationReject') throw { name: [{ message: '请输入任务名称' }] }; return !(fail && mode === 'validationFalse'); },
            key: async () => { if (fail && mode === 'keyReject') throw new Error('编码获取失败'); return { data: '1' }; },
            response: async () => { if (fail && mode === 'networkReject') throw new Error('网络断开'); return fail && mode === 'businessReject' ? { code: 500, msg: '任务名称已存在' } : { code: 200 }; },
        });
        await state.saveClose();
        assert.equal(state.submitting.value, false); assert.equal(state.saveLoading.value, false);
        assert.deepEqual(state.events, []); assert.equal(state.form.value.name, 'fix_bin199');
        fail = false; await state.saveClose();
        assert.deepEqual(state.events, [['update:visible', false]]);
    });
}

test('调度检查等待期互斥，检查失败解锁且不提交', async () => {
    const check = deferred(), state = harness({ scheduler: 'DOLPHINSCHEDULER', checkDS: () => check.promise });
    const first = state.saveData(); await state.saveClose(); await state.saveData();
    check.resolve(false); await first;
    assert.deepEqual(state.requests, []); assert.deepEqual(state.events, []); assert.equal(state.submitting.value, false);
});

test('保存并完善成功才跳转，精度安全ID保持字符串', async () => {
    const state = harness(); await state.saveData();
    assert.deepEqual(state.routes, [['/dpp/task/developTask/edit', { id: '9007199254740997' }]]);
    assert.deepEqual(state.events, [['update:visible', false]]);
});

test('父级独立阻止重复调用，业务失败提示且不跳转', async () => {
    const response = deferred(), state = harness({ response: () => response.promise });
    const first = state.submitTask({}, 'confirm');
    assert.equal(await state.submitTask({}, 'save'), false); assert.equal(await state.submitTask({}, 'confirm'), false);
    assert.equal(state.requests.length, 1);
    response.resolve({ code: 500, msg: '责任人不能为空' }); assert.equal(await first, false);
    assert.deepEqual(state.warnings, ['责任人不能为空']); assert.deepEqual(state.routes, []); assert.equal(state.saveLoading.value, false);
});

test('详情配置保留save事件与关闭契约，不请求创建接口', async () => {
    const state = harness({ legacy: true }); await state.saveClose();
    assert.deepEqual(state.requests, []); assert.deepEqual(state.events.map(item => item[0]), ['save', 'update:visible']);
});
