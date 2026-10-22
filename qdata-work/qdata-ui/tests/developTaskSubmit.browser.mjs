// 实际Vue弹窗、Element Plus和父级处理函数；API/宿主为本地夹具，不连接95。
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import { parse } from '@vue/compiler-sfc';

const root = fileURLToPath(new URL('..', import.meta.url));
const evidence = path.resolve(root, '../../evidence/BIN-199');
mkdirSync(evidence, { recursive: true });
const source = parse(readFileSync(path.join(root,'src/views/dpp/task/developTask/index.vue'),'utf8')).descriptor.scriptSetup.content;
const handlers = source.slice(source.indexOf('const submitTask ='), source.indexOf('const leftWidth ='));
const api = `export const getTaskCapabilities=async()=>({data:{canUseDolphin:false}});
export const dppEtlSqlTemp=async()=>({data:{rows:[],total:0}});
export const getNodeUniqueKey=async()=>fetch('/fixture-api/node-key').then(r=>r.json());
export const listDaDatasourceNoKafkaByProjectCode=async()=>({data:[]});
export const checkApi=async()=>({data:true});`;
const fixture = `import {createApp,ref,h,onMounted,nextTick} from 'vue';
import {createRouter,createWebHistory} from 'vue-router';
import {createI18n} from 'vue-i18n';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import Dialog from '/src/views/dpp/task/developTask/add/add.vue';
import dpp from '/src/locales/zh-CN/dpp/index.js';
import common from '/src/locales/zh-CN/common/index.js';
const router=createRouter({history:createWebHistory(),routes:[{path:'/',component:{render:()=>null}},{path:'/dpp/task/developTask/edit',component:{render:()=>null}}]});
const notices=ref([]),visible=ref(false),dialogRef=ref(),saveLoading=ref(false);
const userStore={projectId:'19',projectCode:'9007199254740999'};
const td=(_key,fallback)=>fallback;
const proxy={$modal:{msgSuccess:message=>notices.value.push(message),msgWarning:message=>notices.value.push(message)}};
const handleQuery=()=>{};
const routeTo=(link,row)=>router.push({path:link,query:{id:row.id}});
const createEtlTaskFront=payload=>fetch('/fixture-api/createEtlTaskFront',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}).then(r=>r.json());
${handlers}
const app=createApp({setup(){onMounted(()=>nextTick(()=>visible.value=true));window.fixture={get dialog(){return dialogRef.value.$.setupState},notices,visible};return()=>h('main',[
h('h2','BIN-199 本地研发验证（接口替身）'),h('div',{id:'notices'},notices.value.join('；')),
h(Dialog,{ref:dialogRef,visible:visible.value,'onUpdate:visible':value=>visible.value=value,title:'新增任务',submitTask,
data:{name:'fix_bin199_browser',catCode:'cat1',personCharge:1,crontab:'0 * * * * ?',scheduler:'QUARTZ',typaCode:'DM'},
deptOptions:[{id:'1',code:'cat1',name:'研发测试类目'}],userList:[{userId:1,nickName:'研发测试用户'}]})]);}});
app.config.globalProperties.useDict=()=>({dpp_etl_task_status:ref([{label:'下线',value:'0'}])});
app.config.globalProperties.resetForm=()=>{};
app.config.globalProperties.$modal=proxy.$modal;
app.config.globalProperties.labelPosition='right';
app.component('pagination',{render:()=>null});
app.use(router).use(createI18n({legacy:false,locale:'zh-CN',messages:{'zh-CN':{dpp,common}},missingWarn:false,fallbackWarn:false})).use(ElementPlus).mount('#app');`;
const stubs = new Map([
    ['@/api/dpp/task/index.js', api], ['@/api/da/dataSource/dataSource', api], ['@/api/ds/api/api.js', api],
    ['@/store/system/user', `export default()=>({projectId:'19',projectCode:'9007199254740999'});`],
    ['@/views/dpp/task/developTask/data', `export const treeData=[{id:2,label:'DM',value:'DM',children:[]}];`],
    ['@/components/SqlEditor/editorShow/index.vue', `export default {render:()=>null};`],
    ['@/components/Crontab/index.vue', `export default {render:()=>null};`],
]);
const stubIds = new Map([...stubs.keys()].map((key,index)=>[key,`\0bin199-${index}.js`]));
const server = await createServer({root,configFile:false,optimizeDeps:{entries:[],include:['vue','element-plus','vue-router','vue-i18n']},server:{host:'127.0.0.1',port:5199,strictPort:true},plugins:[{
    name:'bin199-local-fixture',enforce:'pre',
    resolveId(id){if(stubIds.has(id))return stubIds.get(id);if(id==='/fixture.js')return '\0fixture:main.js';if(id.startsWith('@/')){const file=path.join(root,'src',id.slice(2));return existsSync(file)?file:file+'.js';}},
    load(id){if(id==='\0fixture:main.js')return fixture;for(const [key,value] of stubIds)if(value===id)return stubs.get(key);},
    configureServer(s){s.middlewares.use((req,res,next)=>{if(req.url==='/'||req.url.startsWith('/dpp/task/developTask/edit')){res.setHeader('Content-Type','text/html');res.end('<div id="app"></div><script type="module" src="/fixture.js"></script>');}else next();});},
},vue(),AutoImport({imports:['vue'],dts:false})]});
const { chromium } = await import(process.env.QDATA_PLAYWRIGHT_MODULE ? pathToFileURL(process.env.QDATA_PLAYWRIGHT_MODULE).href : 'playwright');
let browser;
const results = [], errors = [], warnings = [];
const deferred = () => { let resolve; const promise = new Promise(yes=>{resolve=yes;}); return {promise,resolve}; };
try {
    await server.listen();
    browser = await chromium.launch({headless:true,executablePath:process.env.QDATA_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
    const page = await browser.newPage({viewport:{width:1440,height:1080}});
    page.setDefaultTimeout(15000);
    page.on('pageerror',error=>errors.push(error.message));
    page.on('response',response=>{if(response.status()>=400)console.error('HTTP',response.status(),response.url());});
    page.on('console',message=>{if(['error','warning'].includes(message.type()))warnings.push(message.text().split('\n')[0]);});
    const origin = `http://127.0.0.1:${server.httpServer.address().port}`;
    let keys=0,saves=0,keyGate=deferred(),saveGate=deferred(),response={code:500,msg:'任务名称已存在（本地失败夹具）'},abort=false;
    let keySeen=deferred(),saveSeen=deferred();
    await page.route('**/fixture-api/node-key',async route=>{keys++;keySeen.resolve();await keyGate.promise;await route.fulfill({json:{data:'9007199254740998'}});});
    await page.route('**/fixture-api/createEtlTaskFront',async route=>{saves++;saveSeen.resolve();await saveGate.promise;if(abort)await route.abort('failed');else await route.fulfill({json:response});});
    await page.goto(origin);
    const only=page.getByRole('button',{name:'仅保存',exact:true});
    const configure=page.getByRole('button',{name:'保存并配置流程',exact:true});
    await only.waitFor();
    await page.evaluate(()=>{
        const form=window.fixture.dialog.daDiscoveryTaskRef,validate=form.validate;
        window.validationCalls=0;
        const gate=new Promise(resolve=>{window.releaseValidation=resolve;});
        form.validate=async(...args)=>{window.validationCalls++;await gate;return validate(...args);};
    });
    // 同一事件循环三次点击事件，覆盖DOM禁用尚未刷新的竞态。
    await only.evaluate(button=>{for(let i=0;i<3;i++)button.dispatchEvent(new MouseEvent('click',{bubbles:true}));});
    assert.equal(await page.evaluate(()=>window.validationCalls),1);
    assert.equal(await only.isDisabled(),true);assert.equal(await configure.isDisabled(),true);assert.equal(saves,0);
    await page.screenshot({path:path.join(evidence,'validation-loading.png'),fullPage:true});
    await page.evaluate(()=>window.releaseValidation());await keySeen.promise;
    assert.equal(keys,1);
    keyGate.resolve();await saveSeen.promise;
    assert.equal(saves,1);assert.equal(await only.isDisabled(),true);assert.equal(await configure.isDisabled(),true);
    await only.evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})));
    await configure.evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})));
    assert.equal(saves,1);
    await page.screenshot({path:path.join(evidence,'network-loading.png'),fullPage:true});
    saveGate.resolve();
    await page.waitForFunction(()=>!document.querySelector('.el-dialog__footer button')?.disabled);
    assert.equal(saves,1);assert.equal(await only.isEnabled(),true);assert.equal(await configure.isEnabled(),true);
    assert.equal(await page.getByRole('textbox').first().inputValue(),'fix_bin199_browser');
    await page.screenshot({path:path.join(evidence,'failure-recovered.png'),fullPage:true});
    results.push({scenario:'校验/编码/网络等待期三连点与交替点击',keyRequests:keys,saveRequests:saves,buttonsDisabled:true,failureRecovered:true});
    response={code:200,data:{id:'9007199254740997'}};
    await configure.click();await page.waitForURL('**/dpp/task/developTask/edit?id=9007199254740997');
    await page.waitForFunction(()=>!window.fixture.visible.value);assert.equal(saves,2);
    results.push({scenario:'失败后保存并完善重试',newSaveRequests:1,url:page.url(),dialogClosed:true});
    keys=0;saves=0;abort=true;
    await page.goto(origin);await only.waitFor();
    await page.getByRole('textbox').first().fill('');await only.click();
    await page.getByText('请输入任务名称',{exact:true}).waitFor();
    assert.equal(saves,0);assert.equal(await only.isEnabled(),true);
    await page.getByRole('textbox').first().fill('fix_bin199_network');await only.click();
    await page.waitForFunction(()=>window.fixture.notices.value.some(message=>message.includes('fetch')));
    assert.equal(saves,1);assert.equal(await only.isEnabled(),true);assert.equal(await configure.isEnabled(),true);
    await page.screenshot({path:path.join(evidence,'network-error-recovered.png'),fullPage:true});
    abort=false;await only.click();await page.waitForFunction(()=>!window.fixture.visible.value);assert.equal(saves,2);
    results.push({scenario:'空名称校验/网络中断/仅保存重试',invalidRequests:0,failedRequests:1,retryRequests:1,dialogClosed:true});
    keys=0;saves=0;saveGate=deferred();saveSeen=deferred();
    await page.goto(origin);await only.waitFor();
    await only.click({clickCount:3,delay:10});await saveSeen.promise;
    assert.equal(saves,1);assert.equal(keys,1);
    assert.equal(await only.isDisabled(),true);assert.equal(await configure.isDisabled(),true);
    await page.screenshot({path:path.join(evidence,'mouse-triple-click.png'),fullPage:true});
    saveGate.resolve();await page.waitForFunction(()=>!window.fixture.visible.value);
    assert.equal(saves,1);
    results.push({scenario:'真实鼠标事件有效表单三连点',clickCount:3,keyRequests:keys,saveRequests:saves,dialogClosed:true});
    assert.deepEqual(errors,[]);
} finally {
    writeFileSync(path.join(evidence,'browser-results.json'),JSON.stringify({scope:'本地实际Vue弹窗+Element Plus；API及路由宿主为夹具，非95运行态',results,pageErrors:errors,consoleWarnings:warnings},null,2));
    if(browser)await browser.close();server.httpServer?.closeAllConnections();await server.close();
}
console.log(JSON.stringify(results,null,2));
