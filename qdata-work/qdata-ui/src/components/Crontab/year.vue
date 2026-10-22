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
    <el-form>
        <el-form-item>
            <el-radio v-model="radioValue" :value="1" @change="onRadioChange">
                {{ td('common.crontab.year.wildcard') }}
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model="radioValue" :value="2" @change="onRadioChange"> {{ td('common.crontab.year.every') }} </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model="radioValue" :value="3" @change="onRadioChange">
                {{ td('common.crontab.cycleFrom') }}
                <el-input-number v-model="cycle01" :min="1970" :max="maxFullYear - 1" @change="onRadioChange" /> -
                <el-input-number
                    v-model="cycle02"
                    :min="cycle01 ? cycle01 + 1 : 1971"
                    :max="maxFullYear"
                    @change="onRadioChange"
                />
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model="radioValue" :value="4" @change="onRadioChange">
                {{ td('common.crontab.year.averagePrefix') }}
                <el-input-number v-model="average01" :min="1970" :max="maxFullYear" @change="onRadioChange" /> {{ td('common.crontab.year.averageMiddle') }}
                <el-input-number v-model="average02" :min="1" :max="MAX_YEAR_AHEAD" @change="onRadioChange" />
                {{ td('common.crontab.year.averageSuffix') }}
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model="radioValue" :value="5" @change="onRadioChange">
                {{ td('common.crontab.specify') }}
                <el-select
                    clearable
                    v-model="checkboxList"
                    @change="onRadioChange"
                    :placeholder="td('common.crontab.multiSelect')"
                    multiple
                    :multiple-limit="8"
                >
                    <el-option
                        v-for="item in yearOptions"
                        :key="item"
                        :value="item"
                        :label="item"
                        :disabled="item > maxFullYear"
                    />
                </el-select>
            </el-radio>
        </el-form-item>
    </el-form>
</template>

<script setup>
    import { MAX_YEAR_AHEAD } from './yearPolicy.mjs';
    import useDefaultLang from "@/composables/useDefaultLang.js";
    const { td } = useDefaultLang();
    const emit = defineEmits(['update']);
    const props = defineProps({
        cron: {
            type: Object,
            default: {
                second: '0',
                min: '*',
                hour: '*',
                day: '*',
                month: '*',
                week: '?',
                year: ''
            }
        },
        check: {
            type: Function,
            default: () => {}
        }
    });
    const fullYear = new Date().getFullYear();
    const maxFullYear = fullYear + MAX_YEAR_AHEAD;
    const radioValue = ref(1);
    const cycle01 = ref(fullYear);
    const cycle02 = ref(fullYear + 1);
    const average01 = ref(fullYear);
    const average02 = ref(1);
    const checkboxList = ref([fullYear]);
    const checkCopy = ref([fullYear]);
    // Keep imported years visible even outside the default nine-year window.
    const yearOptions = ref(Array.from({ length: MAX_YEAR_AHEAD + 1 }, (_, index) => fullYear + index));
    const cycleTotal = computed(() => {
        const start = props.check(cycle01.value, 1970, maxFullYear - 1);
        const end = props.check(cycle02.value, start + 1, maxFullYear);
        return start + '-' + end;
    });
    const averageTotal = computed(() => {
        const start = props.check(average01.value, 1970, maxFullYear);
        const step = props.check(average02.value, 1, MAX_YEAR_AHEAD);
        return start + '/' + step;
    });
    const checkboxString = computed(() => checkboxList.value.join(','));
    // Hydration must never emit: the parent can still be resolving its expression.
    // Only explicit control change events below write back to the parent.
    watch(() => props.cron.year, changeRadioValue, { immediate: true });
    function changeRadioValue(value) {
        if (!value) {
            radioValue.value = 1;
        } else if (value === '*') {
            radioValue.value = 2;
        } else if (value.indexOf('-') > -1) {
            const indexArr = value.split('-');
            cycle01.value = Number(indexArr[0]);
            cycle02.value = Number(indexArr[1]);
            radioValue.value = 3;
        } else if (value.indexOf('/') > -1) {
            const indexArr = value.split('/');
            average01.value = Number(indexArr[0]);
            average02.value = Number(indexArr[1]);
            radioValue.value = 4;
        } else {
            checkboxList.value = [...new Set(value.split(',').map((item) => Number(item)))];
            yearOptions.value = [...new Set([...yearOptions.value, ...checkboxList.value])].sort((a, b) => a - b);
            checkCopy.value = [...checkboxList.value];
            radioValue.value = 5;
        }
    }
    function onRadioChange() {
        switch (radioValue.value) {
            case 1:
                emit('update', 'year', '', 'year');
                break;
            case 2:
                emit('update', 'year', '*', 'year');
                break;
            case 3:
                emit('update', 'year', cycleTotal.value, 'year');
                break;
            case 4:
                emit('update', 'year', averageTotal.value, 'year');
                break;
            case 5:
                if (checkboxList.value.length === 0) {
                    checkboxList.value = [...checkCopy.value];
                } else {
                    checkCopy.value = [...checkboxList.value];
                }
                emit('update', 'year', checkboxString.value, 'year');
                break;
        }
    }
</script>

<style lang="scss" scoped>
    .el-input-number--small,
    .el-select,
    .el-select--small {
        margin: 0 0.2rem;
    }
    .el-select,
    .el-select--small {
        width: 18.8rem;
    }
</style>
