<!--
  Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.

  This file is part of qData Data Middle Platform (Open Source Edition).

  qData is licensed under Apache License 2.0 with additional qData terms.
  You may use qData for commercial purposes, but you may not remove, hide,
  modify, or replace the qData logo, copyright notices, license notices,
  or attribution information without a separate commercial license.

  White-label use, OEM distribution, rebranding, or presenting qData as
  another product requires separate commercial authorization from
  Jiangsu Qiantong Technology Co., Ltd.

  Business License: https://community.qdata.tech/business/policy.html
  See the LICENSE file in the project root for full license information.
-->

<!-- problemData.vue -->
<template>
    <el-dialog v-model="visible" :title="td('da.qualityTaskLog.problemData.title')" class="medium-dialog" destroy-on-close>
        <div class="dialog-header">
            <el-form :inline="true" @selection-change="handleSelectionChange"
                style="display: inline-block; text-align: left;" :label-position="labelPosition">
                <el-form class="btn-style" :model="queryParams" ref="queryRef" :inline="true"
                    @submit.prevent :label-position="labelPosition">
                    <el-form-item v-for="(fieldName, index) in visibleFields" :key="index"
                        :label="getFieldLabel(fieldName)" :prop="'keyWordData.' + fieldName" :label-position="labelPosition">
                        <el-input v-model="queryParams.keyWordData[fieldName]"
                            :placeholder="`${td('da.qualityTaskLog.inputPlaceholder')} ${getFieldLabel(fieldName)}`" clearable class="el-form-input-width" />
                    </el-form-item>

                    <template v-if="showMoreFields">
                        <el-form-item v-for="(fieldName, index) in moreFields" :key="'more-' + index"
                            :label="getFieldLabel(fieldName)" :prop="'keyWordData.' + fieldName" :label-position="labelPosition">
                            <el-input v-model="queryParams.keyWordData[fieldName]"
                                :placeholder="`${td('da.qualityTaskLog.inputPlaceholder')} ${getFieldLabel(fieldName)}`" clearable
                                class="el-form-input-width" />
                        </el-form-item>
                    </template>
                    <!-- More/collapse button -->
                    <el-form-item v-if="columnFields.length > 3" :label-position="labelPosition">
                        <el-button type="text" @click="toggleMore">
                            {{ showMoreFields ? td('da.qualityTaskLog.problemData.collapse') : td('da.qualityTaskLog.problemData.more') }}
                            <el-icon>
                                <ArrowUp v-if="showMoreFields" />
                                <ArrowDown v-else />
                            </el-icon>
                        </el-button>
                    </el-form-item>

                    <el-form-item :label="td('da.qualityTaskLog.problemData.processingState')" prop="repair">
                        <el-select v-model="queryParams.repair" clearable style="width:160px" :placeholder="td('da.qualityTaskLog.problemData.allStates')">
                            <el-option v-for="item in quality_log_data_repair" :key="item.value" :label="item.label" :value="Number(item.value)" />
                        </el-select>
                    </el-form-item>
                    <!-- Query/reset buttons -->
                    <el-form-item :label-position="labelPosition">
                        <el-button plain type="primary" @click="handleQuery" @mousedown.prevent>
                            <i class="iconfont-mini icon-a-zu22377 mr5"></i>{{ td('common.button.query') }}
                        </el-button>
                        <el-button @click="resetQuery" @mousedown.prevent>
                            <i class="iconfont-mini icon-a-zu22378 mr5"></i>{{ td('common.button.reset') }}
                        </el-button>
                    </el-form-item>
                </el-form>
            </el-form>
            <el-row v-if="canManageProblems" align="middle" justify="space-between" style="width: 100%">
                <el-col :span="24">
                    <el-space wrap>
                        <el-button link type="danger" :icon="SemiSelect" :disabled="!canBatch('3') || loading"
                            @click="addIgnore">
                            {{ td('da.qualityTaskLog.problemData.batchIgnore') }}
                        </el-button>
                        <el-button link type="warning" :icon="SemiSelect" :disabled="!canBatch('8') || loading" @click="changeProblemState('8')">{{ td('da.qualityTaskLog.problemData.batchDefer') }}</el-button>
                        <el-button link type="info" :icon="SemiSelect" :disabled="!canBatch('4') || loading" @click="changeProblemState('4')">{{ td('da.qualityTaskLog.problemData.batchReFix') }}</el-button>
                        <el-button link type="info" :icon="RefreshLeft" :disabled="!canBatch('5') || loading" @click="changeProblemState('5')">{{ td('da.qualityTaskLog.problemData.batchRevokeIgnored') }}</el-button>
                        <el-button link type="info" :icon="RefreshLeft" :disabled="!canBatch('6') || loading" @click="changeProblemState('6')">{{ td('da.qualityTaskLog.problemData.batchRevokeDeferred') }}</el-button>
                        <el-button link type="info" :icon="RefreshLeft" :disabled="!canBatch('7') || loading" @click="changeProblemState('7')">{{ td('da.qualityTaskLog.problemData.batchReturn') }}</el-button>
                    </el-space>
                </el-col>
            </el-row>
        </div>

        <p class="problem-state-hint">{{ td('da.qualityTaskLog.problemData.stateOnlyNotice') }}</p>
        <el-table :data="filteredData" border style="width: 100%; " @selection-change="handleSelectionChange"
            height="55vh" v-loading="loading">
            <el-table-column type="selection" width="50" align="center" />
            <el-table-column :label="td('da.qualityTaskLog.problemData.problemData')" align="center" width="300" :show-overflow-tooltip="{effect: 'light'}">
                <template #default="scope">
                    <div class="data-field" :title="td('da.qualityTaskLog.problemData.viewAll')" @click="openDetails(scope.row)">
                        <div v-for="(item, index) in getLabelAndValueFromStr(obj.columnName, obj.rule, scope.row.dataJsonStr)"
                            :key="index">
                            {{ item }}
                        </div>
                    </div>
                </template>
            </el-table-column>
            <!-- Evaluation field columns -->
            <el-table-column :label="td('da.qualityTaskLog.problemData.evaluationField')" align="center" :show-overflow-tooltip="{effect: 'light'}" width="300">
                <template #default="scope">
                    <div v-for="(item, index) in getLabelAndValueFromStr(obj.columnName, obj.rule, scope.row.dataJsonStr)"
                        :key="index">
                        {{ item }}
                    </div>
                </template>
            </el-table-column>
            <el-table-column :label="td('da.qualityTaskLog.problemData.severity')" align="center" prop="warningLevel" :show-overflow-tooltip="{effect: 'light'}">
                <template #default="scope">
                    <dict-tag :options="quality_warning_status" :value="obj?.rule?.warningLevel" />
                </template>

            </el-table-column>
            <el-table-column :label="td('da.qualityTaskLog.problemData.isFixed')" align="center" prop="repair" :show-overflow-tooltip="{effect: 'light'}">
                <template #default="scope">
                    <dict-tag :options="quality_log_data_repair" :value="scope.row.repair" /></template>
            </el-table-column>
            <el-table-column :label="td('da.qualityTaskLog.problemData.errorDesc')" align="center" prop="errDescription" :show-overflow-tooltip="{effect: 'light'}" width="300">
                <template #default="scope">

                    {{ obj.rule.errDescription || '-' }}
                </template>
            </el-table-column>

            <el-table-column :label="td('da.qualityTaskLog.problemData.fixSuggestion')" align="center" prop="suggestion" :show-overflow-tooltip="{effect: 'light'}" width="300">
                <template #default="scope">
                    {{ obj.rule?.suggestion || '-' }}
                </template>
            </el-table-column>

            <el-table-column :label="td('da.qualityTaskLog.problemData.evaluationDate')" align="center" prop="time" width="100">
                <template #default="scope">
                    {{ scope.row.time ? moment(scope.row.time).format('YYYY-MM-DD') : '-' }}
                </template>
            </el-table-column>
            <el-table-column :label="td('common.texts.remark')" align="center" prop="remark" :show-overflow-tooltip="{effect: 'light'}" width="300">
                <template #default="scope">
                    {{ scope.row.remark || '-' }}
                </template>
            </el-table-column>
            <el-table-column :label="td('common.texts.operation')" align="center" width="350" fixed="right">
                <template #default="scope">
                    <el-button link type="primary" v-if="canManageProblems && scope.row.repair != 2" @click="addIgnore(scope.row)"
                        icon="CircleClose">
                        {{ td('da.qualityTaskLog.problemData.ignore') }}
                    </el-button>

                    <el-button link type="warning" v-if="canManageProblems && Number(scope.row.repair)===0" @click="changeProblemState('8',scope.row)">{{ td('da.qualityTaskLog.problemData.defer') }}</el-button>
                    <el-button link type="primary" @click="showHistory(scope.row)">{{ td('da.qualityTaskLog.problemData.history') }}</el-button>
                    <el-button link type="primary" v-if="canManageProblems" @click="addComment(scope.row)" icon="Edit">
                        {{ td('da.qualityTaskLog.problemData.addRemark') }}
                    </el-button>

                    <el-button link type="primary" @click="openupDetails(scope.row)" icon="Tools"
                        v-if="canManageProblems && scope.row.repair == 0">
                        {{ td('da.qualityTaskLog.problemData.handle') }}
                    </el-button>
                </template>

            </el-table-column>
        </el-table>
        <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize" @pagination="getList" />
        <template #footer>
            <el-button @click="close">{{ td('common.button.close') }}</el-button>
        </template>
        <el-dialog v-model="historyVisible" :title="td('da.qualityTaskLog.problemData.history')" width="1100px" append-to-body>
            <el-table :data="historyRows" v-loading="historyLoading" max-height="520" border>
                <el-table-column type="expand">
                    <template #default="scope">
                        <div class="problem-history-json" v-if="scope.row.oldData || scope.row.newData">
                            <div><strong>{{ td('da.qualityTaskLog.problemData.beforeData') }}</strong><pre>{{ scope.row.oldData }}</pre></div>
                            <div><strong>{{ td('da.qualityTaskLog.problemData.afterData') }}</strong><pre>{{ scope.row.newData }}</pre></div>
                        </div>
                        <p v-else class="problem-state-hint">{{ td('da.qualityTaskLog.problemData.noSourceChange') }}</p>
                    </template>
                </el-table-column>
                <el-table-column :label="td('da.qualityTaskLog.problemData.operationTime')" width="180"><template #default="scope">{{ moment(Number(scope.row.createdAt)).format('YYYY-MM-DD HH:mm:ss') }}</template></el-table-column>
                <el-table-column :label="td('common.texts.operation')" width="180"><template #default="scope">{{ actionLabel(scope.row.action) }}</template></el-table-column>
                <el-table-column :label="td('da.qualityTaskLog.problemData.stateChange')" width="180"><template #default="scope">{{ stateLabel(scope.row.oldRepair) }} → {{ stateLabel(scope.row.newRepair) }}</template></el-table-column>
                <el-table-column :label="td('da.qualityTaskLog.problemData.operator')" prop="operator" width="120" />
                <el-table-column :label="td('da.qualityTaskLog.problemData.reason')" prop="reason" min-width="160" show-overflow-tooltip />
                <el-table-column :label="td('common.texts.remark')" min-width="180" show-overflow-tooltip><template #default="scope">{{ scope.row.oldRemark || '-' }} → {{ scope.row.newRemark || '-' }}</template></el-table-column>
            </el-table>
        </el-dialog>
        <ProblemDataDetailDialog ref="detailDialogRef" />
        <updateDataDialog ref="updateDialogRef" @ok="onSave" />
        <el-dialog v-model="commentDialogVisible" :title="td('da.qualityTaskLog.problemData.remarkTitle')" width="800px" @close="resetComment">
            <el-form :model="commentForm" label-width="80px" :label-position="labelPosition">
                <el-form-item :label="td('common.texts.remark')" :label-position="labelPosition">
                    <el-input type="textarea" v-model="commentForm.comment" rows="4" :placeholder="td('da.qualityTaskLog.problemData.remarkPlaceholder')" />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="commentDialogVisible = false">{{ td('common.button.cancel') }}</el-button>
                <el-button type="primary" @click="submitComment">{{ td('common.button.save') }}</el-button>
            </template>
        </el-dialog>

    </el-dialog>
</template>

<script setup>
import useDefaultLang from "@/composables/useDefaultLang"
import {
    SemiSelect,
    RefreshLeft,
    ArrowUp,
    ArrowDown,
    Search,
    InfoFilled,
} from '@element-plus/icons-vue'
import moment from 'moment'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ref, computed, getCurrentInstance } from 'vue'
import updateDataDialog from './problemDataEdit.vue'
import ProblemDataDetailDialog from './problemDataDetail.vue'
import { pageErrorData, updateErrorData, errorDataHistory, problemCapabilities } from "@/api/da/quality/qualityTaskLog"

const canManageProblems=ref(false)
const { td } = useDefaultLang();
const { proxy } = getCurrentInstance()
const { quality_warning_status, quality_log_data_repair } = proxy.useDict(
    'quality_warning_status',
    'quality_log_data_repair'
)
// Parse columnName to array
const columnFields = computed(() => {
    return obj.value.columnName
        ? obj.value.columnName.split(',').map(name => name.trim())
        : []
})

const showMoreFields = ref(false)

const visibleFields = computed(() => columnFields.value.slice(0, 3))

const moreFields = computed(() => columnFields.value.slice(3))

function toggleMore() {
    showMoreFields.value = !showMoreFields.value
}
function getFieldLabel(fieldName) {
    const evaCols = obj.value.rule?.evaColumns
    if (!evaCols) return fieldName

    const match = Array.isArray(evaCols)
        ? evaCols.find(col => col.name?.toLowerCase?.() === fieldName.toLowerCase())
        : Object.values(evaCols).find(col => col.name?.toLowerCase?.() === fieldName.toLowerCase())

    return match?.name || fieldName
}
async function handleUpdateErrorData(params) {
    if(!canManageProblems.value)return false
    loading.value = true
    try {
        const res = await updateErrorData(params)
        if (Number(res?.code)!==200 || res?.data!==true) {
            ElMessage.error(td('da.qualityTaskLog.problemData.updateFailed'))
            return false
        }
        selectedRows.value=[]
        await getList()
        ElMessage.success(td('da.qualityTaskLog.problemData.updated'))
        return true
    } catch (error) {
        return false // The request interceptor displays the backend error; keep the editor open.
    } finally {
        loading.value = false
    }
}
const allowedStates={'3':[0,1,3],'4':[1],'5':[2],'6':[3],'7':[1,2,3],'8':[0]}
const actionKeys={'3':'batchIgnore','4':'batchReFix','5':'batchRevokeIgnored','6':'batchRevokeDeferred','7':'batchReturn','8':'batchDefer'}
const auditKeys={IGNORE:'batchIgnore',REOPEN_REPAIR:'batchReFix',REVOKE_IGNORE:'batchRevokeIgnored',REVOKE_DEFER:'batchRevokeDeferred',RETURN:'batchReturn',DEFER:'defer',REPAIR:'handle',REMARK:'addRemark'}
function canBatch(action) {
    if(!canManageProblems.value)return false
    const rows=tableData.value.filter(row=>selectedRows.value.includes(row.id))
    return rows.length>0 && rows.length===selectedRows.value.length && rows.every(row=>allowedStates[action].includes(Number(row.repair)))
}
function stateLabel(state) {return quality_log_data_repair.value?.find(item=>Number(item.value)===Number(state))?.label || String(state)}
function actionLabel(action) {return auditKeys[action]?td('da.qualityTaskLog.problemData.'+auditKeys[action]):action}
async function changeProblemState(action,row) {
    const ids=row?.id?[row.id]:[...selectedRows.value]
    const candidates=tableData.value.filter(item=>ids.includes(item.id))
    if (!ids.length || candidates.length!==ids.length || !candidates.every(item=>allowedStates[action].includes(Number(item.repair)))) {
        ElMessage.warning(td('da.qualityTaskLog.problemData.invalidSelection'));return
    }
    const label=td('da.qualityTaskLog.problemData.'+actionKeys[action])
    const message=td('da.qualityTaskLog.problemData.stateConfirm',undefined,{action:label,count:ids.length})
    let reason=''
    try {
        if (action==='7' || action==='8') {
            const answer=await ElMessageBox.prompt(message,td('da.qualityTaskLog.problemData.reason'),{
                inputType:'textarea',inputPlaceholder:td('da.qualityTaskLog.problemData.reasonPlaceholder'),
                inputValidator:value=>value?.trim().length>0 && value.trim().length<=1000 || td('da.qualityTaskLog.problemData.reasonRequired'),
                confirmButtonText:td('common.button.confirm'),cancelButtonText:td('common.button.cancel')
            })
            reason=answer.value.trim()
        } else await proxy.$modal.confirm(message)
    } catch (error) {return}
    await handleUpdateErrorData({reportId:obj.value.id,errorDataId:ids,updateType:action,reason})
}
const historyVisible=ref(false)
const historyLoading=ref(false)
const historyRows=ref([])
async function showHistory(row) {
    historyVisible.value=true;historyLoading.value=true;historyRows.value=[]
    try {const res=await errorDataHistory({reportId:obj.value.id,id:row.id});historyRows.value=res.data||[]}
    finally {historyLoading.value=false}
}

let row = ref(null)
let obj = ref({})
const detailDialogRef = ref(null)
const updateDialogRef = ref(null)
const commentDialogVisible = ref(false)
const commentForm = ref({ comment: '' })
let commentTarget = ref(null)
let total = ref(0)
let loading = ref(false)
const visible = ref(false)
const search = ref('')
const selectedRows = ref([])
const showDetailsDialog = ref(false)
const detailData = ref({})
const advancedVisible = ref(false)
const filters = ref({
    position: '',
    severity: '',
})
const tableData = ref([])

// Query parameters
let queryParams = ref({
    pageNum: 1,
    pageSize: 10,
    id: '',
    keyWordData: '',
    repair: null,
})

// Search and reset
function handleQuery() {
    queryParams.value.pageNum = 1
    getList()
}

function resetQuery() {
    proxy.resetForm('queryRef')
    handleQuery()
}

// Fetch list data
async function getList() {
    loading.value = true
    try {
        const res = await pageErrorData(queryParams.value)
        
        if (res.code == '200') {
            selectedRows.value=[]
            tableData.value = res.data.content || []
            total.value = res.data?.totalElements || 0
        }
    } finally {
        loading.value = false
    }
}

// Selection change
function handleSelectionChange(val) {
    selectedRows.value = val.map(item => item.id)
}

// Ignore data
async function addIgnore(row) {return changeProblemState('3',row)}

function resetComment() {commentForm.value.comment='';row.value=null}

// Add remark
function addComment(obj) {
    row.value = obj
    commentForm.value.comment = obj.remark
    commentDialogVisible.value = true
}

async function submitComment() {
    if (!commentForm.value.comment.trim()) {
        ElMessage.warning(td('da.qualityTaskLog.problemData.remarkRequired'))
        return
    }

    const saved=await handleUpdateErrorData({
        id: row.value.id,
        reportId: obj.value.id,
        remark: commentForm.value.comment,
        updateType: '2',
    })

    if (saved) commentDialogVisible.value = false
}

// Save data
async function onSave(updatedData, oldData, keyWordData) {
    updateDialogRef.value?.setSaving(true)
    const saved=await handleUpdateErrorData({
        keyWordData,
        updatedData,
        oldData,
        tableName: obj.value.tableName,
        datasourceId: obj.value.datasourceId,
        reportId: obj.value.id,
        updateType: '1',
    })
    updateDialogRef.value?.finishSave(saved)
}

// Open detail dialog
async function openDetails(row) {
    detailDialogRef.value?.open(row, obj.value.rule)
}

async function openupDetails(row) {
    updateDialogRef.value?.open(row, obj.value.rule, obj.value.columnName)
}

// Advanced filter
const toggleAdvanced = () => {
    advancedVisible.value = !advancedVisible.value
}

const applyAdvancedFilters = () => {
    console.log('Apply advanced filters:', filters.value)
}

const resetFilters = () => {
    filters.value = {
        position: '',
        severity: '',
    }
}

// Display field formatting
function getLabelAndValueFromStr(columnName, rule, dataJsonStr) {
    if (!columnName || !dataJsonStr) return ['-']

    let jsonData
    try {
        jsonData = typeof dataJsonStr === 'string' ? JSON.parse(dataJsonStr) : dataJsonStr
        if (!jsonData || typeof jsonData !== 'object') return ['-']
    } catch (e) {
        console.warn('dataJsonStr parse failed:', e)
        return ['-']
    }

    let evaColumns = []
    try {
        evaColumns = Array.isArray(rule?.evaColumns)
            ? rule.evaColumns
            : Object.values(rule?.evaColumns || {})
    } catch (err) {
        console.warn('rule.evaColumns parse failed', err)
        return ['-']
    }

    const fieldNames = columnName.split(',').map(n => n.trim())

    return fieldNames.map(name => {
        const lowerName = name.toLowerCase()
        const match = evaColumns.find(col => col.name?.toLowerCase?.() === lowerName)
        const label = match?.label || name
        const value = Object.keys(jsonData).find(key => key.toLowerCase() === lowerName)
        const finalValue = value ? jsonData[value] : '-'
        return `${label}：${finalValue}`
    })
}

function parseHistoricalRule(value) {
    try {
        const rule = typeof value === 'string' ? JSON.parse(value) : value
        return rule && typeof rule === 'object' && !Array.isArray(rule) ? rule : {}
    } catch { return {} }
}

// Open main dialog
async function open(row) {
    canManageProblems.value=false;const reportId=String(row.id);problemCapabilities(reportId).then(r=>{if(String(obj.value.id)===reportId)canManageProblems.value=r.data?.edit===true;}).catch(()=>{});
    selectedRows.value=[]
    queryParams.value.pageNum=1
    queryParams.value.repair=null
    obj.value = {
        ...row,
        rule: parseHistoricalRule(row.rule),
    }
    // Initialize fields in keyWordData
    const fieldNames = obj.value.columnName?.split(',') || []
    const keyWordData = {}
    fieldNames.forEach(name => {
        keyWordData[name.trim()] = ''
    })
    queryParams.value.keyWordData = keyWordData
    queryParams.value.reportId = row.id
    getList()
    visible.value = true
}

const close = () => (visible.value = false)
const closeDetails = () => (showDetailsDialog.value = false)
const handleProcess = row => (row.processing = true)

const filteredData = computed(() => {
    return tableData.value.filter(item => {
        const matchesSearch =
            !search.value ||
            item.employeeName.includes(search.value) ||
            item.employeeId.includes(search.value)
        const matchesPosition = !filters.value.position || item.position.includes(filters.value.position)
        const matchesSeverity = !filters.value.severity || item.severity === filters.value.severity
        return matchesSearch && matchesPosition && matchesSeverity
    })
})

// Expose methods
defineExpose({ open, close })
</script>

<style scoped>
.data-field {
    display: flex;
    flex-direction: column;
    align-items: center;
    color: #333;
    cursor: pointer;
    max-height: 80px;
    overflow-y: scroll;
    scrollbar-width: none;
    -ms-overflow-style: none;
    background-color: #c3dcfd;
    border-radius: 5px;
    padding: 8px;
    box-sizing: border-box;
    text-align: center;
}

.data-field::-webkit-scrollbar {
    display: none;
}

.popover-content {
    padding: 10px;
}

.popover-multi-line {
    max-width: 260px;
}

.popover-line {
    line-height: 24px;
    border-bottom: 1px solid #eee;
    padding: 4px 0;
    color: #333;
}

.popover-line:last-child {
    border-bottom: none;
}

.dialog-header {
    padding-bottom: 12px;
    border-bottom: 1px solid #ebeef5;
}

.advanced-filters {
    margin-top: 8px;
}

.filter-form {
    width: 100%;
}

.problem-state-hint { margin: 10px 0; color: #606266; font-size: 12px; line-height: 1.6; }
.problem-history-json { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; padding: 16px; }
.problem-history-json pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
</style>
