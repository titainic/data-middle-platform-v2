<template>
  <div class="app-container warehouse-page">
    <ModelWorkspaceTabs />
    <div class="warehouse-workspace">
      <div class="workspace-tools">
        <span class="pipeline-hint">ODS → DWD → DWS → DWT → ADS</span>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </div>
      <el-tabs v-model="activeTab" class="warehouse-tabs">
        <el-tab-pane label="库级空间" name="spaces">
          <section class="secondary-panel namespace-panel">
            <h3>项目与分层数据库</h3>
            <p class="field-help">为项目和环境登记五个独立数据库。这里保存分层绑定；实际建库和模型物化分别验证。</p>
            <el-table :data="warehouseContext.spaces" border class="space-table">
              <el-table-column prop="name" label="数仓空间" min-width="150" />
              <el-table-column prop="projectName" label="所属项目" min-width="130" />
              <el-table-column prop="environmentCode" label="环境" width="110" />
              <el-table-column prop="engineName" label="Hive 执行环境" min-width="150" />
              <el-table-column v-for="layer in layers" :key="layer.code" :label="layer.code" min-width="145"><template #default="{row}">{{row.databases[layer.code]}}</template></el-table-column>
              <el-table-column label="操作" width="120" fixed="right"><template #default="{row}"><el-button link type="primary" :loading="busy" @click="inspectSpace(row)">核验物理库</el-button></template></el-table-column>
            </el-table>
            <el-table v-if="spaceInspection.length" :data="spaceInspection" border class="space-table"><el-table-column prop="layer" label="分层" width="100" /><el-table-column prop="database" label="数据库" /><el-table-column label="实际检查结果"><template #default="{row}"><el-tag :type="row.status==='VISIBLE' ? 'success' : 'warning'">{{row.message}}</el-tag></template></el-table-column></el-table>
            <template v-if="warehouseContext.canRegisterEngine">
            <div class="section-title"><h3>新增数仓空间</h3></div>
            <el-form :model="spaceForm" label-width="115px" :label-position="compact ? 'top' : 'right'" @submit.prevent>
              <div class="form-grid">
                <el-form-item label="空间名称" required><el-input v-model="spaceForm.name" placeholder="例如：IoT 环境监测" /></el-form-item>
                <el-form-item label="所属项目" required><el-select v-model="spaceForm.projectId" placeholder="选择项目"><el-option v-for="project in warehouseContext.projects.filter(p=>p.canCreateSpace)" :key="project.id" :label="project.name" :value="project.id" /></el-select></el-form-item>
                <el-form-item label="环境标识" required><el-input v-model="spaceForm.environmentCode" placeholder="例如：test、prod" /></el-form-item>
                <el-form-item label="Hive 执行环境" required><el-select v-model="spaceForm.engineId" placeholder="选择已登记环境"><el-option v-for="engine in warehouseContext.engines" :key="engine.id" :label="engine.name" :value="engine.id" /></el-select></el-form-item>
                <el-form-item v-for="layer in layers" :key="layer.code" :label="`${layer.code} 数据库`" required><el-input v-model="spaceForm.databases[layer.code]" :placeholder="`${layer.code.toLowerCase()}_iot_db`" /></el-form-item>
              </div>
              <el-button type="primary" :loading="busy" :disabled="!warehouseContext.projects.some(p=>p.id===spaceForm.projectId && p.canCreateSpace)" @click="saveSpace" v-hasPermi="['dp:model:add']">保存数仓空间</el-button>
            </el-form>
            </template>
            <template v-if="warehouseContext.canRegisterEngine">
              <div class="section-title engine-heading"><h3>Hive 执行环境登记</h3><el-button link type="primary" @click="showEngineForm=!showEngineForm">{{showEngineForm ? '收起' : '登记执行环境'}}</el-button></div>
              <el-form v-if="showEngineForm" :model="engineForm" label-width="115px" :label-position="compact ? 'top' : 'right'" @submit.prevent>
                <p class="field-help">同一 Hive 集群的多个数据库共用一个执行环境。连接登记前会实际验证 Hive 可达性。</p>
                <div class="form-grid">
                  <el-form-item label="环境名称" required><el-input v-model="engineForm.name" placeholder="例如：IoT Hive 集群" /></el-form-item>
                  <el-form-item label="Hive 连接" required><el-select v-model="engineForm.datasourceId" placeholder="选择已有 Hive 连接"><el-option v-for="source in datasources" :key="source.id" :label="source.name" :value="source.id" /></el-select></el-form-item>
                  <el-form-item label="集群标识" required><el-input v-model="engineForm.catalogKey" placeholder="例如：iot_hive；同一集群保持一致" /></el-form-item>
                </div>
                <el-button type="primary" plain :loading="busy" @click="saveEngine">验证连接并登记</el-button>
              </el-form>
            </template>
            <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="feedback" />
          </section>
        </el-tab-pane>
        <el-tab-pane label="持续批次" name="batches" lazy><BatchManager :spaces="warehouseContext.spaces" :models="models" :datasources="datasources" @created="refresh" /></el-tab-pane>
        <el-tab-pane label="模型与加工" name="models">
          <div class="warehouse-layout">
            <aside class="model-navigation" aria-label="数仓模型目录">
              <div class="directory-title"><span>数仓模型</span><el-tag size="small" type="info" effect="plain">{{ models.length }}</el-tag></div>
              <el-input v-model="search" :prefix-icon="Search" placeholder="搜索模型名称或表名" clearable aria-label="搜索模型" />
              <div class="directory-actions"><el-button type="primary" plain size="small" :icon="Plus" v-if="warehouseContext.spaces.some(s=>s.capabilities?.modelWrite)" @click="newModel" v-hasPermi="['dp:model:add']">新建分层模型</el-button><el-button v-if="form.id && form.spaceId && !form.managedBatchId && modelCapabilities.modelWrite" size="small" plain @click="newPhysicalVersion" v-hasPermi="['dp:model:add']">新建数据版本</el-button></div>
              <el-tree ref="modelTree" class="model-tree" :data="treeNodes" node-key="key" :current-node-key="form.id ? `model-${form.id}` : null" default-expand-all highlight-current :expand-on-click-node="false" @node-click="handleTreeClick">
                <template #default="{data}">
                  <div class="directory-node" :class="{'directory-model':!!data.model}">
                    <el-icon :class="data.model ? 'table-icon' : 'folder-icon'"><Document v-if="data.model" /><FolderOpened v-else /></el-icon>
                    <div class="directory-label"><span :title="data.label">{{data.label}}</span><small v-if="data.model" :title="qualifiedName(data.model)">{{qualifiedName(data.model)}}</small></div>
                    <span v-if="!data.model" class="layer-count">{{data.children.length}}</span>
                  </div>
                </template>
              </el-tree>
              <p v-if="search && !filteredModels.length" class="directory-empty">没有匹配的模型，请调整搜索条件。</p>
            </aside>
            <main class="model-editor">
              <div v-if="!editorOpen" class="editor-empty">
                <el-icon :size="44"><Files /></el-icon>
                <h2>选择模型开始编辑</h2>
                <p>从左侧选择已有模型，查看字段、上游关系和加工规则。</p>
                <p>需要建立新模型时，点击“新建分层模型”。</p>
                <div class="empty-pipeline"><span v-for="layer in layers" :key="layer.code">{{layer.code}}<small>{{layer.name}}</small></span></div>
              </div>
              <template v-else>
                <header class="editor-heading">
                  <div><h2>{{form.id ? form.name : `新建 ${form.layer} 分层模型`}}</h2><p>{{form.id ? qualifiedName(form) : '选择项目空间，填写粒度、来源与字段定义后保存。'}}</p></div>
                  <div class="editor-status"><el-tag size="small" effect="plain">{{form.layer}}</el-tag><el-tag size="small" :type="!form.id || dirty ? 'warning' : 'info'" effect="plain">{{!form.id ? '待保存' : dirty ? '有未保存修改' : `版本 ${form.revision}`}}</el-tag></div>
                </header>
                <el-alert class="layer-notice" :title="layerDescription" type="info" :closable="false" show-icon />
                <el-form :label-position="compact ? 'top' : 'right'" label-width="110px" :model="form" :disabled="!!form.managedBatchId" class="definition-form" @submit.prevent>
                  <section class="form-section" aria-labelledby="warehouse-basic-title">
                    <div class="section-title"><h3 id="warehouse-basic-title">基础信息</h3></div>
                    <div class="form-grid">
                <el-form-item label="模型中文名称" required><el-input v-model="form.name" placeholder="例如：测站日雨量主题" /></el-form-item>
                <el-form-item label="数仓空间" :required="!form.id"><el-select v-model="form.spaceId" :disabled="!!form.id" :placeholder="form.id ? '历史单库模型' : '请选择数仓空间'" @change="changeSpace"><el-option v-for="space in warehouseContext.spaces" :key="space.id" :value="space.id" :label="`${space.projectName} / ${space.environmentCode} / ${space.name}`" /></el-select></el-form-item>
                <el-form-item label="Hive 数据源" required><el-select v-model="form.datasourceId" :disabled="!!form.id || !!form.spaceId" placeholder="由数仓空间确定" filterable><el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" /></el-select></el-form-item>
                <el-form-item label="目标数据库" required><el-input :model-value="form.databaseName || ''" disabled placeholder="由空间与分层确定" /></el-form-item>
                <el-form-item label="所属分层" required><el-select v-model="form.layer" :disabled="!!form.id" @change="changeLayer"><el-option v-for="l in layers" :key="l.code" :value="l.code" :label="`${l.code} · ${l.name}`" /></el-select></el-form-item>
                <el-form-item label="模型角色" required><el-select v-model="form.role" :disabled="!!form.id" @change="form.dimensions=[]"><el-option v-for="r in roles" :key="r.value" :value="r.value" :label="r.label" /></el-select></el-form-item>
                <el-form-item label="Hive 表名" required><el-input v-model="form.tableName" :disabled="!!form.id" :placeholder="form.spaceId ? '例如：water_quality' : `${form.layer.toLowerCase()}_业务_模型`" /></el-form-item>
                <el-form-item label="存储格式"><el-select v-model="form.storage"><el-option label="Parquet" value="PARQUET" /><el-option label="ORC" value="ORC" /></el-select></el-form-item>
                <el-form-item label="数据粒度" required><el-input v-model="form.grain" placeholder="例如：一个测站在一个观测时段的降雨记录" /></el-form-item>
                <el-form-item label="业务主题" :required="['DWT','ADS'].includes(form.layer)"><el-input v-model="form.topic" placeholder="例如：雨情监测" /></el-form-item>
              </div>
                    <el-form-item label="业务说明" class="wide-field description-field"><el-input v-model="form.description" type="textarea" :rows="2" placeholder="说明业务含义、数据粒度、质量规则或统计口径" /></el-form-item>
                  </section>
                  <section class="form-section" aria-labelledby="warehouse-source-title">
                    <div class="section-title"><h3 id="warehouse-source-title">{{form.layer === 'ODS' ? '原始数据来源' : '上游与加工'}}</h3></div>
                    <el-form-item v-if="form.layer === 'ODS'" label="原始来源表" class="wide-field"><div class="field-content"><el-input v-model="form.rawTable" placeholder="database.raw_table；仅按同名字段采集" /><p class="field-help">可留空后通过数据集成采集。已有 ODS 数据保持不变，不重复写入。</p></div></el-form-item>
                    <template v-else>
                      <el-form-item label="上游模型" required class="wide-field"><div class="field-content"><el-select v-model="form.upstreamIds" multiple filterable :placeholder="`选择 ${previousLayer} 层模型`" @change="preview = null"><el-option v-for="m in upstreamOptions" :key="m.id" :label="`${m.name} · ${qualifiedName(m)}`" :value="m.id" /></el-select><p class="field-help">仅允许读取同一空间的 {{previousLayer}} 层。{{upstreamNames || '先建立上一层模型，再选择来源。'}} SQL 使用完整数据库和表名。</p></div></el-form-item>
                      <el-form-item label="加工 SQL" required class="wide-field sql-field"><el-input v-model="form.selectSql" type="textarea" :rows="6" placeholder="SELECT ... FROM 上一层模型；可使用 JOIN、聚合、窗口函数或 CTE，不填写 INSERT / CREATE。" /></el-form-item>
                    </template>
                  </section>
                  <section class="form-section" aria-labelledby="warehouse-columns-title">
                    <div class="section-title"><h3 id="warehouse-columns-title">字段定义</h3><span class="section-meta">{{form.columns.length}} 个字段</span></div>
                    <div class="field-toolbar"><el-button size="small" type="primary" plain :icon="Plus" @click="addColumn">添加字段</el-button><span>按 SQL 输出顺序配置，分区字段排在最后。业务键在执行后校验。</span></div>
                    <el-table :data="form.columns"  border class="column-editor" max-height="400">
                <el-table-column label="字段英文名" min-width="145"><template #default="{row,$index}"><el-input v-model="row.name" :aria-label="`字段${$index+1}英文名`" /></template></el-table-column>
                <el-table-column label="Hive 类型" min-width="145"><template #default="{row,$index}"><el-input v-model="row.type" placeholder="DECIMAL(18,3)" :aria-label="`字段${$index+1}类型`" /></template></el-table-column>
                <el-table-column label="字段说明" min-width="140"><template #default="{row,$index}"><el-input v-model="row.comment" :aria-label="`字段${$index+1}说明`" /></template></el-table-column>
                <el-table-column label="来源字段 / 业务表达式" min-width="210"><template #default="{row,$index}"><el-input v-model="row.source" placeholder="例如：SUM(dws_rain.rain_mm)" :aria-label="`字段${$index+1}来源`" /></template></el-table-column>
                <el-table-column label="业务键" width="75"><template #default="{row,$index}"><el-checkbox v-model="row.key" :aria-label="`字段${$index+1}业务键`" /></template></el-table-column>
                <el-table-column label="分区" width="65"><template #default="{row,$index}"><el-checkbox v-model="row.partition" :aria-label="`字段${$index+1}分区`" /></template></el-table-column>
                <el-table-column label="操作" width="65"><template #default="{$index}"><el-button link type="danger" @click="form.columns.splice($index,1)">移除</el-button></template></el-table-column>
              </el-table>
                    <p v-if="!form.columns.length" class="field-help empty-fields-help">尚未配置字段，点击“添加字段”开始定义表结构。</p>
                  </section>
                  <section v-if="form.layer === 'DWT' && form.role === 'FACT'" class="form-section">
                <div class="section-title"><h3>星型维度关系</h3><el-button size="small" plain type="primary" :icon="Plus" @click="form.dimensions.push({column:'',modelId:null,targetColumn:''})">添加维度关系</el-button></div>
                <p class="field-help">事实表加工读取 DWS；这里定义与 DWT 维度表的关联关系。ADS 可以组合这些主题表。</p>
                <div v-for="(rel,index) in form.dimensions" :key="index" class="relation-row">
                  <el-select v-model="rel.column" placeholder="事实外键" :aria-label="`关系${index+1}事实外键`"><el-option v-for="c in form.columns" :key="c.name" :label="c.name" :value="c.name" /></el-select>
                  <span>→</span><el-select v-model="rel.modelId" placeholder="DWT 维度表" :aria-label="`关系${index+1}维度表`"><el-option v-for="m in dimensionOptions" :key="m.id" :label="qualifiedName(m)" :value="m.id" /></el-select>
                  <el-select v-model="rel.targetColumn" placeholder="维度业务键" :aria-label="`关系${index+1}维度键`"><el-option v-for="c in (models.find(m=>m.id===rel.modelId)?.columns || []).filter(c=>c.key)" :key="c.name" :label="c.name" :value="c.name" /></el-select>
                  <el-button link type="danger" @click="form.dimensions.splice(index,1)">移除</el-button>
                </div>
              </section>
                  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="feedback" />
                </el-form>
                  <footer class="editor-footer">
                    <div class="editor-actions">
                      <div class="action-group" role="group" aria-label="保存与校验"><el-button type="primary" :icon="Check" :loading="busy" :disabled="!!form.managedBatchId || !modelCapabilities.modelWrite" @click="save">保存模型</el-button><el-button :loading="busy" :disabled="!modelCapabilities.modelRead" @click="validate">校验并预览 SQL</el-button></div>
                      <div class="action-group execution-actions" role="group" aria-label="执行任务">
                        <el-tooltip :content="executionBlockReason" :disabled="!executionBlockReason"><span><el-button :disabled="!!executionBlockReason" :loading="busy" @click="execute('DDL')">物化建表</el-button></span></el-tooltip>
                        <el-tooltip :content="executionBlockReason" :disabled="!executionBlockReason"><span><el-button :icon="VideoPlay" :disabled="!!executionBlockReason" :loading="busy" @click="execute('LOAD')">执行本层</el-button></span></el-tooltip>
                        <el-tooltip :content="executionBlockReason" :disabled="!executionBlockReason"><span><el-button type="primary" plain :icon="Connection" :disabled="!!executionBlockReason" :loading="busy" @click="execute('CHAIN')">按血缘执行整链</el-button></span></el-tooltip>
                      </div>
                      <div class="action-group inspect-actions" role="group" aria-label="查看结果"><el-button :icon="View" :disabled="!form.id || busy" @click="showData">查看 Hive 数据</el-button><el-button link type="primary" @click="activeTab='runs'">执行记录</el-button></div>
                    </div>
                    <p class="execution-hint" role="status">{{executionBlockReason || '仅向空表写入。ODS 保留已有数据；上层目标表已有数据时停止加工，请新建版本表。'}}</p>
                  </footer>
                <div v-if="preview" class="sql-preview">
                  <p v-if="preview.dataCheckStatus==='PASS'" class="execution-hint" role="status">当前上游数据的数值 CAST 全量预检通过；执行写入前会再次复核。</p>
                  <p v-else-if="preview.dataCheckStatus==='DEFERRED'" class="execution-hint" role="status">上游物理表尚未建立；数据级数值 CAST 校验将在执行写入前完成。</p>
                  <h3>Hive DDL</h3><pre>{{preview.ddl}}</pre><h3>数据加工 SQL</h3><pre>{{preview.insertSql || '仅建表；原始数据通过数据集成写入。'}}</pre>
                </div>
              </template>
            </main>
          </div>
        </el-tab-pane>
        <el-tab-pane label="血缘与交付" name="lineage"><section class="secondary-panel">
        <p>加工血缘严格沿五层传递。主题关系是事实与维度的关联，不代表额外的数据加工依赖。</p>
        <div class="lineage-columns"><section v-for="l in layers" :key="l.code"><h3>{{ l.code }} · {{ l.name }}</h3><div v-for="m in models.filter(m=>m.layer===l.code)" :key="m.id" class="lineage-model"><strong>{{m.name}}</strong><code>{{qualifiedName(m)}}</code><p>{{m.grain}}</p><small>来源：{{m.layer==='ODS' ? (m.rawTable || '数据集成') : m.upstreamIds.map(id=>modelName(id)).join('、')}}</small><p v-for="r in m.dimensions" :key="r.column">{{r.column}} → {{modelName(r.modelId)}}.{{r.targetColumn}}</p></div></section></div>
        <el-button @click="exportLineage">导出建模与血缘 JSON</el-button>
        <el-table :data="fieldLineage" border><el-table-column prop="layer" label="层" width="75" /><el-table-column prop="table" label="目标表" min-width="200" /><el-table-column prop="column" label="目标字段" min-width="150" /><el-table-column prop="source" label="登记的来源 / 加工表达式" min-width="300" /></el-table>
      </section></el-tab-pane>
        <el-tab-pane label="执行记录" name="runs"><section class="secondary-panel">
        <p>保存每次执行的模型版本、SQL、行数和结果。上游失败时，下游标记为阻塞。</p>
        <el-table :data="logs" border><el-table-column prop="id" label="执行 ID" width="85" /><el-table-column label="模型" min-width="180"><template #default="{row}">{{modelName(row.model_id)}}</template></el-table-column><el-table-column prop="revision" label="版本" width="65" /><el-table-column prop="mode" label="方式" width="90" /><el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="statusType(row.status)">{{statusLabel(row.status)}}</el-tag></template></el-table-column><el-table-column prop="row_count" label="行数" width="80" /><el-table-column prop="message" label="执行结果" min-width="280" show-overflow-tooltip /><el-table-column label="开始时间（北京时间）" min-width="180"><template #default="{row}">{{formatWarehouseTime(row.started_at)}}</template></el-table-column><el-table-column label="详情" width="80"><template #default="{row}"><el-button link type="primary" @click="runDetail=row">查看</el-button></template></el-table-column></el-table>
      </section></el-tab-pane>
      </el-tabs>
    </div>
    <el-dialog v-model="dataVisible" title="Hive 数据预览（前 50 行）" width="85%"><el-table :data="dataRows" border max-height="500"><el-table-column v-for="key in Object.keys(dataRows[0] || {})" :key="key" :prop="key" :label="key" min-width="150" /></el-table><p v-if="!dataRows.length">目标表暂无数据。</p></el-dialog>
    <el-dialog :model-value="!!runDetail" title="执行详情" width="85%" @close="runDetail=null"><template v-if="runDetail"><p>批次 {{runDetail.batch_id}} · {{statusLabel(runDetail.status)}}</p><pre>{{runDetail.message}}</pre><h3>实际提交 SQL</h3><pre>{{runDetail.sql_text || '尚未提交 SQL'}}</pre><h3>执行时的模型与血缘快照</h3><pre>{{JSON.stringify(JSON.parse(runDetail.definition_json),null,2)}}</pre></template></el-dialog>
  </div>
</template>

<script setup>
import {formatWarehouseTime} from './warehouseTime';
import ModelWorkspaceTabs from '@/components/ModelWorkspaceTabs/index.vue';
import BatchManager from './BatchManager.vue';
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useMediaQuery } from '@vueuse/core';
import { Plus, Search, Refresh, Document, FolderOpened, Files, Check, VideoPlay, Connection, View } from '@element-plus/icons-vue';
const compact=useMediaQuery('(max-width: 1100px)');
const editorOpen=ref(false), modelTree=ref();
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router';
const route=useRoute(),router=useRouter();
import request from '@/utils/request';
import { encrypt } from '@/utils/aesEncrypt';
const layers=[{code:'ODS',name:'原始',description:'保留原始记录；不配置业务转换，不覆盖已采集的数据。'},{code:'DWD',name:'清洗',description:'只读取 ODS，完成清洗与质量提升，保留最细业务粒度。'},{code:'DWS',name:'融合',description:'只读取 DWD，完成多源汇聚、消歧、去重与必要聚合；保留主题建模需要的明细。'},{code:'DWT',name:'主题',description:'只读取 DWS，围绕主题构建事实表和维度表，记录业务键与星型关系。'},{code:'ADS',name:'应用',description:'只读取 DWT，为具体报表、大屏和接口组织数据。'}];
const models=ref([]),datasources=ref([]),logs=ref([]),search=ref(''),activeTab=ref(route.query.tab==='batches'?'batches':'models'),loading=ref(false),busy=ref(false),error=ref(''),preview=ref(null),snapshot=ref(''),dataRows=ref([]),dataVisible=ref(false),runDetail=ref(null);
const warehouseContext=ref({spaces:[],engines:[],projects:[],canRegisterEngine:false});
const showEngineForm=ref(false),spaceInspection=ref([]);
const spaceForm=ref({name:'',projectId:null,environmentCode:'test',engineId:null,databases:Object.fromEntries(layers.map(l=>[l.code,`${l.code.toLowerCase()}_iot_db`]))});
const engineForm=ref({name:'',datasourceId:null,catalogKey:''});
const qualifiedName=model=>model ? `${model.databaseName ? model.databaseName+'.' : ''}${model.tableName}` : '';
const empty=()=>{const space=warehouseContext.value.spaces[0];return {id:null,revision:0,name:'',tableName:'',databaseName:space?.databases.ODS || '',spaceId:space?.id || null,datasetId:null,layer:'ODS',role:'DETAIL',datasourceId:space?.datasourceId || datasources.value[0]?.id || null,grain:'',topic:'',description:'',storage:'PARQUET',rawTable:'',selectSql:'',upstreamIds:[],columns:[],dimensions:[]};};
const form=ref(empty());
const modelCapabilities=computed(()=>form.value.spaceId ? warehouseContext.value.spaces.find(s=>String(s.id)===String(form.value.spaceId))?.capabilities || {} : warehouseContext.value.legacyCapabilities || {});
const dirty=computed(()=>snapshot.value!==JSON.stringify(form.value));
const executing=computed(()=>logs.value.some(l=>['QUEUED','RUNNING'].includes(l.status)));
const filteredModels=computed(()=>models.value.filter(m=>`${m.name} ${qualifiedName(m)}`.toLowerCase().includes(search.value.toLowerCase())));
const treeNodes=computed(()=>layers.map(layer=>({key:`layer-${layer.code}`,label:`${layer.code} · ${layer.name}`,children:filteredModels.value.filter(m=>m.layer===layer.code).map(model=>({key:`model-${model.id}`,label:model.name,model}))})).filter(group=>!search.value || group.children.length));
const executionBlockReason=computed(()=>!modelCapabilities.value.modelExecute?'当前项目未授予执行权限。':busy.value?'正在处理，请稍候。':form.value.managedBatchId?'此为固定批次版本，请从持续批次或对应开发任务执行。':!form.value.id?'请先保存模型，保存后可物化建表或执行链路。':dirty.value?'当前模型有未保存修改，请先保存后再执行。':executing.value?'已有分层任务正在执行，请等待完成后再操作。':'');
async function handleTreeClick(data){if(!data.model)return;await selectModel(data.model);nextTick(()=>modelTree.value?.setCurrentKey(form.value.id?`model-${form.value.id}`:null));}
const previousLayer=computed(()=>layers[layers.findIndex(l=>l.code===form.value.layer)-1]?.code);
const layerDescription=computed(()=>layers.find(l=>l.code===form.value.layer)?.description);
const roles=computed(()=>form.value.layer==='DWT'?[{value:'FACT',label:'主题事实表'},{value:'DIMENSION',label:'主题维度表'}]:[{value:form.value.layer==='ADS'?'APPLICATION':'DETAIL',label:form.value.layer==='ADS'?'应用表':'明细 / 融合表'}]);
const sameSpace=model=>String(model.spaceId ?? '')===String(form.value.spaceId ?? '') && String(model.datasourceId)===String(form.value.datasourceId);
const upstreamOptions=computed(()=>models.value.filter(m=>m.layer===previousLayer.value && sameSpace(m)));
const dimensionOptions=computed(()=>models.value.filter(m=>m.layer==='DWT' && m.role==='DIMENSION' && sameSpace(m)));
const upstreamNames=computed(()=>form.value.upstreamIds.map(id=>modelName(id)).join('、'));
const fieldLineage=computed(()=>models.value.flatMap(m=>m.columns.map(c=>({layer:m.layer,table:qualifiedName(m),column:c.name,source:c.source}))));
const modelName=id=>qualifiedName(models.value.find(m=>String(m.id)===String(id))) || `模型 #${id}`;
const api=(url,method='get',data)=>request({url:`/dp/warehouse${url}`,method,data,timeout:180000,headers:{repeatSubmit:false,hideErrorMessage:true}}).then(r=>r.data);
async function safely(action){error.value='';busy.value=true;try{return await action();}catch(e){error.value=e.message || String(e);ElMessage.error(error.value);}finally{busy.value=false;}}
async function refresh(){loading.value=true;try{warehouseContext.value=await api('/context');[models.value,datasources.value,logs.value]=await Promise.all([api('/models'),warehouseContext.value.canListDatasources?api('/datasources'):Promise.resolve(warehouseContext.value.engines.map(e=>({id:e.datasourceId,name:e.name}))),warehouseContext.value.canReadLogs?api('/logs'):Promise.resolve([])]);if(!form.value.datasourceId)form.value.datasourceId=datasources.value[0]?.id || null;if(!engineForm.value.datasourceId)engineForm.value.datasourceId=datasources.value[0]?.id || null;if(!spaceForm.value.engineId)spaceForm.value.engineId=warehouseContext.value.engines[0]?.id || null;}catch(e){error.value=e.message;}finally{loading.value=false;}}
async function selectModel(model){if(dirty.value && form.value.name){try{await ElMessageBox.confirm('当前修改尚未保存，是否切换模型？','未保存修改',{type:'warning'});}catch{return;}}form.value=JSON.parse(JSON.stringify(model));snapshot.value=JSON.stringify(form.value);preview.value=null;error.value='';activeTab.value='models';editorOpen.value=true;await router.replace({query:{modelId:model.id}});}
async function newModel(){if(!warehouseContext.value.spaces.length){activeTab.value='spaces';ElMessage.info('请先登记数仓空间，再建立分层模型');return;}if(dirty.value && form.value.name){try{await ElMessageBox.confirm('当前修改尚未保存，是否新建模型？','未保存修改');}catch{return;}}form.value=empty();snapshot.value=JSON.stringify(form.value);preview.value=null;error.value='';editorOpen.value=true;activeTab.value='models';nextTick(()=>modelTree.value?.setCurrentKey(null));}
async function newPhysicalVersion(){
  if(dirty.value){ElMessage.warning('请先保存当前模板修改，再创建新版本');return;}
  const next=JSON.parse(JSON.stringify(form.value));
  next.id=null;next.revision=0;next.tableName='';next.managedBatchId=null;
  next.name=`${next.name} / 新版本`;
  next.description='同一逻辑数据集的新物理版本；请配置新的原始接入或加工，既有数据保留。';
  if(next.layer==='ODS'){next.rawTable='';next.selectSql='';}
  form.value=next;snapshot.value=JSON.stringify(next);preview.value=null;activeTab.value='models';editorOpen.value=true;
  await router.replace({query:{}});ElMessage.info('已继承逻辑数据集和字段，请填写本次新表名后保存');
}
function changeLayer(){const space=warehouseContext.value.spaces.find(s=>String(s.id)===String(form.value.spaceId));form.value.databaseName=space?.databases[form.value.layer] || form.value.databaseName;form.value.tableName=space ? '' : form.value.layer.toLowerCase()+'_';form.value.role=roles.value[0].value;form.value.upstreamIds=[];form.value.dimensions=[];form.value.selectSql='';form.value.rawTable='';preview.value=null;}
function changeSpace(){const space=warehouseContext.value.spaces.find(s=>String(s.id)===String(form.value.spaceId));form.value.datasourceId=space?.datasourceId || null;form.value.datasetId=null;changeLayer();}
async function saveEngine(){await safely(async()=>{const engine=await api('/engines','post',engineForm.value);await refresh();spaceForm.value.engineId=engine.id;showEngineForm.value=false;ElMessage.success('Hive 连接验证通过，执行环境已登记');});}
async function saveSpace(){await safely(async()=>{await api('/spaces','post',spaceForm.value);await refresh();spaceInspection.value=[];ElMessage.success('数仓空间绑定已保存，可新建分层模型');});}
async function inspectSpace(space){await safely(async()=>{spaceInspection.value=await api(`/spaces/${space.id}/inspect`);});}
function addColumn(){form.value.columns.push({name:'',type:'STRING',comment:'',source:'',partition:false,key:false});}
async function save(){await safely(async()=>{form.value=await api('/models/encoded','post',{payload:encrypt(JSON.stringify(form.value))});snapshot.value=JSON.stringify(form.value);await router.replace({query:{modelId:form.value.id}});await refresh();ElMessage.success('模型和字段血缘已保存');});}
async function validate(){await safely(async()=>{
  preview.value=null;
  preview.value=await api('/validate/encoded','post',{payload:encrypt(JSON.stringify(form.value))});
  if(preview.value?.dataCheckStatus==='PASS')ElMessage.success('模型定义及当前上游数据的数值 CAST 校验通过');
  else if(preview.value?.dataCheckStatus==='DEFERRED')ElMessage.warning('模型定义通过；上游未物化，数据级数值校验待执行');
  else ElMessage.success('逐层来源和模型定义校验通过');
});}
async function execute(mode){await safely(async()=>{if(mode!=='DDL'){try{await ElMessageBox.confirm(mode==='CHAIN'?'将按血缘依次执行上游和当前模型，仅向空表写入。ODS 保留已有数据；上层已有数据时整链停止，请使用新版本表，或对尚未加工的模型执行本层。':'将执行当前层，仅向空表写入。ODS 保留已有数据；上层已有数据时停止，请新建版本表。','执行数据加工',{confirmButtonText:'执行',cancelButtonText:'取消',type:'warning'});}catch{return;}}await api(`/models/${form.value.id}/execute?mode=${mode}`,'post');await refresh();activeTab.value='runs';ElMessage.success('任务已提交，可查看执行记录');});}
async function showData(){await safely(async()=>{dataRows.value=await api(`/models/${form.value.id}/data`);dataVisible.value=true;});}
function statusLabel(status){return {QUEUED:'等待',RUNNING:'执行中',SUCCESS:'成功',FAILED:'失败',UNKNOWN:'需要核对',BLOCKED:'上游阻塞',INTERRUPTED:'已中断'}[status] || status;}
function statusType(status){return status==='SUCCESS'?'success':['FAILED','UNKNOWN','BLOCKED','INTERRUPTED'].includes(status)?'danger':'info';}
function exportLineage(){const blob=new Blob([JSON.stringify({architecture:layers.map(l=>l.code),exportedAt:new Date().toISOString(),spaces:warehouseContext.value.spaces,engines:warehouseContext.value.engines,models:models.value},null,2)],{type:'application/json'});const a=document.createElement('a');const url=URL.createObjectURL(blob);a.href=url;a.download='qdata-hive-model-lineage.json';a.click();URL.revokeObjectURL(url);}
onBeforeRouteLeave(async()=>{if(!dirty.value || !form.value.name)return true;try{await ElMessageBox.confirm('当前模型有未保存的修改，是否离开？','未保存修改',{confirmButtonText:'离开',cancelButtonText:'继续编辑',type:'warning'});return true;}catch{return false;}});
let timer;onMounted(async()=>{await refresh();const linked=models.value.find(m=>String(m.id)===String(route.query.modelId));if(linked){form.value=JSON.parse(JSON.stringify(linked));editorOpen.value=true;}snapshot.value=JSON.stringify(form.value);timer=setInterval(async()=>{if(executing.value || activeTab.value==='runs'){try{logs.value=await api('/logs');}catch{/* The next refresh displays connection errors. */}}},4000);});onBeforeUnmount(()=>clearInterval(timer));
</script>

<style scoped>

.warehouse-page{color:var(--el-text-color-primary,#333)}
.warehouse-workspace{position:relative}
.namespace-panel{padding:22px}
.space-table{margin:16px 0 24px}
.namespace-panel .form-grid{max-width:1100px}
.engine-heading{margin-top:28px}
.workspace-tools{position:absolute;top:8px;right:15px;z-index:2;display:flex;align-items:center;gap:16px}
.pipeline-hint{font-size:12px;color:#596d82}
.warehouse-tabs:deep(.el-tabs__header){background:#fff;margin:0 0 15px;padding:0 15px;border-radius:2px;box-shadow:0 5px 8px rgba(128,145,165,.1)}
.warehouse-tabs:deep(.el-tabs__nav-wrap){padding-right:400px}
.warehouse-tabs:deep(.el-tabs__nav-wrap::after){height:1px;background:#ebeef5}
.warehouse-tabs:deep(.el-tabs__item){height:48px;font-size:14px}
.warehouse-tabs:deep(.el-tabs__content){overflow:visible}
.warehouse-layout{display:grid;grid-template-columns:300px minmax(0,1fr);gap:15px;align-items:start}
.model-navigation,.model-editor,.secondary-panel{background:#fff;border-radius:2px;box-shadow:0 5px 8px rgba(128,145,165,.1)}
.model-navigation{padding:15px;min-height:calc(100vh - 205px)}
.directory-title{display:flex;justify-content:space-between;align-items:center;font-size:14px;font-weight:500;margin:0 0 14px}
.model-navigation>.el-input:deep(.el-input__wrapper){min-height:40px}
.directory-actions{margin:15px 0 8px}
.model-tree{font-family:inherit;font-size:14px}
.model-tree:deep(.el-tree-node__content){height:auto;min-height:40px;padding-block:6px}
.model-tree:deep(.el-tree-node__content:hover){background:#f5f7fa}
.model-tree:deep(.is-current>.el-tree-node__content){background:var(--el-color-primary-light-9,#ecf5ff);color:var(--el-color-primary,#2666fb)}
.directory-node{display:flex;align-items:center;gap:8px;min-width:0;flex:1;padding-right:8px;min-height:28px}
.directory-label{min-width:0;flex:1}.directory-label>span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px;line-height:20px}
.directory-label>small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#606f80;font-size:12px;line-height:18px;margin-top:2px}
.directory-model{padding-block:2px}.folder-icon{color:var(--el-color-primary-light-3,#79a5eb)}.table-icon{color:#8194a9}.layer-count{color:#7b8998;font-size:12px;margin-left:4px}.directory-empty{font-size:13px;color:#606f80;line-height:1.6;margin-top:18px}
.model-editor{min-width:0;min-height:calc(100vh - 205px);padding:15px 20px 0}
.editor-heading{display:flex;justify-content:space-between;align-items:start;gap:20px;padding-bottom:14px;border-bottom:1px solid #ebeef5;margin-bottom:14px}
.editor-heading h2{font-size:16px;font-weight:500;line-height:24px;margin:0;color:#303133}.editor-heading p{font-size:12px;color:#606f80;line-height:20px;margin:4px 0 0;overflow-wrap:anywhere}.editor-status{display:flex;gap:8px;white-space:nowrap;padding-top:2px}
.layer-notice{background:#f3f7fc;color:#4b637e;border-radius:2px}.layer-notice:deep(.el-alert__title){color:#4b637e;font-size:13px;line-height:20px}.layer-notice:deep(.el-alert__icon){color:#6685a4;font-size:16px}
.definition-form{margin-top:4px}.form-section{padding:14px 0 18px;border-bottom:1px solid #ebeef5}.form-section:last-of-type{border-bottom:0}
.section-title{display:flex;gap:12px;align-items:center;margin:0 0 16px}.section-title h3{font-size:16px;font-weight:500;line-height:24px;margin:0;color:#303133}.section-title>.el-button{margin-left:8px}.section-meta{font-size:12px;color:#606f80}
.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px 24px;max-width:1320px;margin-bottom:16px}
.form-grid:deep(.el-form-item){margin:0}.definition-form:deep(.el-form-item__label){font-weight:400;font-size:14px;line-height:32px}.definition-form:deep(.el-form-item__content){min-width:0}.definition-form:deep(.el-select){width:100%}
.wide-field{max-width:1320px;margin:0}.field-content{width:100%;min-width:0}.field-help{font-size:12px;line-height:20px;color:#596d82;margin:6px 0 0}.sql-field{margin-top:16px}.sql-field:deep(textarea){font-family:ui-monospace,SFMono-Regular,Consolas,monospace;line-height:1.6}
.field-toolbar{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:12px}.field-toolbar>span{font-size:12px;line-height:20px;color:#596d82}.column-editor:deep(.el-input__wrapper){padding-inline:8px}.empty-fields-help{margin-top:10px}
.relation-row{display:grid;grid-template-columns:1fr 20px 1.5fr 1fr 50px;gap:8px;margin:12px 0;align-items:center;max-width:1320px}
.editor-footer{position:sticky;bottom:0;z-index:2;background:#fff;border-top:1px solid #dfe5ec;padding:12px 0 10px;margin-top:16px}.editor-actions{display:flex;align-items:center;gap:16px;flex-wrap:wrap}.action-group{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.action-group .el-button{margin-left:0}.execution-actions{border-left:1px solid #dfe5ec;padding-left:16px}.inspect-actions{margin-left:auto}.execution-hint{font-size:12px;color:#596d82;line-height:20px;margin:8px 0 0}.feedback{margin-top:14px}
.editor-empty{min-height:560px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:36px 20px;color:#778ca2}.editor-empty h2{font-size:18px;font-weight:500;color:#303133;margin:18px 0 10px}.editor-empty p{font-size:14px;line-height:24px;color:#606f80;margin:2px 0}.empty-pipeline{display:flex;gap:24px;flex-wrap:wrap;justify-content:center;margin-top:30px}.empty-pipeline>span{font-size:13px;color:#416182}.empty-pipeline small{display:block;font-size:12px;color:#74859a;margin-top:6px}
.secondary-panel{padding:15px;min-height:calc(100vh - 205px)}.secondary-panel>p{font-size:13px;line-height:22px;color:#596d82;margin:0 0 15px}.lineage-columns{display:grid;grid-template-columns:repeat(5,minmax(180px,1fr));gap:16px;overflow-x:auto;margin-bottom:16px}.lineage-columns h3{font-size:14px;font-weight:500;margin:0 0 14px}.lineage-model{padding:12px 0;border-bottom:1px solid #ebeef5}.lineage-model strong{font-size:14px;font-weight:500}.lineage-model code{display:block;font-size:12px;overflow-wrap:anywhere;margin-top:6px}.lineage-model p,.lineage-model small{font-size:12px;line-height:20px;color:#596d82}.lineage-columns+.el-button{margin-bottom:15px}
.sql-preview{padding:10px 0 20px}.sql-preview h3{font-size:14px;font-weight:500}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light,#f5f7fa);padding:15px;font-size:13px;line-height:1.6;border-radius:2px}
@media(max-width:1500px){.inspect-actions{margin-left:0}.editor-actions{gap:12px}.pipeline-hint{display:none}.warehouse-tabs:deep(.el-tabs__nav-wrap){padding-right:95px}}
@media(max-width:1200px){.warehouse-layout{grid-template-columns:260px minmax(0,1fr)}.model-editor{padding-inline:15px}.form-grid{gap:16px}}
@media(max-width:1100px){.form-grid{grid-template-columns:1fr}.definition-form:deep(.el-form-item__label){line-height:22px;margin-bottom:6px}.execution-actions{padding-left:0;border-left:0}.editor-footer{position:static}}
@media(max-width:800px){.warehouse-layout{grid-template-columns:1fr}.model-navigation{min-height:0;max-height:300px;overflow:auto}.directory-title{margin-bottom:10px}.editor-heading{flex-wrap:wrap}.relation-row{grid-template-columns:1fr}.warehouse-tabs:deep(.el-tabs__item){padding-inline:12px}.editor-empty{min-height:350px}}
</style>
