import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRenderer, defineComponent, h, nextTick, reactive, ref } from 'vue';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';
import { useHandlers } from 'element-plus/es/components/upload/src/use-handlers.mjs';
import useImportUpload from '../src/composables/useImportUpload.js';

function harness() {
  const upload = reactive({ open: true, isUploading: false, updateSupport: 0 });
  const messages = [], successes = [], requests = [];
  let aborts = 0, clears = 0, submitError;
  const uploadRef = {
    abort: () => aborts++, clearFiles: () => clears++,
    submit: () => { if (submitError) throw submitError; requests.push('upload'); },
  };
  const flow = useImportUpload({
    upload, getUploadRef: () => uploadRef,
    onEmpty: () => messages.push('请选择文件'),
    onError: message => messages.push(message || '导入失败，请检查文件后重试'),
    onSuccess: response => { successes.push(response); upload.open = false; },
  });
  return {
    ...flow, upload, messages, successes, requests,
    select: (uid = 1, status = 'ready') => {
      const file = { uid, status, raw: { name: 'users.xlsx' } };
      flow.importFiles.value = [file];
      return file;
    },
    failSubmit: () => { submitError = new Error('upload unavailable'); },
    counts: () => ({ aborts, clears }),
  };
}

test('空文件/移除后的空队列：提示，按钮可用，不提交请求，可继续选文件提交', () => {
  const f = harness();
  f.submitFileForm();
  assert.deepEqual(f.messages, ['请选择文件']);
  assert.equal(f.importLoading.value, false);
  assert.equal(f.upload.isUploading, false);
  assert.equal(f.requests.length, 0);
  f.select(); f.importFiles.value = [];
  f.submitFileForm();
  assert.equal(f.requests.length, 0);
  f.select(); f.submitFileForm();
  assert.equal(f.requests.length, 1);
  assert.equal(f.importLoading.value, true);
  assert.equal(f.upload.isUploading, true);
});

test('没有 ready/raw 文件不能进入 loading；提交中重复点击仅发起一次', () => {
  const f = harness();
  f.select(1, 'success'); f.submitFileForm();
  f.importFiles.value = [{ uid: 2, status: 'ready' }]; f.submitFileForm();
  assert.equal(f.importLoading.value, false);
  const file = f.select(3);
  f.submitFileForm(); f.submitFileForm(); f.handleFileUploadProgress({}, file);
  assert.equal(f.requests.length, 1);
  assert.equal(f.importLoading.value, true);
});

test('取消/关闭/重开：中止在途请求、清空文件和更新选项，迟到回调不能影响新提交', () => {
  const f = harness();
  const oldFile = f.select(1); f.upload.updateSupport = true; f.submitFileForm();
  f.resetImport();
  assert.equal(f.importLoading.value, false);
  assert.equal(f.upload.isUploading, false);
  assert.equal(f.upload.updateSupport, 0);
  assert.deepEqual(f.importFiles.value, []);
  assert.deepEqual(f.counts(), { aborts: 1, clears: 1 });
  const newFile = f.select(2); f.submitFileForm();
  f.handleFileSuccess({ code: 200, msg: '旧响应' }, oldFile);
  f.handleFileError(new Error('late error'), oldFile);
  assert.equal(f.importLoading.value, true);
  assert.equal(f.messages.length, 0);
  assert.equal(f.successes.length, 0);
  f.handleFileSuccess({ code: 200, msg: '成功' }, newFile);
  assert.equal(f.importLoading.value, false);
  assert.equal(f.successes.length, 1);
});

test('成功：复位 loading、清空文件、保留结果展示回调并关闭弹窗', () => {
  const f = harness(), file = f.select();
  f.submitFileForm(); f.handleFileSuccess({ code: 200, msg: '导入成功' }, file);
  assert.equal(f.importLoading.value, false);
  assert.equal(f.upload.isUploading, false);
  assert.deepEqual(f.importFiles.value, []);
  assert.equal(f.upload.open, false);
  assert.deepEqual(f.successes, [{ code: 200, msg: '导入成功' }]);
});

for (const response of [{ code: 500, msg: '文件内容错误' }, { code: 401, msg: '登录已过期' }, null]) {
  test(`业务失败 ${response?.code ?? '空响应'}：提示并解锁，不执行成功回调，可重新选择提交`, () => {
    const f = harness(), file = f.select();
    f.submitFileForm(); f.handleFileSuccess(response, file);
    assert.equal(f.importLoading.value, false);
    assert.equal(f.upload.isUploading, false);
    assert.equal(f.upload.open, true);
    assert.equal(f.successes.length, 0);
    assert.deepEqual(f.messages, [response?.msg || '导入失败，请检查文件后重试']);
    f.select(2); f.submitFileForm();
    assert.equal(f.requests.length, 2);
    assert.equal(f.importLoading.value, true);
  });
}

test('HTTP/网络 on-error 与同步提交异常：均提示并解锁，允许重新选择文件', () => {
  const f = harness(), file = f.select();
  f.submitFileForm(); f.handleFileError(new Error('HTTP 500'), file);
  assert.equal(f.importLoading.value, false);
  assert.equal(f.upload.isUploading, false);
  assert.deepEqual(f.importFiles.value, []);
  assert.equal(f.messages.length, 1);
  f.select(2); f.failSubmit(); f.submitFileForm();
  assert.equal(f.importLoading.value, false);
  assert.equal(f.messages.length, 2);
});

// 使用锁定版本 Element Plus 的真实队列与回调实现，替换网络传输，不需要浏览器或业务接口。
test('真实 Element Plus 队列：v-model 同步、空提交、成功、业务失败、HTTP失败及重开后再次提交', async () => {
  let handlers, flow, serial = 0;
  const messages = [], successes = [], submitted = [], aborted = [];
  const renderer = createRenderer({
    createComment: () => ({}), createText: () => ({}), createElement: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
    parentNode: () => null, nextSibling: () => null,
  });
  const Upload = defineComponent({
    props: ['fileList', 'onSuccess', 'onError', 'onProgress'], emits: ['update:fileList'],
    setup(props) {
      handlers = useHandlers({
        get fileList() { return props.fileList; },
        get onSuccess() { return props.onSuccess; },
        get onError() { return props.onError; },
        get onProgress() { return props.onProgress; },
        onChange() {}, onRemove() {},
      }, ref({ upload: file => submitted.push(file.uid), abort: file => aborted.push(file?.uid) }));
      return () => null;
    },
  });
  const app = renderer.createApp({ setup() {
    flow = useImportUpload({
      upload: reactive({ isUploading: false, updateSupport: 0 }),
      getUploadRef: () => handlers,
      onEmpty: () => messages.push('empty'), onError: () => messages.push('error'),
      onSuccess: response => successes.push(response.code),
    });
    return () => h(Upload, {
      fileList: flow.importFiles.value,
      'onUpdate:fileList': files => { flow.importFiles.value = files; },
      onSuccess: flow.handleFileSuccess, onError: flow.handleFileError,
      onProgress: flow.handleFileUploadProgress,
    });
  } });
  app.mount({});
  const select = async () => {
    const raw = { uid: ++serial, name: 'users.xlsx', size: 10 };
    handlers.handleStart(raw); await nextTick();
    assert.equal(flow.importFiles.value[0].status, 'ready');
    return raw;
  };
  try {
    flow.submitFileForm(); assert.equal(flow.importLoading.value, false);
    const a = await select(); flow.submitFileForm(); flow.submitFileForm();
    assert.deepEqual(submitted, [a.uid]);
    handlers.handleSuccess({ code: 200 }, a); await nextTick();
    assert.equal(flow.importLoading.value, false);
    assert.deepEqual(handlers.uploadFiles.value, []);
    const b = await select(); flow.submitFileForm();
    handlers.handleSuccess({ code: 500 }, b); await nextTick();
    assert.equal(flow.importLoading.value, false);
    assert.deepEqual(handlers.uploadFiles.value, []);
    const c = await select(); flow.submitFileForm();
    // Element Plus 自身会 console.error，使用预期错误作为测试证据。
    handlers.handleError(new Error('expected offline HTTP failure'), c); await nextTick();
    assert.equal(flow.importLoading.value, false);
    const d = await select(); flow.submitFileForm(); flow.resetImport(); await nextTick();
    assert.equal(flow.importLoading.value, false);
    assert.deepEqual(handlers.uploadFiles.value, []);
    assert.ok(aborted.length >= 4);
    handlers.handleSuccess({ code: 200 }, d);
    const e = await select(); flow.submitFileForm();
    handlers.handleSuccess({ code: 200 }, e); await nextTick();
    assert.deepEqual(successes, [200, 200]);
    assert.deepEqual(messages, ['empty', 'error', 'error']);
    assert.deepEqual(submitted, [1, 2, 3, 4, 5]);
    assert.equal(flow.importLoading.value, false);
  } finally { app.unmount(); }
});

for (const path of ['sys/system/user', 'ds/client', 'ds/api', 'ds/apiCat', 'ds/apiLog']) {
  test(`${path}：真实页面绑定相同状态函数、关闭清理与独立导入 loading，SFC可编译`, () => {
    const filename = new URL(`../src/views/${path}/index.vue`, import.meta.url);
    const { descriptor, errors } = parse(readFileSync(filename, 'utf8'));
    assert.deepEqual(errors, []);
    const script = compileScript(descriptor, { id: path, inlineTemplate: false });
    const template = compileTemplate({ source: descriptor.template.content, filename: filename.pathname, id: path });
    assert.deepEqual(template.errors, []);
    const source = descriptor.scriptSetup.content;
    assert.match(source, /function handleImport\(\)\s*\{\s*resetImport\(\);/);
    assert.match(script.content, /useImportUpload\(/);
    const html = descriptor.template.content;
    assert.match(html, /v-model="upload.open"[^>]*@close="resetImport"/);
    assert.match(html, /v-model:file-list="importFiles"/);
    assert.match(html, /:on-error="handleFileError"/);
    assert.match(html, /:loading="importLoading" @click="submitFileForm"/);
    assert.match(html, /:disabled="importLoading"/);
    assert.match(source, /const handleImportSuccess[\s\S]*?upload.open = false;[\s\S]*?proxy.\$alert\([\s\S]*?getList\(\);/);
  });
}
