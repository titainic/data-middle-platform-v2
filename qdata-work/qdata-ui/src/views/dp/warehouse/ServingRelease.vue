<template>
  <section class="serving-release" v-loading="loading">
    <div class="serving-heading"><div><h4>数据服务发布</h4><p>通过质量门禁后同步全部应用产品；对账完成后统一发布，历史版本支持固定分页和回退。</p></div><el-button @click="reload">刷新服务</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="!target" label-width="100px">
      <el-form-item label="Doris连接"><el-select v-model="sourceId" placeholder="选择本项目的Doris连接"><el-option v-for="source in sources" :key="source.id" :value="source.id" :label="source.datasource_name" /></el-select></el-form-item>
      <el-form-item label="服务数据库"><el-input v-model="database" placeholder="例如 serving_iot_db" maxlength="128" /></el-form-item>
      <el-form-item label="副本数量"><el-input-number v-model="replicas" :min="1" :max="3" /><span class="form-help">正式环境建议3副本，需要对应数量的可用节点。</span></el-form-item>
      <el-button :loading="busy" :disabled="!sourceId || !database || !capabilities.servingConfigure" @click="configure">登记服务目标</el-button>
    </el-form>
    <template v-else>
      <p>服务库：{{ target.database_name }} · {{ target.replicas }} 副本</p>
      <el-form label-width="100px"><el-form-item label="通过的评估"><el-select v-model="assessmentId" placeholder="选择本批已通过的质量评估"><el-option v-for="assessment in passed" :key="assessment.id" :value="assessment.id" :label="`${assessment.policy_name} / v${assessment.policy_version} · 尝试${assessment.attempt}`" /></el-select></el-form-item><el-form-item label="服务契约"><el-input-number v-model="contractVersion" :min="1" :max="100" /></el-form-item></el-form>
      <div class="serving-actions"><el-button type="primary" :loading="busy" :disabled="!assessmentId || !capabilities.servingSync" @click="synchronize(false)">同步并对账本批产品</el-button><el-button :disabled="busy || !assessmentId || !capabilities.servingSync" @click="synchronize(true)">新建同步尝试</el-button><el-button @click="scopeOpen=true">应用数据范围</el-button></div>
      <p v-if="!passed.length" class="form-help">本批尚无通过的质量评估，请先完成上方质量门禁。</p>
    </template>
    <el-table :data="releases" border empty-text="尚未同步服务版本">
      <el-table-column label="服务版本" min-width="210"><template #default="{row}"><span>{{ row.id.slice(0,8) }} · 契约 v{{row.contract_version}}</span><el-tag v-if="isCurrent(row)" type="success" size="small">当前发布</el-tag></template></el-table-column>
      <el-table-column label="批次" min-width="150"><template #default="{row}">{{row.batch_id.slice(0,8)}}{{row.batch_id===batchId?' · 本批':''}}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{row}">{{state(row.status)}}</template></el-table-column>
      <el-table-column prop="message" label="说明" min-width="230" />
      <el-table-column label="操作" width="200"><template #default="{row}"><el-button link type="primary" @click="show(row.id)">查看产品</el-button><el-button v-if="['VERIFIED','PUBLISHED'].includes(row.status) && !isCurrent(row)" link type="primary" :disabled="busy || !capabilities.servingPublish" @click="publish(row)">{{row.ever_published?'切换至此版本':'发布全部产品'}}</el-button></template></el-table-column>
    </el-table>
    <el-table v-if="selected" :data="selected.products" border class="product-table"><el-table-column prop="dataset_id" label="数据集" width="100" /><el-table-column prop="table_name" label="Doris产品表" min-width="240" /><el-table-column prop="row_count" label="对账行数" width="110" /><el-table-column prop="status" label="对账状态" width="130" /><el-table-column label="数据服务" min-width="180"><template #default="{row}"><router-link v-if="row.api_id" :to="{path:'/ds/api',query:{id:row.api_id}}">服务 {{row.api_id}} · 查看目录</router-link></template></el-table-column></el-table>
    <el-dialog v-model="scopeOpen" title="应用数据范围" width="560px" append-to-body><el-form label-width="90px"><el-form-item label="应用编号"><el-input v-model="clientId" placeholder="应用管理中的编号" /><el-button :disabled="busy || !clientId" @click="loadScope">读取当前范围</el-button></el-form-item><el-form-item label="地点编码"><el-input v-model="locations" type="textarea" :rows="4" placeholder="每行一个地点编码；留空表示应用可查询全部地点" /></el-form-item><el-form-item label="可见字段"><el-input v-model="allowedColumns" type="textarea" :rows="3" placeholder="每行一个字段名；留空表示全部字段" /></el-form-item><el-form-item label="脱敏字段"><el-input v-model="maskedColumns" type="textarea" :rows="3" placeholder="每行一个字段名；响应中该字段返回空值" /></el-form-item></el-form><p class="form-help">应用仍须在服务目录中获得对应接口授权。设置地点范围后，不含地点字段的产品会拒绝查询。</p><template #footer><el-button @click="scopeOpen=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!capabilities.scopeWrite || !scopeEditable" @click="saveScope">保存范围</el-button></template></el-dialog>
  </section>
</template>
<script setup>
import {ref,watch} from 'vue';
import request from '@/utils/request';
import {useWarehouseCapabilities} from './useCapabilities';
import {ElMessage,ElMessageBox} from 'element-plus';
const props=defineProps({batchId:String,spaceId:[Number,String]});
const {capabilities,loadCapabilities}=useWarehouseCapabilities(()=>props.spaceId);
const scopeEditable=ref(false);
const target=ref(null),sources=ref([]),releases=ref([]),heads=ref([]),passed=ref([]),selected=ref(null),sourceId=ref(null),database=ref('serving_iot_db'),replicas=ref(3),assessmentId=ref(null),contractVersion=ref(1),loading=ref(false),busy=ref(false),error=ref(''),scopeOpen=ref(false),clientId=ref(''),locations=ref(''),allowedColumns=ref(''),maskedColumns=ref('');
const api=(path='',method='get',data)=>request({url:'/dp/warehouse/serving'+path,method,data,timeout:600000,headers:{repeatSubmit:false,hideErrorMessage:true}}).then(response=>response.data);
const state=value=>({SYNCING:'同步中',VERIFIED:'对账通过',FAILED:'同步失败',PUBLISHED:'已发布'}[value]||value);
const isCurrent=row=>heads.value.some(head=>head.release_id===row.id);
async function action(work){busy.value=true;error.value='';try{await work();}catch(failure){error.value=failure.message||String(failure);}finally{busy.value=false;await reload();}}
async function reload(){if(!props.spaceId)return;loading.value=true;try{if(!(await loadCapabilities()) || !capabilities.value.servingRead)return;const data=await api(`?spaceId=${props.spaceId}`);target.value=data.target[0]||null;sources.value=data.sources;releases.value=data.releases;heads.value=data.heads;const response=capabilities.value.qualityRead?await request({url:`/dp/warehouse/quality/assessments?batchId=${props.batchId}`}):{data:[]};passed.value=response.data.filter(item=>item.status==='PASSED');if(!passed.value.some(item=>item.id===assessmentId.value))assessmentId.value=passed.value[0]?.id||null;if(selected.value)selected.value=await api(`/releases/${selected.value.id}`);}catch(failure){error.value=failure.message||String(failure);}finally{loading.value=false;}}
async function configure(){await action(async()=>{await api('/target','post',{spaceId:props.spaceId,datasourceId:sourceId.value,database:database.value,replicas:replicas.value});ElMessage.success('服务目标已登记');});}
async function synchronize(newAttempt){await action(async()=>{selected.value=await api('/releases','post',{batchId:props.batchId,assessmentId:assessmentId.value,contractVersion:contractVersion.value,newAttempt});ElMessage.success('全部产品对账完成，可发布服务版本');});}
async function show(id){await action(async()=>{selected.value=await api(`/releases/${id}`);});}
async function publish(row){try{await ElMessageBox.confirm(`将契约 v${row.contract_version} 的全部产品切换至 ${row.id.slice(0,8)}。已固定旧版本的分页请求继续读取旧版本。`,'发布服务版本',{confirmButtonText:'确认发布',cancelButtonText:'取消'});}catch{return;}await action(async()=>{selected.value=await api(`/releases/${row.id}/publish`,'post');ElMessage.success('全部产品已切换至所选版本');});}
const lines=value=>value.split('\n').map(item=>item.trim()).filter(Boolean);
async function loadScope(){await action(async()=>{if(!/^[0-9]+$/.test(clientId.value))throw new Error('请填写有效的应用编号');const requestedClient=clientId.value;scopeEditable.value=false;const scope=await api(`/scope?spaceId=${props.spaceId}&clientId=${requestedClient}`);if(requestedClient!==clientId.value)return;scopeEditable.value=scope.canEdit===true;locations.value=scope.locations.join('\n');allowedColumns.value=scope.columns.join('\n');maskedColumns.value=scope.maskedColumns.join('\n');});}
async function saveScope(){await action(async()=>{if(!/^[0-9]+$/.test(clientId.value))throw new Error('请填写有效的应用编号');await api('/scope','put',{spaceId:props.spaceId,clientId:clientId.value,locations:lines(locations.value),columns:lines(allowedColumns.value),maskedColumns:lines(maskedColumns.value)});scopeOpen.value=false;ElMessage.success('应用数据范围已更新');});}
watch(clientId,()=>{scopeEditable.value=false;});
watch(()=>[props.spaceId,props.batchId],async()=>{selected.value=null;await reload();},{immediate:true});
</script>
<style scoped>
.serving-release{margin-top:28px;padding-top:20px;border-top:1px solid #e5e8ef}.serving-heading{display:flex;justify-content:space-between;gap:20px}.serving-heading h4{margin:0;font-size:15px}.serving-heading p,.form-help{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.serving-actions{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:16px}.serving-release :deep(.el-select){min-width:330px}.serving-release :deep(.el-input){max-width:440px}.form-help{margin-left:12px}.product-table{margin-top:16px}.serving-release :deep(.el-tag){margin-left:8px}@media(max-width:1000px){.serving-heading{flex-wrap:wrap}.serving-release :deep(.el-select){min-width:0;width:100%}}
</style>
