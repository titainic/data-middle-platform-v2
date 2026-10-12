<template>
  <div class="app-container managed-task" v-loading="loading">
    <header><div><h2>{{task.name}}</h2><p>由数仓批次生成的固定版本工作流，包含 {{rows.length}} 个节点。需要修改规则或输入时，请创建新批次。</p></div><div><el-button @click="router.push('/dpp/task/developTask')">返回数据开发</el-button><el-button type="primary" plain @click="openBatch">查看数仓批次</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-descriptions v-if="batch" :column="3" border class="summary">
      <el-descriptions-item label="批次状态">{{status(batch.status)}}</el-descriptions-item>
      <el-descriptions-item label="业务窗口">{{String(batch.window_start).slice(0,10)}} 至 {{String(batch.window_end).slice(0,10)}}</el-descriptions-item>
      <el-descriptions-item label="发布配置版本">{{batch.task_version}}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="rows" border>
      <el-table-column prop="order" label="顺序" width="65" />
      <el-table-column prop="name" label="开发节点" min-width="270" show-overflow-tooltip />
      <el-table-column prop="database" label="实际数据库" width="150" />
      <el-table-column label="结果" width="120"><template #default="{row}"><el-tag :type="row.state==='SUCCESS'?'success':['FAILED','UNKNOWN'].includes(row.state)?'danger':'info'">{{status(row.state)}}</el-tag></template></el-table-column>
      <el-table-column prop="count" label="结果行数" width="105" align="right" />
      <el-table-column label="固定版本与执行证据" min-width="245"><template #default="{row}"><el-button link type="primary" @click="router.push({path:'/dm/model/warehouse',query:{modelId:row.modelId}})">模型版本</el-button><el-button link type="primary" @click="showSql(row)">查看 SQL</el-button><el-button link type="primary" :disabled="!['SUCCESS','FAILED','UNKNOWN'].includes(row.state)" @click="showData(row)">查看数据</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="sqlVisible" title="固定版本与实际执行 SQL" width="85%"><pre>{{sql}}</pre></el-dialog>
    <el-dialog v-model="dataVisible" title="当前物理版本数据（前50行）" width="85%"><el-table :data="dataRows" border max-height="500"><el-table-column v-for="key in Object.keys(dataRows[0]||{})" :key="key" :prop="key" :label="key" min-width="150" /></el-table></el-dialog>
  </div>
</template>

<script setup>
import {computed,onBeforeUnmount,ref,watch} from 'vue';
import {useRouter} from 'vue-router';
import request from '@/utils/request';
const props=defineProps({task:{type:Object,required:true}}),router=useRouter();
const loading=ref(false),error=ref(''),batch=ref(null),sql=ref(''),sqlVisible=ref(false),dataRows=ref([]),dataVisible=ref(false);
const batchId=computed(()=>JSON.parse(props.task.draftJson||'{}').warehouseBatchId);
const api=path=>request({url:`/dp/warehouse${path}`,headers:{hideErrorMessage:true}}).then(r=>r.data);
const status=value=>({READY:'待执行',QUEUED:'等待上游',RUNNING:'执行中',SUCCESS:'计算完成',FAILED:'失败',BLOCKED:'上游阻塞',UNKNOWN:'需要核对',NO_DATA:'无有效数据'}[value]||value||'待执行');
const rows=computed(()=>(props.task.taskDefinitionList||[]).map(node=>{const p=node.taskParams||JSON.parse(node.parameters||'{}');const step=batch.value?.steps.find(s=>String(s.node_code)===String(node.code));return{name:node.name,order:Number(p.warehouseOrdinal)+1,modelId:p.warehouseModelId,database:p.datasources?.databaseName,state:step?.status,count:step?.row_count,sql:p.sql};}).sort((a,b)=>a.order-b.order));
async function refresh(initial=false){if(!batchId.value)return;if(initial)loading.value=true;try{batch.value=await api(`/batches/${batchId.value}`);}catch(e){error.value=e.message||String(e);}finally{loading.value=false;}}
function openBatch(){router.push({path:'/dm/model/warehouse',query:{tab:'batches',batchId:batchId.value}});}
async function showSql(row){try{const logs=await api(`/logs?modelId=${row.modelId}`);sql.value=logs.find(r=>r.batch_id===batchId.value)?.sql_text||row.sql;sqlVisible.value=true;}catch(e){error.value=e.message||String(e);}}
async function showData(row){try{dataRows.value=await api(`/models/${row.modelId}/data`);dataVisible.value=true;}catch(e){error.value=e.message||String(e);}}
watch(batchId,()=>refresh(true),{immediate:true});let polling=false;
const timer=setInterval(async()=>{if(polling||batch.value?.status!=='RUNNING')return;polling=true;try{await refresh();}finally{polling=false;}},4000);
onBeforeUnmount(()=>clearInterval(timer));
</script>
<style scoped>
.managed-task header{display:flex;justify-content:space-between;gap:20px;align-items:flex-start}.managed-task h2{margin:0;font-size:18px;font-weight:500}.managed-task p{font-size:13px;color:#606f80;line-height:1.7}.summary{margin:20px 0}.managed-task pre{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.65;font-size:13px}@media(max-width:900px){.managed-task header{flex-direction:column}}
</style>
