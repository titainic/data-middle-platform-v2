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

<template>
    <div class="app-container">
        <div slot="header" class="header-container">
            <div class="header-left">
                <div class="blue-bar"></div>
                {{ td('ds.apiEdit.simulation.apiCall') }}
            </div>
            <el-button  size="mini" style="border-radius: 30px !important" round
                       :loading="calling" @click="handleCall">
                {{td('ds.apiEdit.simulation.interfaceCall')}}
            </el-button>
        </div>
        <div class="body-wrapper">
            <el-form v-if="isChange" ref="form" :model="form" :disabled="true" :label-position="labelPosition">
                <el-row :gutter="20">
                    <el-col :span="12">
                        <el-form-item :label="td('ds.apiEdit.simulation.apiName')">
                            <el-input v-model="form.name" />
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item :label="td('ds.apiEdit.simulation.apiVersion')">
                            <el-input v-model="form.apiVersion" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row :gutter="20">
                    <el-col :span="12">
                        <el-form-item :label="td('ds.apiEdit.simulation.requestType')">
                            <!--                            <el-input v-model="form.reqMethod"/>-->
                            <dict-tag :options="ds_api_bas_info_api_method_type" :value="form.reqMethod" />
                        </el-form-item>
                        <!--                        <el-table-column v-if="getColumnVisibility(4)" label="Request method" align="center" prop="reqMethod">-->
                        <!--                            <template #default="scope">-->
                        <!--                                <dict-tag :options="ds_api_bas_info_api_method_type" :value="scope.row.reqMethod"/>-->
                        <!--                            </template>-->
                        <!--                        </el-table-column>-->
                    </el-col>
                    <el-col :span="12">
                        <el-form-item :label="td('ds.apiEdit.simulation.returnFormat')" prop="resDataType">
                            <dict-tag :options="ds_api_bas_info_res_data_type" :value="form.resDataType" />
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-row>
                    <el-col :span="24">
                        <el-form-item :label="td('ds.apiEdit.simulation.callAddress')">
                            <el-input v-model="form.apiUrl" />
                        </el-form-item>
                    </el-col>
                </el-row>
            </el-form>
            <div class="header-container">
                <div class="header-left">
                    <div class="blue-bar"></div>
                    {{td('ds.apiEdit.simulation.requestData')}}
                </div>
            </div>
            <el-row>
                <el-col :span="24">
                    <el-table class="tableStyle" :data="form.reqParams" stripe :max-height="250"
                        style="width: 100%; margin: 15px 0">
                        <el-table-column :label="td('common.display.index')" width="80" align="center">
                            <template #default="scope">
                                <span>{{ scope.$index + 1 }}</span>
                            </template>
                        </el-table-column>
                        <el-table-column prop="paramName" :label="td('ds.apiEdit.simulation.paramName')" align="center" :show-overflow-tooltip="{effect: 'light'}" />
                        <el-table-column prop="nullable" :label="td('ds.apiEdit.simulation.nullable')" align="center" :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                <el-checkbox v-model="scope.row.nullable" true-label="1" false-label="0" disabled />
                            </template>
                        </el-table-column>
                        <el-table-column prop="paramComment" :label="td('common.texts.description')" align="center" :show-overflow-tooltip="{effect: 'light'}" />
                        <el-table-column prop="paramType" :label="td('ds.apiEdit.simulation.paramType')" align="center" :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                <dict-tag :options="ds_api_param_type" :value="scope.row.paramType" />
                                <!--                                <el-select v-model="scope.row.paramType" placeholder="Please select the parameter type" disabled>-->
                                <!--                                    <el-option v-for="dict in paramTypeOptions" :key="dict.id" :label="dict.itemValue"-->
                                <!--                                               :value="dict.itemText"/>-->
                                <!--                                </el-select>-->
                            </template>
                        </el-table-column>
                        <el-table-column prop="whereType" :label="td('ds.apiEdit.simulation.operator')" align="center" :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                <el-select v-model="scope.row.whereType" :placeholder="td('ds.apiEdit.simulation.operatorPlaceholder')" disabled>
                                  <el-option
                                      v-for="dict in da_api_param_operator"
                                      :key="dict.id"
                                      :label="dict.label"
                                      :value="dict.value"
                                  />
                                </el-select>
                            </template>
                        </el-table-column>
                        <el-table-column prop="paramValue" :label="td('ds.apiEdit.simulation.paramValue')" align="center" :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                <el-input v-if="scope.row.paramType != '2'" v-model="scope.row.paramValue"
                                    :placeholder="td('ds.apiEdit.simulation.paramValuePlaceholder')" />
                                <el-input v-else-if="scope.row.paramType === '2'" v-model="scope.row.paramValue"
                                    :placeholder="td('ds.apiEdit.simulation.paramValuePlaceholder')" type="number" />
                            </template>
                        </el-table-column>
                    </el-table>
                </el-col>
            </el-row>
            <div class="header-container">
                <div class="header-left">
                    <div class="blue-bar"></div>
                    {{td('ds.apiEdit.simulation.returnData')}}
                </div>
            </div>
            <el-row>
                <el-col :span="24">
                    <div v-if="apiExecuting">
                        <p v-if="pinnedReleaseId" class="version-hint">当前数据版本：{{ pinnedReleaseId }}。翻页保持此版本，重新调用可读取当前发布。</p>
                        <el-table :data="callData.dataList" stripe border :max-height="250"
                            style="width: 100%; margin: 15px 0">
                            <el-table-column :label="td('common.display.index')" width="80" align="center">
                                <template #default="scope">
                                    <span>{{ scope.$index + 1 }}</span>
                                </template>
                            </el-table-column>
                            <template v-for="(column, index) in callData.columnList" :key="index">
                                <el-table-column :prop="column" :label="column" align="center" :min-width="180"
                                    :show-overflow-tooltip="{effect: 'light'}" />
                            </template>
                        </el-table>
                        <div style="display: flex; justify-content: flex-end; margin-top: 20px;"
                            v-if="callData.dataTotal">
                            <el-pagination v-if="form.resDataType == '1' || form.resDataType == '3'"
                                :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
                                v-model:current-page="callData.pageNum" v-model:page-size="callData.pageSize"
                                :total="callData.dataTotal" @size-change="handleSizeChange"
                                @current-change="handleCurrentChange" />
                        </div>

                    </div>
                    <div v-else class="no-data">{{td('common.noData')}}</div>
                </el-col>
            </el-row>
        </div>
    </div>
</template>

<script setup>
import useDefaultLang from "@/composables/useDefaultLang";
const { td } = useDefaultLang();
import { serviceTesting } from '@/api/ds/api/api.js';
const { proxy } = getCurrentInstance();
const { ds_api_bas_info_api_method_type, ds_api_param_type, ds_api_bas_info_res_data_type,  da_api_param_operator } =
    proxy.useDict(
        'ds_api_bas_info_api_method_type',
        'ds_api_param_type',
        'ds_api_bas_info_res_data_type',
        "da_api_param_operator"
    );

const props = defineProps({
    data: {
        type: Object,
        default: function () {
            return {};
        }
    },
    form: {
        type: Object,
        default: {}
    },
    reqMethodOptions: {
        type: Array,
        required: true
    },

    resTypeOptions: {
        type: Array,
        required: true
    },
    whetherOptions: {
        type: Array,
        required: true
    },
    statusOptions: {
        type: Array,
        required: true
    },
    isChange: {
        type: Boolean,
        default: true
    }
});
const data = reactive({
    title: td('ds.apiEdit.simulation.dataApiCall'),
    // display toggle
    showOptions: {
        data: {},
        showList: true,
        showAdd: false,
        showEdit: false,
        showDetail: false,
        showExample: false
    },
    activeTabName: 'table0',
    apiHeader: {},
    apiHeaderList: [],
    // operator data dictionary
    whereTypeOptions: [],
    // Parameter type data dictionary
    paramTypeOptions: [],
    apiExecuting: false,
    callData: {
        dataList: [],
        columnList: [],
        pageNum: 1,
        pageSize: 20,
        dataTotal: 0
    },
    bashUrl: null
});

const {
    apiHeader,
    apiHeaderList,
    whereTypeOptions,
    activeTabName,
    showOptions,
    title,
    paramTypeOptions,
    apiExecuting,
    callData,
    bashUrl
} = toRefs(data);

function showCard() {
    this.$emit('showCard', this.showOptions);
}

function handleSizeChange(val) {
    callData.value.pageNum = 1;
    callData.value.pageSize = val;
    handleCall(true);
}

function handleCurrentChange(val) {
    callData.value.pageNum = val;
    handleCall(true);
}

const pinnedReleaseId = ref('');
const calling = ref(false);
let requestVersion = 0;
onBeforeUnmount(() => { requestVersion++; });
watch(() => props.form.id, () => {
    requestVersion++;
    calling.value = false;
    apiExecuting.value = false;
    pinnedReleaseId.value = '';
    callData.value.dataList = [];
    callData.value.columnList = [];
    callData.value.dataTotal = 0;
});
function handleCall(pagination = false) {
    if (pagination !== true) {callData.value.pageNum=1;pinnedReleaseId.value='';}
    let isNull = false;
    props.form.reqParams.forEach((param) => {
        if (
            param.nullable == '0' &&
            (param.paramValue == null ||
                param.paramValue == undefined ||
                param.paramValue == '')
        ) {
            proxy.$message.warning(td('ds.apiEdit.simulation.nullableWarning') + '‘' + param.paramName + '’' + td('ds.apiEdit.simulation.cannotBeEmpty'));
            isNull = true;
            return;
        }
    });
    if (isNull) {
        return;
    }
    const data = {};
    data.pageNum = callData.value.pageNum;
    data.pageSize = callData.value.pageSize;
    for (const originalParam of props.form.reqParams) {
        let param = JSON.parse(JSON.stringify(originalParam));
        if (param.paramType == 2) {
            if (
                param.paramValue != null &&
                param.paramValue != '' &&
                param.paramValue != undefined
            ) {
                param.paramValue = parseInt(param.paramValue);
            }
        }
      if (param.paramType == 5) {
        if (
            param.paramValue != null &&
            param.paramValue != '' &&
            param.paramValue != undefined
        ) {
          try {
            param.paramValue = JSON.parse(param.paramValue);
          } catch (error) {
            proxy.$message.warning(td('ds.apiEdit.simulation.nullableWarning') + '‘' + param.paramName + '’' + td('ds.apiEdit.simulation.formatError'));
            return;
          }
        }
      }
        if (param.paramValue != null && param.paramValue !== '' && !(pagination === true && ['pageNum','pageSize'].includes(param.paramName))) data[param.paramName] = param.paramValue;
    }
    if (!data.releaseId && pinnedReleaseId.value) data.releaseId=pinnedReleaseId.value;
    props.form.params = data;
    let params = {};
    //Give all the values of props.form to params
    Object.assign(params, props.form);
    params.reqParamsList = params.reqParams;
    params.resParamsList = params.resParams;
    //Remove reqParams and resParams
    delete params.reqParams;
    delete params.resParams;
    delete params.createTime;
    delete params.updateTime;
    if (!['1', '2'].includes(String(props.form.reqMethod))) return;
    const version = ++requestVersion;
    calling.value = true;
    apiExecuting.value = false;
    callData.value.dataList = [];
    callData.value.columnList = [];
    callData.value.dataTotal = 0;
    serviceTesting(params).then((response) => {
        if (version !== requestVersion) return;
        if (response.code !== 200) {
            proxy.$message.warning(td('ds.apiEdit.simulation.callFailed'));
            return;
        }
        const result = response.data;
        const resultType = String(props.form.resDataType);
        const rows = resultType === '3' ? result.data : resultType === '2' ? result : [result];
        pinnedReleaseId.value = result?.releaseId || '';
        if (result?.pageNum) callData.value.pageNum = result.pageNum;
        if (result?.pageSize) callData.value.pageSize = result.pageSize;
        callData.value.dataList = rows || [];
        callData.value.columnList = rows?.length ? Object.keys(rows[0]) : [];
        callData.value.dataTotal = resultType === '3' ? (result.total || 0) : (rows?.length || 0);
        apiExecuting.value = true;
        proxy.$message.success(td('ds.apiEdit.simulation.callSuccess'));
    }).catch(() => {
        // The shared request client displays the server error; old rows stay cleared.
    }).finally(() => {
        if (version === requestVersion) calling.value = false;
    });
}
</script>

<style lang="scss" scoped>
.version-hint { color: var(--el-text-color-secondary); font-size: 13px; overflow-wrap: anywhere; }
.app-container {
    margin-top: -5px;
    min-height: 65vh;
    margin-left: 0px;
    // padding: 20px;
    background-color: #ffffff;
    border-radius: 8px;

    // box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);

    .header-text {
        display: flex;
        align-items: center;
        margin-bottom: 3px;
        margin: 10px 0;
    }

    .section-title {
        width: 100%;
        height: 36px;
        background-color: #f8f8f9;
        display: flex;
        align-items: center;
        padding-left: 10px;
        margin-bottom: 10px;
        font-size: 16px;
        font-weight: bold;
        color: #333;
    }

    .section-title span {
        display: flex;
        align-items: center;
    }

    .blue-bar {
        background-color: #2666fb;
        width: 5px;
        height: 20px;
        margin-right: 10px;
        border-radius: 2px;
    }

    .header-container {
        height: 36px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 5px 0;
        margin: 10px 0;
        border-radius: 4px;
    }

    .header-left {
        display: flex;
        align-items: center;
        font-size: 16px;
        line-height: 24px;
        font-style: normal;
    }

    .el-form {
        margin-top: 20px;
    }

    .el-form-item {
        margin-bottom: 15px;
    }

    .el-input,
    .el-select {
        width: 100%;
    }

    .el-button {
        transition: background-color 0.3s;

        &:hover {
            background-color: #2666fb;
            color: #ffffff;
        }
    }

    .tableStyle {
        font-size: 14px;
        margin: 0px !important;

        ::v-deep {
            th.el-table__cell>.cell {
                padding: 0 5px !important;
                font-style: normal;
                text-transform: none;
                background-color: #f0f2f5;
                color: #333;
                white-space: nowrap;
            }

            .el-table__row {
                .el-table__cell {
                    padding: 4px 0 !important;
                    transition: background-color 0.3s;

                    &:hover {
                        background-color: #f5f7fa;
                    }
                }
            }

            .el-table__header-wrapper th {
                padding: 4px 0;
            }
        }
    }

    .no-data {
        display: flex;
        justify-content: center;
        align-items: center;
        height: 250px;
        text-align: center;
        font-size: 14px;
        color: #909399;
        background-color: #f8f8f9;
        border: 1px solid #ebeef5;
        border-radius: 4px;
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
    }
}
</style>