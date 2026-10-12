<template>
  <section v-loading="loading" class="asset-references">
    <div class="reference-heading"><div><h3>引用与影响</h3><p>查看模型、质量检查、字段标准及服务对该资产的依赖。</p></div><el-button @click="reload">刷新引用</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="data">
      <el-alert :type="data.referenced?'warning':'info'" :closable="false" :title="data.referenced?'此资产已有业务引用，物理身份和字段标识受到保护。':'尚未发现模型、质量、标准或服务引用；删除仍会检查其他任务依赖。'" />
      <p class="standard-count">字段标准关联：{{data.standardLinks}} 项</p>
      <h4>模型与版本</h4><el-table :data="data.models" border empty-text="暂无模型引用"><el-table-column prop="id" label="模型编号" width="110" /><el-table-column prop="database_name" label="数据库" width="190" /><el-table-column prop="name" label="模型表" min-width="260" show-overflow-tooltip /><el-table-column label="操作" width="130"><template #default="{row}"><router-link :to="{path:'/dm/model/warehouse',query:{modelId:row.id}}">查看模型</router-link></template></el-table-column></el-table>
      <h4>质量检查</h4><el-table :data="data.qualityTasks" border empty-text="暂无质量任务引用"><el-table-column prop="id" label="任务编号" width="110" /><el-table-column prop="name" label="质量任务" min-width="260" /></el-table>
      <h4>质量规则</h4><el-table :data="data.qualityRules" border empty-text="暂无质量规则引用"><el-table-column prop="id" label="规则编号" width="110" /><el-table-column prop="task_id" label="任务编号" width="110" /><el-table-column prop="name" label="规则名称" min-width="220" show-overflow-tooltip /><el-table-column prop="rule_name" label="规则类型" min-width="180" /><el-table-column prop="eva_column" label="检查字段" min-width="160" /></el-table>
      <h4>字段标准</h4><el-table :data="data.standards" border empty-text="暂无字段标准引用"><el-table-column prop="id" label="关联编号" width="110" /><el-table-column prop="data_elem_id" label="标准数据元" min-width="170" /><el-table-column prop="column_name" label="资产字段" min-width="180" /></el-table>
      <h4>数据服务</h4><el-table :data="data.services" border empty-text="暂无服务引用"><el-table-column prop="id" label="接口编号" width="110" /><el-table-column prop="name" label="服务名称" min-width="260" /><el-table-column prop="api_version" label="契约版本" width="130" /><el-table-column label="操作" width="130"><template #default="{row}"><router-link :to="{path:'/ds/api/detail',query:{id:row.id}}">查看接口</router-link></template></el-table-column></el-table>
    </template>
  </section>
</template>
<script setup>
import {ref,watch} from 'vue';
import request from '@/utils/request';
const props=defineProps({form1:{type:Object,default:()=>({})}});
const loading=ref(false),error=ref(''),data=ref(null);
async function reload(){if(!props.form1.id)return;loading.value=true;error.value='';try{data.value=(await request({url:`/da/asset/${props.form1.id}/references`})).data;}catch(failure){error.value=failure.message||String(failure);}finally{loading.value=false;}}
watch(()=>props.form1.id,reload,{immediate:true});
</script>
<style scoped>
.asset-references{padding:16px}.reference-heading{display:flex;justify-content:space-between;gap:20px}.reference-heading h3{font-size:16px;margin:0}.reference-heading p,.standard-count{font-size:13px;color:var(--el-text-color-secondary);line-height:1.7}.asset-references h4{font-size:14px;margin:24px 0 12px}.asset-references a{color:var(--el-color-primary)}
</style>
