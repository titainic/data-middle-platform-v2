<template>
  <section class="batch-panel" v-loading="loading">
    <div class="batch-heading"><div><h3>持续批次与数据开发</h3><p>选择原始接入结果和业务日期，生成固定版本的开发任务。每次尝试保留独立结果，重复输入复用已有批次。</p></div><el-button @click="reload" :disabled="busy">刷新批次</el-button></div>
    <el-form label-width="100px" @submit.prevent>
      <el-form-item label="数仓空间"><el-select v-model="spaceId" placeholder="选择数仓空间" @change="changeSpace"><el-option v-for="space in spaces" :key="space.id" :value="space.id" :label="`${space.name} / ${space.environmentCode}`" /></el-select></el-form-item>
      <div class="batch-form-grid">
        <el-form-item label="批次名称" required><el-input v-model="name" maxlength="128" placeholder="例如：环境监测一月处理" /></el-form-item>
        <el-form-item label="业务日期" required><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" /></el-form-item>
        <el-form-item label="调度器" required><el-select v-model="scheduler"><el-option label="Quartz（本地调度）" value="QUARTZ" /><el-option label="DolphinScheduler（集群触发）" value="DOLPHINSCHEDULER" /></el-select></el-form-item>
        <el-form-item label="计算引擎" required><el-select v-model="computeEngine"><el-option label="HiveServer2 / Tez" value="HIVE_TEZ" /><el-option label="Kyuubi / Spark" value="KYUUBI_SPARK" /></el-select></el-form-item>
        <el-form-item v-if="computeEngine==='KYUUBI_SPARK'" label="Spark 连接" required><el-select v-model="computeDatasourceId" filterable placeholder="选择已登记并分配到本项目的 Kyuubi 连接"><el-option v-for="source in sparkDatasources" :key="source.id" :label="source.name" :value="source.id" /></el-select></el-form-item>
      </div>
      <el-form-item label="交付模型" required><el-select v-model="leafIds" multiple filterable placeholder="选择本次需要生成的应用模型"><el-option v-for="model in outputModels" :key="model.id" :value="model.id" :label="model.name" /></el-select></el-form-item>
    </el-form>
    <div class="batch-input-heading"><h4>成功的原始接入</h4><span>可以合并原始批次与新增输入；同一文件只计入一次。</span></div>
    <el-table :data="candidates" row-key="id" border @selection-change="selectedInputs=$event" empty-text="当前空间暂无符合要求的成功 CSV 接入，请先完成数据集成。">
      <el-table-column type="selection" width="48" />
      <el-table-column prop="name" label="接入任务" min-width="210" />
      <el-table-column prop="table" label="原始数据版本" min-width="210" show-overflow-tooltip />
      <el-table-column prop="rows" label="原始行数" width="110" align="right" />
      <el-table-column label="完成时间（北京时间）" width="190"><template #default="{row}">{{formatWarehouseTime(row.end_time, '+08:00')}}</template></el-table-column>
    </el-table>
    <div class="batch-create"><el-button type="primary" :loading="busy" :disabled="!spaceId || !capabilities.batchCreate" @click="createBatch">校验输入并生成开发任务</el-button><span>{{selectedInputs.length}} 条输入已选择</span></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <h4>批次记录</h4>
    <el-table :data="batches" border empty-text="生成后可在这里查看固定版本、执行状态与开发任务。">
      <el-table-column prop="name" label="批次" min-width="190" />
      <el-table-column label="业务日期" min-width="200"><template #default="{row}">{{day(row.window_start)}} 至 {{day(row.window_end)}}</template></el-table-column>
      <el-table-column label="调度/计算" min-width="200"><template #default="{row}">{{schedulerLabel(row.scheduler)}} / {{engineLabel(row.computeEngine)}}</template></el-table-column>
      <el-table-column prop="attempt" label="尝试" width="70" />
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="tone(row.status)">{{status(row.status)}}</el-tag></template></el-table-column>
      <el-table-column prop="message" label="说明" min-width="230" show-overflow-tooltip />
      <el-table-column label="操作" width="190"><template #default="{row}"><el-button link type="primary" @click="show(row.id)">查看</el-button><el-button v-if="row.status==='READY'" link type="primary" :disabled="busy || !capabilities.modelExecute" @click="start(row.id)">运行</el-button><el-button v-if="['FAILED','UNKNOWN'].includes(row.status)" link type="primary" :disabled="busy || !capabilities.batchCreate" @click="retry(row)">新建尝试</el-button></template></el-table-column>
    </el-table>
    <section v-if="selected" class="batch-detail">
      <div class="batch-heading"><div><h3>{{selected.name}}</h3><p>{{schedulerLabel(selected.scheduler)}} / {{engineLabel(selected.computeEngine)}} · 尝试 {{selected.attempt}} · {{status(selected.status)}} · {{selected.message}}</p></div><div class="batch-actions"><el-button v-if="selected.status === 'SUCCESS' && capabilities.catalogWrite" v-hasPermi="['da:asset:add']" :loading="busy" @click="linkCatalog">关联元数据与资产</el-button><el-button v-if="selected.task_id" @click="openTask">查看开发任务</el-button></div></div>
      <el-alert v-if="catalogError" :title="catalogError" type="warning" :closable="false" />
      <el-table :data="selected.steps" border>
        <el-table-column label="模型版本" min-width="260"><template #default="{row}"><router-link :to="{path:'/dm/model/warehouse',query:{modelId:row.model_id}}">{{modelName(row.model_id)}}</router-link></template></el-table-column>
        <el-table-column label="治理目录" width="180"><template #default="{row}">
          <div>{{ catalogFor(row.model_id).metadataId ? `元数据 ${catalogFor(row.model_id).metadataId}` : '待采集元数据' }}</div>
          <router-link v-if="catalogFor(row.model_id).assetId" :to="{path:'/da/asset/detail',query:{id:catalogFor(row.model_id).assetId}}">资产 {{ catalogFor(row.model_id).assetId }} · {{ catalogFor(row.model_id).linked ? '已关联' : '待关联' }}</router-link>
        </template></el-table-column>
        <el-table-column prop="template_revision" label="模板版本" width="100" />
        <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="tone(row.status)">{{status(row.status)}}</el-tag></template></el-table-column>
        <el-table-column prop="row_count" label="结果行数" width="110" align="right" />
        <el-table-column prop="message" label="执行结果" min-width="260" show-overflow-tooltip />
      </el-table>
      <QualityAssessment v-if="selected.status==='SUCCESS' && capabilities.qualityRead" :batch-id="selected.id" :space-id="spaceId" />
      <ServingRelease v-if="selected.status==='SUCCESS' && capabilities.servingRead" :batch-id="selected.id" :space-id="spaceId" />
    </section>
  </section>
</template>

<script setup>
import {formatWarehouseTime} from './warehouseTime';
import {computed,onBeforeUnmount,ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {ElMessage} from 'element-plus';
import request from '@/utils/request';
import {useWarehouseCapabilities} from './useCapabilities';
import QualityAssessment from './QualityAssessment.vue';
import ServingRelease from './ServingRelease.vue';
const props=defineProps({spaces:{type:Array,default:()=>[]},models:{type:Array,default:()=>[]},datasources:{type:Array,default:()=>[]}});
const emit=defineEmits(['created']);
const route=useRoute(),router=useRouter();
const spaceId=ref(null),name=ref(''),dates=ref([]),leafIds=ref([]),candidates=ref([]),selectedInputs=ref([]),batches=ref([]),selected=ref(null),loading=ref(false),busy=ref(false),error=ref('');
const scheduler=ref('QUARTZ'),computeEngine=ref('HIVE_TEZ'),computeDatasourceId=ref(null);
const sparkDatasources=computed(()=>props.datasources.filter(source=>String(source.id)!==String(props.spaces.find(space=>String(space.id)===String(spaceId.value))?.datasourceId)));
const schedulerLabel=value=>value==='DOLPHINSCHEDULER'?'DolphinScheduler':'Quartz';
const engineLabel=value=>value==='KYUUBI_SPARK'?'Kyuubi/Spark':'Hive/Tez';
const catalogRows=ref([]),catalogError=ref('');
const {capabilities,loadCapabilities}=useWarehouseCapabilities(()=>spaceId.value);
const catalogFor=id=>catalogRows.value.find(row=>String(row.modelId)===String(id))||{};
async function loadCatalog(){catalogRows.value=[];catalogError.value='';if(!selected.value || !capabilities.value.catalogRead)return;try{catalogRows.value=await api(`/${selected.value.id}/catalog`);}catch(e){catalogError.value=e.message||String(e);}}
async function linkCatalog(){await action(async()=>{catalogRows.value=await api(`/${selected.value.id}/catalog`,'post');ElMessage.success('本批元数据已关联原有资产，资产与字段标识保持');});}
const outputModels=computed(()=>props.models.filter(m=>String(m.spaceId)===String(spaceId.value) && m.layer==='ADS' && !m.managedBatchId));
const api=(path='',method='get',data)=>request({url:`/dp/warehouse/batches${path}`,method,data,timeout:180000,headers:{repeatSubmit:false,hideErrorMessage:true}}).then(r=>r.data);
const status=value=>({PREPARING:'校验中',READY:'待执行',WAITING_DS:'DolphinScheduler 触发中',QUEUED:'等待上游',RUNNING:'执行中',SUCCESS:'计算完成',FAILED:'失败',UNKNOWN:'需要核对',BLOCKED:'上游阻塞',NO_DATA:'无有效数据'}[value]||value);
const tone=value=>value==='SUCCESS'?'success':['FAILED','UNKNOWN'].includes(value)?'danger':['RUNNING','PREPARING'].includes(value)?'warning':'info';
const day=value=>String(value||'').slice(0,10);
const modelName=id=>{const m=props.models.find(m=>String(m.id)===String(id));return m ? `${m.name} · ${m.databaseName}.${m.tableName}` : `模型 ${id}`;};
async function action(work){busy.value=true;error.value='';try{await work();}catch(e){error.value=e.message||String(e);if(spaceId.value){try{batches.value=await api(`?spaceId=${spaceId.value}`);}catch{/* Preserve the original preparation error. */}}}finally{busy.value=false;}}
async function reload(includeInputs=true){if(!spaceId.value)return;loading.value=true;error.value='';try{if(!(await loadCapabilities()) || !capabilities.value.batchRead)return;batches.value=await api(`?spaceId=${spaceId.value}`);if(includeInputs && capabilities.value.batchCreate)candidates.value=await api(`/inputs?spaceId=${spaceId.value}`);if(selected.value){selected.value=await api(`/${selected.value.id}`);await loadCatalog();}}catch(e){error.value=e.message||String(e);}finally{loading.value=false;}}
async function changeSpace(){selected.value=null;selectedInputs.value=[];leafIds.value=outputModels.value.map(m=>m.id);await router.replace({query:{...route.query,tab:'batches',batchId:undefined}});await reload();}
async function show(id){selected.value=await api(`/${id}`);await loadCatalog();await router.replace({query:{...route.query,tab:'batches',batchId:id}});}
async function createBatch(){await action(async()=>{if(!name.value.trim() || dates.value?.length!==2 || !leafIds.value.length || !selectedInputs.value.length)throw new Error('请填写批次名称、业务日期、交付模型并选择原始输入');if(computeEngine.value==='KYUUBI_SPARK'&&!computeDatasourceId.value)throw new Error('请选择 Kyuubi/Spark 连接');const batch=await api('','post',{spaceId:spaceId.value,name:name.value.trim(),windowStart:dates.value[0],windowEnd:dates.value[1],modelIds:leafIds.value,scheduler:scheduler.value,computeEngine:computeEngine.value,computeDatasourceId:computeEngine.value==='KYUUBI_SPARK'?computeDatasourceId.value:null,inputs:selectedInputs.value.map(row=>({modelId:row.modelId,instanceId:row.id}))});selected.value=batch;emit('created');await reload(false);await show(batch.id);if(['FAILED','UNKNOWN'].includes(batch.status))ElMessage.warning('已有未完成批次，请查看原因并新建尝试');else ElMessage.success(batch.status==='SUCCESS'?'已复用成功批次，未再次写入Hive':batch.status==='NO_DATA'?'业务窗口内没有有效数据':'固定版本与开发任务已准备完成');});}
async function start(id){await action(async()=>{selected.value=await api(`/${id}/start`,'post');await reload(false);ElMessage.success('任务已提交，请查看各节点最终结果');});}
async function retry(row){await action(async()=>{selected.value=await api('','post',{spaceId:spaceId.value,name:row.name.slice(0,128),retryOf:row.id});emit('created');await reload(false);await show(selected.value.id);});}
function openTask(){router.push({path:'/dpp/task/developTask/detail',query:{id:selected.value.task_id,info:1}});}
watch(()=>props.spaces,async spaces=>{if(spaceId.value || !spaces.length)return;spaceId.value=spaces[0].id;leafIds.value=outputModels.value.map(m=>m.id);if(route.query.batchId){selected.value=await api(`/${route.query.batchId}`);spaceId.value=selected.value.space_id;}await reload();},{immediate:true});
watch(outputModels,options=>{if(!leafIds.value.length)leafIds.value=options.map(m=>m.id);});
let polling=false;const timer=setInterval(async()=>{if(polling || busy.value || !spaceId.value || !capabilities.value.batchRead)return;if(!batches.value.some(b=>['PREPARING','WAITING_DS','RUNNING'].includes(b.status)))return;polling=true;try{batches.value=await api(`?spaceId=${spaceId.value}`);if(selected.value){selected.value=await api(`/${selected.value.id}`);await loadCatalog();}}catch(e){error.value=e.message||String(e);}finally{polling=false;}},4000);
onBeforeUnmount(()=>clearInterval(timer));
</script>

<style scoped>
.batch-actions{display:flex;gap:8px;flex-wrap:wrap}.batch-panel{padding:20px;background:#fff}.batch-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.batch-heading h3{margin:0 0 10px;font-size:16px;font-weight:500}.batch-heading p,.batch-input-heading span,.batch-create span{font-size:13px;line-height:1.7;color:#606f80}.batch-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}.batch-panel :deep(.el-select){width:100%}.batch-input-heading{display:flex;align-items:center;gap:16px}.batch-input-heading h4,.batch-panel>h4{font-size:14px;font-weight:500}.batch-create{display:flex;align-items:center;gap:16px;margin:20px 0}.batch-detail{margin-top:28px;padding-top:20px;border-top:1px solid #e5e8ef}.batch-detail a{color:#2666fb;overflow-wrap:anywhere}@media(max-width:1000px){.batch-form-grid{grid-template-columns:1fr;gap:0}.batch-heading{flex-wrap:wrap}}
</style>
