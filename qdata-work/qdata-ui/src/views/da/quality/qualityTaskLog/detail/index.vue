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
    <div class="app-container stagingIndex" v-loading="loading">
        <div style="display:flex;justify-content:flex-end;margin-bottom:16px">
            <el-button :loading="snapshotLoading" @click="showExecutionSnapshot">查看本次规则与对象</el-button>
        </div>
        <el-dialog v-model="snapshotOpen" title="本次质量执行快照" width="900px" append-to-body>
            <el-empty v-if="!executionSnapshot" description="该历史执行没有保存完整快照，不能用当前规则代替当时的配置" />
            <template v-else>
                <p>执行记录 {{ executionSnapshot.log_id }} · 质量任务 {{ executionSnapshot.task_id }}</p>
                <p style="overflow-wrap:anywhere;color:var(--el-text-color-secondary)">定义摘要 {{ executionSnapshot.definition_sha256 }}</p>
                <el-table :data="snapshotPlan.objects" border>
                    <el-table-column prop="datasourceId" label="连接编号" width="110" />
                    <el-table-column prop="tableName" label="固定检查对象" min-width="300" />
                </el-table>
                <el-table :data="snapshotPlan.rules" border style="margin-top:16px">
                    <el-table-column prop="name" label="本次启用规则" min-width="170" />
                    <el-table-column prop="tableName" label="检查对象" min-width="220" show-overflow-tooltip />
                    <el-table-column prop="evaColumn" label="字段" min-width="140" />
                    <el-table-column prop="whereClause" label="范围条件" min-width="140" show-overflow-tooltip />
                    <el-table-column prop="rule" label="固定参数" min-width="220" show-overflow-tooltip />
                </el-table>
            </template>
        </el-dialog>
        <!-- Top area: score + line chart -->
        <el-row gutter="20" class="top-section">
            <!-- Left score -->
            <el-col :xs="24" :sm="24" :md="12" class="stats-panel">
                <div class="module-8 border-item">
                    <div class="border-item-head">
                        <span class="head-title">{{ td('da.qualityTaskLog.detail.qualityDimensionStats') }}</span>
                    </div>
                    <div class="border-item-body">
                        <div class="overall-score">
                            <span>{{ td('da.qualityTaskLog.detail.overallQualityScore') }}</span>
                            <span class="score" :class="getScoreClass(overallScore)">
                                {{ overallScore ?? '-' }}
                            </span>
                        </div>
                        <el-table :data="summaryList" border size="small" style="margin-top: 12px" height="246">
                            <el-table-column prop="dimensionType" :label="td('da.qualityTaskLog.detail.qualityDimension')" align="center">
                                <template #default="scope">
                                    <dict-tag :options="att_rule_audit_q_dimension" :value="scope.row.dimensionType" />
                                </template>

                            </el-table-column>
                            <el-table-column prop="succesTotal" :label="td('da.qualityTaskLog.detail.ruleCount')" align="center">
                                <template #default="scope">{{ scope.row.succesTotal || '-' }}</template>
                            </el-table-column>
                            <el-table-column prop="proportion" :label="td('da.qualityTaskLog.detail.problemRatio')" align="center">
                                <template #default="scope">
                                    {{ scope.row.proportion != null ? scope.row.proportion + '%' : '-' }}
                                </template>

                            </el-table-column>
                            <el-table-column :label="td('da.qualityTaskLog.detail.trend')" align="center">
                                <template #default="{ row }">
                                    <template v-if="row.trendType == '-3'">
                                        -
                                    </template>
                                    <template v-else-if="row.trendType == '1'">
                                        <el-icon color="green">
                                            <ArrowUp />
                                        </el-icon>
                                    </template>
                                    <template v-else>
                                        <el-icon color="red">
                                            <ArrowDown />
                                        </el-icon>
                                    </template>
                                </template>
                            </el-table-column>

                        </el-table>
                    </div>
                </div>
            </el-col>

            <!-- Right line chart -->
            <el-col :xs="24" :sm="24" :md="12" class="trend-chart-panel">
                <div class="module-8 border-item">
                    <div class="border-item-head">
                        <span class="head-title">{{ td('da.qualityTaskLog.detail.dataGovernanceTrend') }}</span>
                        <el-select v-model="selectedRange" size="small" :placeholder="td('da.qualityTaskLog.detail.selectTimeRange')" style="width: 120px"
                            @change="onRangeChange">
                            <el-option v-for="item in rangeOptions" :key="item.value" :label="item.label"
                                :value="item.value" />
                        </el-select>
                    </div>
                    <div class="border-item-body">
                        <p class="trend-explanation">{{ td('da.qualityTaskLog.detail.ruleCheckExplanation') }}</p>
                        <div ref="chartRef" class="echart-container"></div>
                    </div>
                </div>
            </el-col>
        </el-row>

        <!-- Rule list -->
        <el-row>
            <div class="module-8 border-item" style="width: 100%">
                <div class="border-item-head">
                    <span class="head-title">{{ td('da.qualityTaskLog.detail.ruleList') }}</span>
                </div>
                <div class="border-item-body" style="height: 320px;">
                    <el-table stripe height="300px" v-loading="loading" :data="ruleList" lazy :show-overflow-tooltip="{effect: 'light'}">
                        <el-table-column v-if="getColumnVisibility(8)" :label="td('da.qualityTaskLog.detail.evaluationName')" align="center"
                            :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                {{ getEvaluateName(scope.row) }}
                            </template>
                        </el-table-column>

                        <el-table-column v-if="getColumnVisibility(1)" :label="td('da.qualityTaskLog.detail.dbName')" align="center" prop="name"
                            :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">{{ scope.row.tableName || scope.row.datasourceName || '-' }}</template>
                        </el-table-column>
                        <el-table-column v-if="getColumnVisibility(2)" :label="td('da.qualityTaskLog.detail.fieldInfo')" align="center" prop="name"
                            :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope"> {{ scope.row.columnLabel || '-' }}</template>
                        </el-table-column>
                        <el-table-column v-if="getColumnVisibility(3)" :label="td('da.qualityTaskLog.detail.qualityDimension')" align="center" prop="dimensionType"
                            :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                <dict-tag :options="att_rule_audit_q_dimension" :value="scope.row.dimensionType" />

                            </template>
                        </el-table-column>
                        <el-table-column v-if="getColumnVisibility(5)" :label="td('da.qualityTaskLog.detail.inspectionName')" align="center" prop="ruleName"
                            :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">{{ scope.row.ruleName || '-' }}</template>
                        </el-table-column>

                        <el-table-column v-if="getColumnVisibility(7)" :label="td('da.qualityTaskLog.detail.problemDataRatio')" align="center" prop="proportion"
                            :show-overflow-tooltip="{effect: 'light'}">
                            <template #default="scope">
                                {{
                                    (scope.row.problemTotal != -1 && scope.row.problemTotal != null)
                                        ? `${scope.row.problemTotal} /${td('da.qualityTaskLog.detail.problemTotalUnit')} ${scope.row.proportion ?? '-'}%`
                                        : '-'
                                }}
                            </template>

                        </el-table-column>
                        <el-table-column :label="td('common.texts.operation')" fixed="right" width="190" align="center">
                            <template #default="scope">
                                <el-button link type="primary" icon="View"
                                    @click="openDialog(scope.row)">{{ td('da.qualityTaskLog.detail.viewProblemData') }}</el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                </div>
            </div>
        </el-row>

        <!-- Problem data popup -->
        <ProblemDialog ref="problemDialogRef" />
    </div>
</template>

<script setup>
import request from '@/utils/request';
import * as echarts from 'echarts';
import { useRoute } from 'vue-router';
import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import moment from 'moment';
const { proxy } = getCurrentInstance();
import { ArrowUp, ArrowDown } from '@element-plus/icons-vue';
import ProblemDialog from '../components/problemData.vue';
import useDefaultLang from "@/composables/useDefaultLang";
const { td, locale } = useDefaultLang();
import {
    statisticsEvaluateOne,
    statisticsEvaluateTow,
    statisticsEvaluateTable,
    getDppQualityLog
} from "@/api/da/quality/qualityTaskLog";

const { att_rule_audit_q_dimension, } = proxy.useDict(

    'att_rule_audit_q_dimension'
);
const getScoreClass = (score) => {
    if (score == null || score === '-') return 'score-null';
    if (score >= 85) return 'score-high';
    if (score >= 60) return 'score-medium';
    return 'score-low';
};
const route = useRoute();
const detailPath = route.path;
let disposed = false;
const isCurrentReport = id => !disposed && route.path === detailPath && String(route.query.id) === String(id);
const snapshotOpen=ref(false),snapshotLoading=ref(false),executionSnapshot=ref(null),snapshotPlan=ref({objects:[],rules:[]});
async function showExecutionSnapshot() {
    snapshotLoading.value=true;
    const id = route.query.id;
    try {
        const response=await request({url:`/dpp/qualityLog/${id}/snapshot`,method:'get'});
        if (!isCurrentReport(id)) return;
        executionSnapshot.value=response.data;
        snapshotPlan.value=response.data?JSON.parse(response.data.definition_json):{objects:[],rules:[]};
        snapshotOpen.value=true;
    } finally {snapshotLoading.value=false;}
}
const chartRef = ref(null);
let chartInstance = null;
let problemDialogRef = ref();
function getEvaluateName(row) {
    if (!row.rule) return '-';
    try {
        return JSON.parse(row.rule)?.evaluateName || '-';
    } catch {
        return '-';
    }
}
const openDialog = (row) => {
    problemDialogRef.value?.open(row);
};

const selectedRange = ref('7');
const rangeOptions = [
    { label: td('da.qualityTaskLog.detail.last7Days'), value: '7' },
    { label: td('da.qualityTaskLog.detail.last15Days'), value: '15' },
    { label: td('da.qualityTaskLog.detail.last30Days'), value: '30' }
];

const ruleList = ref([]);
const overallScore = ref();
const summaryList = ref([]);
const loading = ref(false);

const columns = ref([

    { key: 8, label: td('da.qualityTaskLog.detail.columnLabels.ruleName'), visible: true },
    { key: 1, label: td('da.qualityTaskLog.detail.columnLabels.dbName'), visible: true },
    { key: 2, label: td('da.qualityTaskLog.detail.columnLabels.fieldInfo'), visible: true },
    { key: 3, label: td('da.qualityTaskLog.detail.columnLabels.qualityDimension'), visible: true },
    { key: 5, label: td('da.qualityTaskLog.detail.columnLabels.ruleName'), visible: true },
    { key: 7, label: td('da.qualityTaskLog.detail.columnLabels.problemDataRatio'), visible: true },
]);
function getLabelsByColumnName(row, columnName) {
    if (!columnName) return '-';
    if (!row.rule) return columnName;
    let evaColumns = [];
    try {
        const ruleObj = typeof row.rule === 'string' ? JSON.parse(row.rule) : row.rule;
        evaColumns = Array.isArray(ruleObj.evaColumns)
            ? ruleObj.evaColumns
            : Object.values(ruleObj.evaColumns || {});
    } catch (err) {
        console.warn('Rule field parse failed', err);
        return columnName;
    }

    if (!Array.isArray(evaColumns)) return columnName;

    const names = columnName.split(',').map(n => n.trim());
    const labels = names.map(name => {
        const match = evaColumns.find(col => col.name === name);
        return match?.label || name;
    });

    return labels.join(' , ');
}

const getColumnVisibility = (key) => {
    const column = columns.value.find((col) => col.key === key);
    return column ? column.visible : true;
};

const loadChartWithData = (data = []) => {
    let { title = [], value = [] } = data;
    if (!chartInstance && chartRef.value) {
        chartInstance = echarts.init(chartRef.value);
    }

    const maxValue = Math.max(...value, 0);
    const minYMax = 30;
    const yMax = Math.max(minYMax, Math.ceil(maxValue / 5) * 5);

    const option = {
        legend: {
            data: [td('da.qualityTaskLog.detail.checkedRows')],
            left: 'center',
        },
        tooltip: { trigger: 'axis' },
        xAxis: {
            type: 'category',
            data: title,
            axisTick: { show: false },
            axisLine: {
                lineStyle: { color: 'rgba(0,0,0,0.15)' }
            },
            axisLabel: {
                margin: 14,
                fontSize: 12,
                color: 'rgba(0,0,0,0.65)',
                fontFamily: 'PingFangSC, PingFang SC',
            }
        },
        yAxis: {
            type: 'value',
            min: 0,
            max: yMax,
            minInterval: 1,
            name: td('da.qualityTaskLog.detail.checkedRows'),
            nameTextStyle: {
                color: 'rgba(0,0,0,0.85)',
                fontSize: 14,
                padding: [0, 0, 10, -18],
                fontFamily: 'PingFangSC, PingFang SC',
            },
            axisLine: {
                lineStyle: { color: 'rgba(0,0,0,0.15)' }
            },
            axisLabel: {
                fontSize: 12,
                color: 'rgba(0,0,0,0.65)',
                fontFamily: 'PingFangSC, PingFang SC',
            }
        },
        grid: { left: '3%', right: '4%', bottom: '0%', containLabel: true },
        series: [{
            name: td('da.qualityTaskLog.detail.checkedRows'),
            type: 'line',
            data: value,
            symbolSize: 8,
            itemStyle: {
                color: '#427afd',
                borderColor: '#427afd',
                borderWidth: 1
            },
            lineStyle: {
                color: '#5285fd',
                width: 2
            },
            areaStyle: {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                    { offset: 0, color: 'rgba(204, 220, 254, 1)' },
                    { offset: 1, color: 'rgba(204, 220, 254, 0)' }
                ])
            }
        }]
    };

    chartInstance.setOption(option);
};


async function loadOverallScore(id) {
    try {
        const response = await getDppQualityLog(id);
        if (isCurrentReport(id)) overallScore.value = response.data?.score ?? null;
    } catch (failure) {
        if (isCurrentReport(id)) overallScore.value = null;
    }
}

// Score and quality dimension summary
const loadScoreAndSummary = async (id) => {
    try {
        const res = await statisticsEvaluateOne(id);
        if (!isCurrentReport(id)) return;
        const result = res?.data || [];

        // Build dimension mapping for quick lookup
        const resultMap = result.reduce((map, item) => {
            map[item.dimensionType] = item;
            return map;
        }, {});

        summaryList.value = att_rule_audit_q_dimension.value.map(dim => {
            return resultMap[dim.value] || {
                dimensionType: dim.value,
                succesTotal: 0,
                proportion: 0,
                trendType: '-1',
            };
        });
    } catch (err) {
        console.warn('Score/dimension summary failed', err);
    }
};
// Rule list
const loadRuleTable = async (id) => {
    try {
        const res = await statisticsEvaluateTable(id);
        if (!isCurrentReport(id)) return;
        if (res.data && Array.isArray(res.data)) {
            ruleList.value = res.data.map(item => {
                return {
                    ...item,
                    columnLabel: getLabelsByColumnName(item, item.columnName)
                };
            });
        } else {
            ruleList.value = [];
        }
    } catch (err) {
        console.warn('Rule list failed', err);
    } finally {
    }
};

// Line chart data
const loadTrendChart = async (id) => {
    try {
        const requestedRange = selectedRange.value;
        const today = moment().format('YYYY-MM-DD');
        const oldDate = moment().subtract(Number(selectedRange.value), 'days').format('YYYY-MM-DD');
        const type = selectedRange.value === '7' ? 0 : selectedRange.value === '15' ? 1 : 2;
        const res = await statisticsEvaluateTow({ id, deDate: today, oldDate, type });
        if (!isCurrentReport(id) || selectedRange.value !== requestedRange) return;
        loadChartWithData(res?.data || []);
    } catch (err) {
        console.warn('Line chart data failed', err);
    }
};

// Watch language changes, re-render charts
watch(locale, () => {
    if (chartInstance) {
        chartInstance.dispose();
        chartInstance = null;
    }
    const id = route.query.id || 'default';
    loadTrendChart(id);
});

//
const fetchData = async (id) => {
    loading.value = true;
    overallScore.value = null;
    summaryList.value = [];
    ruleList.value = [];
    executionSnapshot.value = null;
    snapshotOpen.value = false;
    chartInstance?.clear();
    await Promise.all([
        loadOverallScore(id),
        loadScoreAndSummary(id),
        loadRuleTable(id),
        loadTrendChart(id)
    ]);
    if (isCurrentReport(id)) loading.value = false;
};

const onRangeChange = () => {
    const id = route.query.id || 'default';
    loadTrendChart(id)
};

watch(() => [route.path, route.query.id], ([path, id]) => { if (path === detailPath && id) fetchData(id); });

const handleResize = () => {
    chartInstance?.resize();
};

onMounted(() => {
    const id = route.query.id || 'default';
    fetchData(id);
    window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
    disposed = true;
    window.removeEventListener('resize', handleResize);
    chartInstance?.dispose();
    chartInstance = null;
});
</script>

<style lang="scss" scoped>
.top-section {
    margin-bottom: 20px;
}

.echart-container {
    height: 100%;
    width: 100%;
}

.border-item {
    width: 100%;
    background: #fff;
    border-radius: 2px;

    .border-item-head {
        height: 50px;
        padding: 0 20px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        border-bottom: 1px solid #e8e8e8;

        .head-title {
            font-size: 16px;
            font-weight: 500;
            display: flex;
            align-items: center;

            &::before {
                content: "";
                display: inline-block;
                width: 3px;
                height: 20px;
                background: var(--el-color-primary);
                margin-right: 10px;
                border-radius: 2px;
            }
        }
    }

    .border-item-body {
        height: 360px;
        padding: 10px 20px;
        background-color: #fff;
    }
}

.trend-explanation { margin: 0 0 8px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.5; }

.overall-score {
    display: flex;
    justify-content: center;
    align-items: center;
    font-size: 16px;
    margin-bottom: 10px;

    .score {
        font-size: 24px;
        font-weight: 700;
        margin-left: 8px;
    }
}

.score-high {
    color: #16a34a;
    ;
}

.score-medium {
    color: #faad14;
}

.score-low {
    color: #f5222d;
}

.score-null {
    color: #999;
}
</style>
