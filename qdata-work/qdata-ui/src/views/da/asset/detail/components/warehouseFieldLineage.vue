<template>
  <section class="field-lineage">
    <el-alert v-if="coverage.status !== 'COMPLETE'" :title="coverage.message || '部分字段来源尚未完整确认，请核对下面的状态与说明。'" type="warning" :closable="false" show-icon />
    <el-alert v-if="coverage.versionBasis === 'MIXED_WITH_MODEL_DEFINITIONS'" title="存在未绑定固定批次的模型，相关字段展示当前登记配置。" type="info" :closable="false" />
    <div class="field-tools">
      <el-select v-model="modelId" filterable placeholder="选择库表" aria-label="字段血缘库表" @change="page=1; search=''">
        <el-option v-for="model in models" :key="model.id" :label="model.table" :value="model.id" />
      </el-select>
      <el-input v-model="search" clearable placeholder="搜索字段名称" aria-label="字段血缘字段搜索" @input="page=1" />
      <el-button @click="reset">返回当前资产</el-button>
      <span>{{coverage.resolvedFields || 0}} / {{coverage.declaredFields || 0}} 个字段已解析</span>
    </div>
    <el-table :data="pageRows" border stripe height="490" :row-key="row=>`${row.modelId}:${row.columnName}`" empty-text="当前来源尚未提供字段级血缘">
      <el-table-column prop="columnName" label="目标字段" min-width="155" />
      <el-table-column prop="columnType" label="类型" width="140" />
      <el-table-column label="来源状态" width="115"><template #default="{row}"><el-tag :type="row.resolution==='RESOLVED'?'success':'warning'">{{row.resolution==='RESOLVED'?'已解析':'待确认'}}</el-tag></template></el-table-column>
      <el-table-column label="取值来源" min-width="370"><template #default="{row}">
        <div v-if="!row.valueSources?.length" class="muted">{{row.resolution==='RESOLVED'?'常量或系统生成':'来源尚未确认'}}</div>
        <div v-for="source in row.valueSources?.slice(0,3)" :key="sourceKey(source)" class="source-line">
          <el-button v-if="canFollow(source)" link type="primary" :title="sourceText(source)" @click="follow(source)">{{sourceText(source)}}</el-button>
          <span v-else :title="sourceText(source)">{{sourceText(source)}}</span>
        </div>
        <el-button v-if="row.valueSources?.length>3" link type="primary" @click="selected=row">查看全部 {{row.valueSources.length}} 个来源</el-button>
      </template></el-table-column>
      <el-table-column label="条件 / 维度" width="145"><template #default="{row}">{{row.conditionSources?.length || 0}} / {{row.dimensionReferences?.length || 0}}</template></el-table-column>
      <el-table-column label="版本" min-width="165"><template #default="{row}">{{versionText(row)}}</template></el-table-column>
      <el-table-column label="详情" width="80" fixed="right"><template #default="{row}"><el-button link type="primary" @click="selected=row">查看</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="filtered.length" v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[20,50,100]" :total="filtered.length" layout="total, sizes, prev, pager, next" />
    <el-dialog :model-value="!!selected" title="字段血缘详情" width="900px" destroy-on-close @close="selected=null">
      <template v-if="selected">
        <h3>{{selected.columnName}} <small>{{selected.columnType}}</small></h3>
        <p class="identity">{{selected.tableName}}</p>
        <el-alert v-if="selected.message" :title="selected.message" type="warning" :closable="false" />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="模型版本">{{selected.modelRevision ?? '—'}}</el-descriptions-item>
          <el-descriptions-item label="依据">{{selected.version?.basis==='FIXED_BATCH'?'固定运行批次':'当前模型定义'}}</el-descriptions-item>
          <el-descriptions-item label="模板版本">{{selected.version?.template_revision ?? '—'}}</el-descriptions-item>
          <el-descriptions-item label="任务版本">{{selected.version?.task_id ?? '—'}} / {{selected.version?.task_version ?? '—'}}</el-descriptions-item>
          <el-descriptions-item label="批次" :span="2">{{selected.version?.batch_id || '未绑定固定批次'}}</el-descriptions-item>
          <el-descriptions-item label="执行状态">{{selected.version?.status || '未绑定'}}</el-descriptions-item>
          <el-descriptions-item label="结果行数">{{selected.version?.row_count ?? '—'}}</el-descriptions-item>
        </el-descriptions>
        <section v-for="group in groups" :key="group.key" class="detail-section">
          <h4>{{group.label}}</h4>
          <div v-if="!selected[group.key]?.length" class="muted">{{group.empty}}</div>
          <div v-for="source in selected[group.key]" :key="sourceKey(source)" class="identity">{{sourceText(source)}}</div>
        </section>
        <section class="detail-section">
          <h4>计算逻辑与条件</h4>
          <el-alert v-if="!coverage.canReadExpressions" title="查看计算逻辑需要当前项目的模型查看权限。" type="info" :closable="false" />
          <template v-else>
            <div v-for="(step,index) in selected.expressions" :key="index" class="expression-step"><div>{{clauseLabel(step.clause)}} · {{step.scope}}{{step.column?' · '+step.column:''}}</div><pre>{{step.expression}}</pre></div>
            <p v-if="!selected.expressions?.length" class="muted">原始接入字段的配置见下方输入来源。</p>
          </template>
        </section>
        <section v-if="selected.inputOrigins?.length" class="detail-section">
          <h4>固定接入来源</h4>
          <div v-for="origin in selected.inputOrigins" :key="origin.instanceId" class="input-origin">
            <p>任务 {{origin.taskId}} · 版本 {{origin.taskVersion}} · 实例 {{origin.instanceId}}</p>
            <p class="identity">{{origin.tableName}} · {{origin.fileRows}} 行 · 目标类型 {{origin.targetType}}</p>
            <p>CSV 字段：{{origin.csvColumns.join('、')}}</p>
            <p class="identity">文件摘要：{{origin.fileSha256}}</p>
            <div v-if="coverage.canReadExpressions" v-for="(step,index) in origin.steps" :key="index" class="expression-step"><div>{{step.operation}} · 节点 {{step.nodeCode}} / v{{step.nodeVersion}}</div><pre>{{JSON.stringify(step.configuration,null,2)}}</pre></div>
          </div>
        </section>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import {computed,ref,watch} from 'vue';
const props=defineProps({lineage:{type:Object,default:()=>({})}});
const modelId=ref(null),search=ref(''),page=ref(1),pageSize=ref(20),selected=ref(null);
const fields=computed(()=>props.lineage.fields || []),coverage=computed(()=>props.lineage.fieldCoverage || {});
const models=computed(()=>Array.from(new Map(fields.value.map(field=>[field.modelId,{id:field.modelId,table:field.tableName}])).values()));
const filtered=computed(()=>fields.value.filter(field=>field.modelId===modelId.value && field.columnName.toLowerCase().includes(search.value.trim().toLowerCase())));
const pageRows=computed(()=>filtered.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value));
const sourceText=source=>`${source.tableName || `接入实例 ${source.instanceId}`} · ${source.columnName==='*'?'记录行':source.columnName}`;
const sourceKey=source=>`${source.modelId || source.instanceId}:${source.tableName}:${source.columnName}:${source.kind}`;
const canFollow=source=>models.value.some(model=>model.id===source.modelId);
const versionText=row=>row.version?.basis==='FIXED_BATCH'?`模板 v${row.version.template_revision} · 任务 v${row.version.task_version}`:`模型 v${row.modelRevision ?? '—'}`;
function reset(){modelId.value=props.lineage.currentTable?.id ?? models.value[0]?.id ?? null;search.value='';page.value=1;selected.value=null;}
function follow(source){modelId.value=source.modelId;search.value=source.columnName==='*'?'':source.columnName;page.value=1;selected.value=null;}
const groups=[{key:'valueSources',label:'取值来源',empty:'无直接字段来源'},{key:'conditionSources',label:'查询条件参与的字段',empty:'无字段条件依赖'},{key:'rowSources',label:'输入记录来源',empty:'无登记的输入记录来源'},{key:'dimensionReferences',label:'维度键关联',empty:'未登记维度键关联'}];
const clauseLabel=kind=>({VALUE:'字段表达式',FILTER:'筛选',GROUP:'分组',HAVING:'聚合筛选',ORDER:'排序',JOIN:'关联条件'}[kind] || kind);
watch(()=>props.lineage,reset,{immediate:true});watch(pageSize,()=>page.value=1);
</script>

<style scoped>
.field-lineage{padding:8px 0}.field-lineage>.el-alert{margin-bottom:12px}.field-tools{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0}.field-tools .el-select{width:420px;max-width:100%}.field-tools .el-input{width:220px}.field-tools>span,.muted{color:#758092;font-size:13px}.source-line{margin:3px 0;overflow-wrap:anywhere}.source-line .el-button{white-space:normal;text-align:left;height:auto;line-height:1.5}.identity{overflow-wrap:anywhere;color:#526174;line-height:1.7}.field-lineage :deep(.el-pagination){margin-top:16px}.detail-section{margin-top:22px}.detail-section h4{margin:0 0 12px;font-weight:500}.expression-step{margin:10px 0}.expression-step>div{color:#64748b;font-size:12px}.expression-step pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f6f8fb;border:1px solid #e8edf4;padding:12px;border-radius:4px;font-size:12px;line-height:1.7}.input-origin{border-top:1px solid #e8edf4;padding:12px 0}h3{font-weight:500}h3 small{font-size:12px;color:#758092}
</style>
