<template>
  <section class="quality-assessment" v-loading="loading">
    <div class="quality-title"><div><h4>质量评估与发布门禁</h4><p>策略保存后固定规则版本；关键规则失败时，本次结果不能进入服务发布。</p></div><el-button @click="reload">刷新评估</el-button></div>
    <el-form label-width="100px">
      <el-form-item label="质量策略"><el-select v-model="policyId" placeholder="选择固定策略版本"><el-option v-for="policy in policies" :key="policy.id" :value="policy.id" :label="`${policy.name} / v${policy.version}`" /></el-select></el-form-item>
      <div class="quality-actions"><el-button type="primary" :loading="busy" :disabled="!policyId || !capabilities.qualityWrite" @click="prepare(false)">准备本批评估</el-button><el-button :disabled="!policyId || busy || !capabilities.qualityWrite" @click="prepare(true)">新建评估尝试</el-button><el-button :disabled="!capabilities.qualityWrite" @click="policyOpen=true">新建策略版本</el-button></div>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="assessments" border empty-text="本批尚未形成质量评估证据">
      <el-table-column label="策略版本" min-width="180"><template #default="{row}">{{ row.policy_name }} / v{{ row.policy_version }}</template></el-table-column>
      <el-table-column prop="attempt" label="尝试" width="70" />
      <el-table-column label="门禁状态" width="140"><template #default="{row}"><el-tag :type="row.status==='PASSED'?'success':['FAILED','UNKNOWN'].includes(row.status)?'danger':'info'">{{ state(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="message" label="说明" min-width="280" />
      <el-table-column label="操作" width="150"><template #default="{row}"><el-button link type="primary" @click="show(row.id)">查看</el-button><el-button v-if="row.status==='READY'" link type="primary" :disabled="busy || !capabilities.qualityRun" @click="start(row.id)">执行</el-button></template></el-table-column>
    </el-table>
    <el-table v-if="selected" :data="selected.tasks" border style="margin-top:16px">
      <el-table-column prop="dataset_id" label="数据集" width="85" />
      <el-table-column label="原生质量任务" min-width="140"><template #default="{row}">任务 {{ row.task_id }}</template></el-table-column>
      <el-table-column label="执行报告" min-width="140"><template #default="{row}"><router-link v-if="row.log_id" :to="{path:'/da/quality/qualityTaskLog/detail',query:{id:row.log_id}}">查看报告 {{ row.log_id }}</router-link><span v-else>待生成</span></template></el-table-column>
      <el-table-column label="结果" width="140"><template #default="{row}">{{ state(row.status) }}</template></el-table-column>
      <el-table-column prop="row_count" label="固定数据行数" width="130" />
      <el-table-column prop="rule_hits" label="规则命中次数" width="130" />
      <el-table-column prop="blocking_hits" label="关键规则命中" width="130" />
      <el-table-column prop="message" label="说明" min-width="220" />
    </el-table>
    <p v-if="selected" class="quality-note">规则命中次数按每条规则累加，同一记录可以被多条规则命中；忽略或暂缓问题不会改写本次评估证据。</p>
    <el-dialog v-model="policyOpen" title="新建质量策略版本" width="900px" append-to-body>
      <el-form label-width="100px"><el-form-item label="策略名称" required><el-input v-model="policyName" maxlength="128" placeholder="同名保存会创建新的策略版本" /></el-form-item></el-form>
      <el-table :data="bindings" border>
        <el-table-column prop="datasetId" label="数据集" width="85" />
        <el-table-column label="检查模板" min-width="240"><template #default="{row}"><el-select v-model="row.taskId" @change="selectTemplate(row)"><el-option v-for="template in templatesFor(row.datasetId)" :key="template.id" :value="template.id" :label="template.task_name" /></el-select></template></el-table-column>
        <el-table-column label="关键阻断规则" min-width="320"><template #default="{row}"><el-checkbox-group v-model="row.blockingRuleIds"><el-checkbox v-for="rule in selectedTemplate(row)?.rules || []" :key="rule.id" :label="rule.id">{{ rule.name }}</el-checkbox></el-checkbox-group></template></el-table-column>
      </el-table>
      <p class="quality-note">每个数据集至少选择一条关键规则。其余启用规则仍执行并记录问题，但不单独阻断发布。</p>
      <template #footer><el-button @click="policyOpen=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!capabilities.qualityWrite" @click="savePolicy">保存固定版本</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup>
import {ref,watch,onBeforeUnmount} from 'vue';
import request from '@/utils/request';
import {useWarehouseCapabilities} from './useCapabilities';
import {ElMessage} from 'element-plus';
const props=defineProps({batchId:String,spaceId:[Number,String]});
const {capabilities,loadCapabilities}=useWarehouseCapabilities(()=>props.spaceId);
const policies=ref([]),templates=ref([]),assessments=ref([]),bindings=ref([]),selected=ref(null),policyId=ref(null),policyName=ref(''),policyOpen=ref(false),loading=ref(false),busy=ref(false),error=ref('');
const api=(path,method='get',data)=>request({url:'/dp/warehouse/quality'+path,method,data,timeout:180000,headers:{repeatSubmit:false,hideErrorMessage:true}}).then(response=>response.data);
const state=value=>({READY:'待执行',RUNNING:'评估中',EXECUTED:'检查完成',PASSED:'门禁通过',FAILED:'门禁未通过',UNKNOWN:'需要核对'}[value]||value);
const templatesFor=id=>templates.value.filter(template=>String(template.dataset_id)===String(id));
const selectedTemplate=row=>templates.value.find(template=>String(template.id)===String(row.taskId));
function selectTemplate(row){row.blockingRuleIds=(selectedTemplate(row)?.rules||[]).filter(rule=>String(rule.name).includes('代码合法性') || rule.ruleType==='NUMERIC_RANGE_VALIDATION').map(rule=>rule.id);}
async function action(work){busy.value=true;error.value='';try{await work();}catch(failure){error.value=failure.message||String(failure);}finally{busy.value=false;}}
async function reload(){if(!props.batchId || !props.spaceId)return;loading.value=true;try{if(!(await loadCapabilities()) || !capabilities.value.qualityRead)return;policies.value=await api(`/policies?spaceId=${props.spaceId}`);templates.value=await api(`/templates?spaceId=${props.spaceId}`);assessments.value=await api(`/assessments?batchId=${props.batchId}`);if(selected.value)selected.value=await api(`/assessments/${selected.value.id}`);if(!policyId.value && policies.value.length)policyId.value=policies.value[0].id;if(!bindings.value.length){bindings.value=[...new Set(templates.value.map(template=>template.dataset_id))].map(datasetId=>({datasetId,taskId:templatesFor(datasetId)[0].id,blockingRuleIds:[]}));bindings.value.forEach(selectTemplate);}}catch(failure){error.value=failure.message||String(failure);}finally{loading.value=false;}}
async function show(id){selected.value=await api(`/assessments/${id}`);}
async function prepare(newAttempt){await action(async()=>{selected.value=await api('/assessments','post',{batchId:props.batchId,policyId:policyId.value,newAttempt});await reload();});}
async function start(id){await action(async()=>{selected.value=await api(`/assessments/${id}/start`,'post');await reload();ElMessage.success('质量评估已提交，请查看门禁最终状态');});}
async function savePolicy(){await action(async()=>{if(!policyName.value.trim() || bindings.value.some(binding=>!binding.blockingRuleIds.length))throw new Error('请填写策略名称，并为每个数据集选择关键规则');const policy=await api('/policies','post',{spaceId:props.spaceId,name:policyName.value.trim(),bindings:bindings.value});policyId.value=policy.id;policyOpen.value=false;await reload();ElMessage.success('已保存新的质量策略版本');});}
watch(()=>props.batchId,async()=>{selected.value=null;await reload();},{immediate:true});
let polling=false;const timer=setInterval(async()=>{if(polling || busy.value || !assessments.value.some(row=>row.status==='RUNNING'))return;polling=true;try{assessments.value=await api(`/assessments?batchId=${props.batchId}`);if(selected.value)selected.value=await api(`/assessments/${selected.value.id}`);}catch(failure){error.value=failure.message||String(failure);}finally{polling=false;}},5000);
onBeforeUnmount(()=>clearInterval(timer));
</script>
<style scoped>
.quality-assessment{margin-top:28px;padding-top:20px;border-top:1px solid #e5e8ef}.quality-title{display:flex;justify-content:space-between;gap:20px}.quality-title h4{margin:0;font-size:15px}.quality-title p,.quality-note{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}.quality-actions{display:flex;gap:10px;margin-bottom:16px}.quality-assessment :deep(.el-select){min-width:260px}.quality-assessment a{color:var(--el-color-primary)}
</style>
