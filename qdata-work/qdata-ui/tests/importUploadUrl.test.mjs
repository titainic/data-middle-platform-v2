import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { File } from 'node:buffer';
import { reactive } from 'vue';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';
import { ajaxUpload } from 'element-plus/es/components/upload/src/ajax.mjs';
import JSZip from 'jszip';
import { joinApiPath } from '../src/utils/apiUrl.js';
import useImportUpload from '../src/composables/useImportUpload.js';

const endpoint = '/system/user/importData';
const origin = 'http://10.131.135.95:8080';
const bases = [
  ['/', endpoint],
  ['/prod-api', `/prod-api${endpoint}`],
  ['/prod-api/', `/prod-api${endpoint}`],
  ['', endpoint],
  [undefined, endpoint],
  ['/gateway/api///', `/gateway/api${endpoint}`],
  ['prod-api/', `prod-api${endpoint}`],
  ['https://api.example.test:8443/gateway/', `https://api.example.test:8443/gateway${endpoint}`],
  ['http://localhost:8080/', `http://localhost:8080${endpoint}`],
  ['//api.example.test/prod-api/', `//api.example.test/prod-api${endpoint}`],
];

for (const [base, expected] of bases) {
  test(`API基址 ${String(base)}：保留合法基址语义并消除边界重复斜杠`, () => {
    assert.equal(joinApiPath(base, endpoint), expected);
    assert.equal(joinApiPath(base, endpoint.slice(1)), expected);
  });
}

test('根路径请求留在当前主机，/prod-api及尾斜杠保留代理前缀和updateSupport', () => {
  // 原缺陷的解析结果是主机 system；修复后必须留在95且不丢代理前缀。
  assert.equal(new URL(`/${endpoint}`, origin).hostname, 'system');
  for (const base of ['/', '/prod-api', '/prod-api/']) {
    const url = new URL(`${joinApiPath(base, endpoint)}?updateSupport=0`, `${origin}/sys/user`);
    assert.equal(url.origin, origin);
    assert.equal(url.pathname, base === '/' ? endpoint : `/prod-api${endpoint}`);
    assert.equal(url.searchParams.get('updateSupport'), '0');
  }
});

function vueFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, directory);
    return entry.isDirectory() ? vueFiles(path) : entry.name.endsWith('.vue') ? [path] : [];
  });
}

test('全部29处importData配置执行归一化表达式且SFC可编译，无遗漏的相同直拼', () => {
  let count = 0;
  for (const filename of vueFiles(new URL('../src/views/', import.meta.url))) {
    const source = readFileSync(filename, 'utf8');
    assert.doesNotMatch(source, /VITE_APP_BASE_API\s*\+\s*['"][^'"]*importData/);
    if (!source.includes('importData')) continue;
    const expressions = [...source.matchAll(/url:\s*(joinApiPath\(import\.meta\.env\.VITE_APP_BASE_API,\s*['"]([^'"]*importData)['"]\))/g)];
    for (const [, expression, path] of expressions) {
      count++;
      assert.match(source, /import\s*\{\s*joinApiPath\s*\}\s*from\s*['"]@\/utils\/apiUrl.js['"]/);
      for (const base of ['/', '/prod-api', '/prod-api/']) {
        const evaluate = new Function('joinApiPath', 'base', `return ${expression.replace('import.meta.env.VITE_APP_BASE_API', 'base')};`);
        const action = evaluate(joinApiPath, base);
        assert.equal(action, base === '/' ? path : `/prod-api${path}`);
        assert.equal(new URL(action, origin).origin, origin);
      }
      const { descriptor, errors } = parse(source);
      assert.deepEqual(errors, []);
      if (descriptor.scriptSetup) compileScript(descriptor, { id: filename.pathname });
      assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: filename.pathname, id: filename.pathname }).errors, []);
    }
  }
  assert.equal(count, 29);
});

// 使用真实File/FormData及锁定版本的Element Plus ajaxUpload；只替换XHR，不发业务请求。
async function excelFile() {
  const zip = new JSZip();
  zip.file('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>');
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>');
  zip.file('xl/workbook.xml', '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="users" sheetId="1" r:id="rId1"/></sheets></workbook>');
  zip.file('xl/_rels/workbook.xml.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>');
  zip.file('xl/worksheets/sheet1.xml', '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>offline_user</t></is></c></row></sheetData></worksheet>');
  return new File([await zip.generateAsync({ type: 'uint8array' })], 'users.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
}

for (const base of ['/', '/prod-api', '/prod-api/']) {
  test(`非空Excel ${base}：真实multipart传输回调覆盖网络/HTTP/业务失败及成功，复用BIN-74状态恢复`, async () => {
    const originalXHR = globalThis.XMLHttpRequest;
    const requests = [], errors = [], results = [];
    class OfflineXHR {
      constructor() { this.events = {}; this.headers = {}; this.upload = { addEventListener() {} }; requests.push(this); }
      addEventListener(name, handler) { this.events[name] = handler; }
      open(method, action) { this.method = method; this.action = action; }
      setRequestHeader(name, value) { this.headers[name] = value; }
      send(body) { this.body = body; }
      abort() {}
      respond(status, body) { this.status = status; this.responseText = JSON.stringify(body); this.events.load(); }
    }
    globalThis.XMLHttpRequest = OfflineXHR;
    try {
      const upload = reactive({ isUploading: false, open: true, updateSupport: 0 });
      const raw = await excelFile();
      let current, serial = 0;
      const uploadRef = {
        abort() {}, clearFiles() {},
        submit() {
          const file = current;
          ajaxUpload({
            action: `${joinApiPath(base, endpoint)}?updateSupport=${upload.updateSupport}`,
            method: 'post', filename: 'file', file: file.raw,
            headers: { Authorization: 'Bearer offline-placeholder' },
            onProgress: event => flow.handleFileUploadProgress(event, file),
            onError: error => flow.handleFileError(error, file),
            onSuccess: response => flow.handleFileSuccess(response, file),
          });
        },
      };
      const flow = useImportUpload({
        upload, getUploadRef: () => uploadRef,
        onEmpty: () => assert.fail('文件不应为空'),
        onError: message => errors.push(message || '导入失败'),
        onSuccess: response => { results.push(response); upload.open = false; },
      });
      function submit() {
        current = { uid: ++serial, status: 'ready', raw };
        flow.importFiles.value = [current];
        flow.submitFileForm(); flow.submitFileForm();
        assert.equal(requests.length, serial);
        assert.equal(flow.importLoading.value, true);
        assert.equal(upload.isUploading, true);
        const xhr = requests.at(-1);
        assert.equal(new URL(xhr.action, origin).origin, origin);
        assert.equal(xhr.action, `${base === '/' ? '' : '/prod-api'}${endpoint}?updateSupport=0`);
        assert.equal(xhr.method, 'post');
        assert.equal(xhr.headers.Authorization, 'Bearer offline-placeholder');
        const part = xhr.body.get('file');
        assert.equal(part.name, 'users.xlsx');
        assert.ok(part.size > 0);
        return xhr;
      }
      function unlocked() {
        assert.equal(flow.importLoading.value, false);
        assert.equal(upload.isUploading, false);
        assert.deepEqual(flow.importFiles.value, []);
      }
      submit().events.error(); unlocked();
      assert.equal(upload.open, true);
      submit().respond(503, { error: 'offline HTTP error' }); unlocked();
      submit().respond(200, { code: 500, msg: '文件内容错误' }); unlocked();
      assert.deepEqual(errors, ['导入失败', '导入失败', '文件内容错误']);
      assert.equal(results.length, 0);
      submit().respond(200, { code: 200, msg: '导入结果' }); unlocked();
      assert.deepEqual(results, [{ code: 200, msg: '导入结果' }]);
      assert.equal(upload.open, false);
    } finally { globalThis.XMLHttpRequest = originalXHR; }
  });
}
