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
  <!-- vector data -->
  <div v-if="visible">
    <template
        v-if="
        form.daAssetFiles.url != undefined &&
        form.daAssetFiles.url.toLowerCase().indexOf('.xls') == -1 &&
        form.daAssetFiles.url.toLowerCase().indexOf('.xlsx') == -1
      "
    >
      <el-form-item
          :label="td('dpp.asset.add.excel.uploadAttachment')"
          prop="daAssetFiles.url"
          :rules="[{ required: true, message: td('dpp.asset.add.excel.uploadAttachmentRequired'), trigger: 'change' }]"
      >
        <FileUploadbtn
            :limit="1"
            v-model="form.daAssetFiles.url"
            :dragFlag="false"
            :file-type="['csv', 'xls', 'xlsx']"
            :fileSize="50"
            @handleRemove="handleRemove"
            @customEvent="dataFileName"
        />
      </el-form-item>
      <div style="display: flex; align-items: center; padding-left: 20px">
        <el-button
            v-if="form.daAssetFiles.url.toLowerCase().indexOf('.csv') != -1"
            type="primary"
            plain
            @click="parseExcel"
            :disabled="isButtonDisabled"
        >
          {{ td('dpp.asset.add.excel.parseCsv') }}
        </el-button>
      </div>
    </template>
    <template
        v-if="
        form.daAssetFiles.url == undefined ||
        form.daAssetFiles.url.toLowerCase().indexOf('.xls') != -1 ||
        form.daAssetFiles.url.toLowerCase().indexOf('.xlsx') != -1
      "
    >
      <el-form-item
          :label="td('dpp.asset.add.excel.uploadAttachment')"
          prop="daAssetFiles.url"
          :rules="[{ required: true, message: td('dpp.asset.add.excel.uploadAttachmentRequired'), trigger: 'change' }]"
      >
        <FileUploadbtn
            :limit="1"
            v-model="form.daAssetFiles.url"
            :dragFlag="false"
            :file-type="['csv', 'xls', 'xlsx']"
            :fileSize="50"
            @handleRemove="handleRemove"
            @customEvent="dataFileName"
        />
      </el-form-item>
      <el-form-item
          :label="td('dpp.asset.add.excel.startRow')"
          prop="daAssetFiles.startData"
          :rules="[{ required: true, message: td('dpp.asset.add.excel.startRowRequired'), trigger: 'change' }]"
      >
        <el-input-number
            :step="1"
            step-strictly
            :placeholder="td('dpp.asset.add.excel.startRowPlaceholder')"
            v-model="form.daAssetFiles.startData"
            style="width: 100%"
            controls-position="right"
            :min="1"
            value-on-clear="min"
        />
      </el-form-item>
    </template>
    <template
        v-if="
        form.daAssetFiles.url == undefined ||
        form.daAssetFiles.url.toLowerCase().indexOf('.xls') != -1 ||
        form.daAssetFiles.url.toLowerCase().indexOf('.xlsx') != -1
      "
    >
      <el-form-item
          :label="td('dpp.asset.add.excel.startColumn')"
          prop="daAssetFiles.startColumn"
          :rules="[{ required: true, message: td('dpp.asset.add.excel.startColumnRequired'), trigger: 'change' }]"
      >
        <el-input-number
            :step="1"
            step-strictly
            :placeholder="td('dpp.asset.add.excel.startColumnPlaceholder')"
            v-model="form.daAssetFiles.startColumn"
            style="width: 100%"
            controls-position="right"
            :min="1"
            value-on-clear="min"
        />
      </el-form-item>
      <div style="display: flex; align-items: center; padding-left: 20px">
        <el-button
            type="primary"
            plain
            @click="parseExcel"
            :disabled="isButtonDisabled"
        >
          {{ td('dpp.asset.add.excel.parseExcel') }}
        </el-button>
      </div>
    </template>
    <el-divider
        class="row-full"
        content-position="center"
        v-if="
        form.daAssetFiles.url &&
        (form.daAssetFiles.url.toLowerCase().indexOf('.csv') != -1 ||
          form.daAssetFiles.url.toLowerCase().indexOf('.xls') != -1 ||
          form.daAssetFiles.url.toLowerCase().indexOf('.xlsx') != -1)
      "
    >
      <span class="blue-text">{{ td('dpp.asset.add.excel.attributeFields') }}</span>
    </el-divider>
    <el-table
        class="row-full"
        stripe
        height="310px"
        v-loading="loadingList"
        :data="ColumnByAssettab"
        v-if="
        form.daAssetFiles.url &&
        (form.daAssetFiles.url.toLowerCase().indexOf('.csv') != -1 ||
          form.daAssetFiles.url.toLowerCase().indexOf('.xls') != -1 ||
          form.daAssetFiles.url.toLowerCase().indexOf('.xlsx') != -1)
      "
    >
      <el-table-column :label="td('dpp.asset.add.excel.index')" type="index" width="80" align="left">
        <template #default="scope">
          <span>{{ scope.$index + 1 }}</span>
        </template>
      </el-table-column>
      <el-table-column
          :label="td('dpp.asset.add.excel.fieldName')"
          align="left"
          prop="columnName"
          :show-overflow-tooltip="{ effect: 'light' }"
      >
        <template #default="scope">
          {{ scope.row.columnName || "-" }}
        </template>
      </el-table-column>
      <el-table-column :label="td('dpp.asset.add.excel.fieldType')" align="left" prop="columnType">
        <template #default="scope">
          {{ scope.row.columnType || "-" }}
        </template>
      </el-table-column>
      <el-table-column :label="td('dpp.asset.add.excel.dateFormat')" align="left" prop="format">
        <template #default="scope">
          {{ scope.row.format || "-" }}
        </template>
      </el-table-column>

    </el-table>
  </div>
</template>
<script setup>
import useDefaultLang from "@/composables/useDefaultLang"
import { getToken } from '@/utils/auth.js';
import { typeList } from '@/utils/graph.js';
import { getNodeUniqueKey } from '@/api/dpp/task/index.js';
import { inspectAssetFile } from '@/api/da/asset/asset';
const { proxy } = getCurrentInstance();
import useUserStore from '@/store/system/user.js';

const { td } = useDefaultLang();
const userStore = useUserStore();
const props = defineProps({
  projectId: { type: [String, Number], default: null },
  assetId: { type: [String, Number], default: null },
  title: { type: String, default: '' },
  currentNode: { type: Object, default: () => ({}) },
  info: { type: Boolean, default: false },
  objData: { type: Object, default: () => ({}) }
});
const emit = defineEmits(['update', 'confirm']);
const visible = ref(false);
// variable definition
let loading = ref(false);
let loadingList = ref(false);
let TablesByDataSource = ref([]);
let ColumnByAssettab = ref();
// Modify
const open = ref(false);
let row = ref({});
const openDialog = (obj) => {
  row.value = obj;
  open.value = true;
};
// Attribute fields modified and added
const handletaskConfig = (form) => {
  ColumnByAssettab.value = ColumnByAssettab.value.map((column) => {
    if (column.id == form.id) {
      return { ...column, ...form };
    }
    return column;
  });
};

let dpModelRefs = ref();
let form = ref();
const tableFields = ref([]); // Source form
// Computed property: determine whether the button is disabled
const isButtonDisabled = computed(() => {
  const file=form.value?.daAssetFiles;
  return !file?.url || (/\.xlsx?$/i.test(file.url) && (!file.startData || !file.startColumn));
});
const parseExcel = async () => {
  const file=form.value.daAssetFiles;
  if(!file.url)return ElMessage.warning(td('dpp.asset.add.excel.addAttachment'));
  loadingList.value=true;
  try {
    const response=await inspectAssetFile({assetId:props.assetId,projectId:props.projectId,url:file.url,
      startData:Number(file.startData)||null,startColumn:Number(file.startColumn)||null});
    ColumnByAssettab.value=response.data.columnList.map((name,index)=>({id:index,columnName:name,columnType:'STRING'}));
    file.tableFields=ColumnByAssettab.value;
    ElMessage.success(td(/\.csv$/i.test(file.url)?'dpp.asset.add.excel.csvParseSuccess':'dpp.asset.add.excel.excelParseSuccess'));
  } catch (error) {
    ColumnByAssettab.value=[];
  } finally { loadingList.value=false; }
};

const off = () => {
  proxy.resetForm('dpModelRefs');
  // Clear table field data
  ColumnByAssettab.value = [];
  TablesByDataSource.value = [];
  tableFields.value = [];
};
// save data
const saveData = async () => {
  try {
    // Asynchronous validation form
    const valid = await dpModelRefs.value.validate();
    if (!valid) return;
    if (
        form.value?.daAssetFiles.type == '1' &&
        (!ColumnByAssettab.value || ColumnByAssettab.value.length == 0)
    ) {
      return proxy.$message.warning(td('dpp.asset.add.excel.validateSelectFields'));
    }
    // If there is no code, call the interface to get the unique code
    if (!form.value.code) {
      loading.value = true;
      const response = await getNodeUniqueKey({
        projectCode: userStore.projectCode || '133545087166112',
        projectId: userStore.projectId
      });
      loading.value = false; // end loading state
      form.value.code = response.data; // Set unique code
    }
    const daAssetFiles = form.value?.daAssetFiles;
    daAssetFiles.tableFields = ColumnByAssettab.value;
    daAssetFiles.columnsList = ColumnByAssettab.value.map(({ columnName, columnType }) => ({
      colName: columnName,
      dataType: columnType
    }));
    daAssetFiles.columns = daAssetFiles.tableFields.map((item) => {
      return {
        index: item.id,
        columnName: item.columnName,
        type: item.columnType,
        format: item.format
      };
    });
    emit('confirm', form.value);
    emit('update', false);
  } finally {
    loadingList.value = false;
  }
};

function dataFileName(file) {
  form.value.daAssetFiles.name = file.originalFilename;
  form.value.daAssetFiles.type = file.ext;
  console.log(form.value, '451561649861');
}
const closeDialog = () => {
  off();
  // Close dialog
  emit('update', false);
};

// Listen for property changes
const show = (data) => {
  visible.value = false;
  console.log(data, "File type:");
  if (data.createType == '2' && data.type == '6') {
    visible.value = true;
    form.value = data;
    form.value.daAssetFiles.startColumn ||= 1;
    form.value.daAssetFiles.startData ||= form.value.daAssetFiles.startColumn + 1;
    ColumnByAssettab.value = data.daAssetFiles.tableFields || [];
  }
  if (data.id != undefined && data.type == '6') {
    visible.value = true;
    if (data.daAssetFiles == undefined) {
      data.daAssetFiles = {
        url: null,
        startData: '',
        tableFields: [],
        startColumn: ''
      };
    }
    form.value = data;
    form.value.daAssetFiles.startColumn ||= 1;
    form.value.daAssetFiles.startData ||= form.value.daAssetFiles.startColumn + 1;
    ColumnByAssettab.value = data.daAssetFiles.tableFields || [];
  }
};
// Listen for property changes
// watchEffect(() => {
//   console.log(userStore)
//   if (props.visible) {
//     // data source
//     console.log(props.objData,'==========')
//     form.value = deepCopy(props.data);
//     ColumnByAssettab.value = props.data.daAssetFiles.tableFields;
//   } else {
//     off();
//   }
// });
// File deletion
function handleRemove() {
  ColumnByAssettab.value = [];
  form.value.daAssetFiles.url = undefined;
}
defineExpose({ show, form });
</script>
<style scoped lang="scss">
.blue-text {
  color: #2666fb;
}
</style>
