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
  <!-- // database table -->

  <el-form-item :label="td('dpp.asset.add.table.tableName')" prop="tableName">
    <el-input v-model="localForm.tableName" :placeholder="td('dpp.asset.add.table.tableName')" disabled />
  </el-form-item>
  <el-form-item :label="td('dpp.asset.add.table.tableComment')" prop="tableComment">
    <el-input v-model="localForm.tableComment" :placeholder="td('dpp.asset.add.table.tableComment')" disabled />
  </el-form-item>
  <el-form-item
      :label="td('dpp.asset.add.table.assetName')"
      prop="name"
      :rules="[{ required: true, message: td('dpp.asset.add.table.assetNameRequired'), trigger: 'blur' }]"
  >
    <el-input v-model="localForm.name" :placeholder="td('dpp.asset.add.table.assetNamePlaceholder')" />
  </el-form-item>
  <el-form-item
      :label="td('dpp.asset.add.table.tableType')"
      prop="tableType"
      :rules="[{ required: true, message: td('dpp.asset.add.table.tableTypeRequired'), trigger: 'change' }]"
  >
    <el-select v-model="localForm.tableType" style="width: 100%">
      <el-option
          v-for="item in table_type"
          :key="item.value"
          :label="item.label"
          :value="item.value"
      />
    </el-select>
  </el-form-item>
  <el-form-item
      :label="td('dpp.asset.add.table.hierarchy')"
      prop="dataLayerId"
      :rules="[
      {
        required: true,
        message: td('dpp.asset.add.table.hierarchyRequired'),
        trigger: 'change',
      },
    ]"
  >
    <el-tree-select
        v-model="localForm.dataLayerId"
        :data="dataLayerList"
        :loading="layerLoading"
        :props="{
        value: 'id',
        label: 'displayName',
        children: 'children',
      }"
        node-key="id"
        value-key="id"
        :placeholder="td('dpp.asset.add.table.hierarchyPlaceholder')"
        check-strictly
        filterable
        default-expand-all
        clearable
        style="width: 100%"
    />
  </el-form-item>

  <template v-if="localForm.tableType != '4'">
    <el-form-item
        :label="td('dpp.asset.add.table.businessCategory')"
        prop="businessDomainId"
        :rules="[
        {
          required: true,
          message: td('dpp.asset.add.table.businessCategoryRequired'),
          trigger: 'change',
        },
      ]"
    >
      <el-tree-select
          v-model="localForm.businessDomainId"
          :data="businessCategoryList"
          :loading="businessLoading"
          :props="{
          value: 'id',
          label: 'displayName',
          children: 'children',
        }"
          node-key="id"
          value-key="id"
          :placeholder="td('dpp.asset.add.table.businessCategoryPlaceholder')"
          check-strictly
          filterable
          clearable
          style="width: 100%"
      />
    </el-form-item>
    <el-form-item
        :label="td('dpp.asset.add.table.dataDomain')"
        prop="dataDomainId"
        :rules="[
        {
          required: true,
          message: td('dpp.asset.add.table.dataDomainRequired'),
          trigger: 'change',
        },
      ]"
    >
      <el-tree-select
          v-model="localForm.dataDomainId"
          :data="dataDomainList"
          :loading="domainLoading"
          :props="{
          value: 'id',
          label: 'displayName',
          children: 'children',
        }"
          node-key="id"
          value-key="id"
          :placeholder="td('dpp.asset.add.table.dataDomainPlaceholder')"
          check-strictly
          filterable
          clearable
          style="width: 100%"
      />
    </el-form-item>
  </template>

  <template v-else>
    <el-form-item
        :label="td('dpp.asset.add.table.themeDomain')"
        prop="themeDomainId"
        :rules="[
        {
          required: true,
          message: td('dpp.asset.add.table.themeDomainRequired'),
          trigger: 'change',
        },
      ]"
    >
      <el-tree-select
          v-model="localForm.themeDomainId"
          :data="themeDomainList"
          :loading="themeLoading"
          :props="{
          value: 'id',
          label: 'displayName',
          children: 'children',
        }"
          node-key="id"
          value-key="id"
          :placeholder="td('dpp.asset.add.table.themeDomainPlaceholder')"
          check-strictly
          filterable
          clearable
          style="width: 100%"
      />
    </el-form-item>
  </template>

  <el-form-item
      :label="td('dpp.asset.add.table.tableNamingRule')"
      prop="tableCase"
      :rules="[
      {
        required: true,
        message: td('dpp.asset.add.table.tableCaseRequired'),
        trigger: 'change',
      },
    ]"
  >
    <el-select
        v-model="localForm.tableCase"
        :placeholder="td('dpp.asset.add.table.tableNamingRulePlaceholder')"
        style="width: 100%"
    >
      <el-option
          v-for="dict in table_name_case"
          :key="dict.value"
          :label="dict.label"
          :value="Number(dict.value)"
      />
    </el-select>
  </el-form-item>
  <el-form-item
      :label="td('dpp.asset.add.table.tableNamingRule')"
      prop="namingSpec"
      :rules="[
      {
        required: true,
        message: td('dpp.asset.add.table.namingSpecRequired'),
        trigger: 'change',
      },
    ]"
  >
    <el-input
        v-model="localForm.namingSpec"
        :placeholder="td('dpp.asset.add.table.namingSpecPlaceholder')"
        disabled
    />
  </el-form-item>
  <el-form-item label="已采集数据表" prop="tableName" :rules="[{ required: true, message: '请选择已采集的数据表', trigger: 'change' }]">
    <el-input v-if="!props.isRegister && localForm.id && localForm.createType == '2'" :model-value="[localForm.databaseName, localForm.tableName].filter(Boolean).join('.')" disabled />
    <el-select v-else v-model="localForm.tableId" filterable remote :remote-method="remoteSearchTables" @visible-change="handleTableSelectVisible" @change="handleTableChange" :loading="loadingList" :disabled="!localForm.id && !localForm.projectId" placeholder="选择项目后搜索库名或表名">
      <el-option v-for="item in tablesByDataSource" :key="item.id" :value="item.id" :label="`${item.datasourceName} / ${item.databaseName}.${item.tableName}`" />
    </el-select>
  </el-form-item>
  <el-form-item label="数据连接"><el-input :model-value="localForm.datasourceName || localForm.datasourceId" disabled /></el-form-item>
  <el-form-item :label="td('dpp.asset.add.table.connectionType')"><el-input v-model="localForm.datasourceType" disabled /></el-form-item>

</template>

<script setup>
import useDefaultLang from "@/composables/useDefaultLang"
import { ref, watch, getCurrentInstance, onMounted } from "vue";
import { ElMessage } from "element-plus";
import useUserStore from "@/store/system/user.js";

const { td } = useDefaultLang();
import {
  getTablesByDataSourceId,
  getColumnByAssetId,
  getDaDatasourceList,
} from "@/api/dpp/task/index.js";
import { registrationTables, dppNoPageList } from "@/api/da/asset/asset.js";
import {
  getDaDatasource,
  listDaDatasourceNoKafkaByProjectCode,
} from "@/api/da/dataSource/dataSource.js";
import DatasourceList from "@/components/Datasource/List.vue";
import { treeDataLayer } from "@/api/dm/dataLayer/dataLayer.js";
import { listBusinessCategory } from "@/api/dm/businessCategory/businessCategory";
import { listDataDomain } from "@/api/dm/dataDomain/dataDomain";
import { listThemeDomain } from "@/api/dm/themeDomain/themeDomain";
import { formatModelName, findInTree } from "@/utils/dm/utils";

const props = defineProps({
  form: Object,
  isRegister: Boolean,
  type: String,
});
const emit = defineEmits(["update:form"]);

const { proxy } = getCurrentInstance();
const { dpp_connection, table_type, table_name_case } = proxy.useDict(
    "dpp_connection",
    "table_type",
    "table_name_case"
);

const userStore = useUserStore();
const createTypeList = ref([]); // Data source list
let loading = ref(false);
const loadingList = ref(false);
const dppAssetList = ref([]);
const columnsByAssetTable = ref([]);
const tablesByDataSource = ref([]);

let layerLoading = ref(false);
let businessLoading = ref(false);
let domainLoading = ref(false);
let themeLoading = ref(false);
let dataLayerList = ref([]);
let businessCategoryList = ref([]);
let dataDomainList = ref([]);
let themeDomainList = ref([]);
const isInitializing = ref(false);
const isResetting = ref(false);

const localForm = ref({
  ...props.form,
  datasourceId: props.form.datasourceId == -1 ? null : props.form.datasourceId,
  tableName: props.form.tableName == -1 ? null : props.form.tableName,
});

const formatTreeData = (list) => {
  return list.map((item) => {
    const newItem = { ...item };
    newItem.id = Number(item.id); // Cast to number to match echo
    const abbreviation = item.engName || item.shortName;
    newItem.displayName = abbreviation
        ? `${item.name} / ${abbreviation}`
        : item.name;
    if (item.children && item.children.length) {
      newItem.children = formatTreeData(item.children);
    }
    return newItem;
  });
};

const fetchAllOptions = (currentType) => {
  const targetType = currentType || localForm.value.tableType;
  // Data warehouse stratification
  layerLoading.value = true;
  const p1 = treeDataLayer()
      .then((res) => {
        const tree = res.data || res.rows || [];
        const processTree = (list, root = true) => {
          return list.map((item) => {
            const newItem = { ...item };
            newItem.id = root ? `layer-category-${item.id}` : Number(item.id); // Cast to number to match echo
            const abbreviation = item.engName || item.shortName;
            newItem.displayName = abbreviation
                ? `${item.name} / ${abbreviation}`
                : item.name;
            newItem.disabled = root;
            if (item.children && item.children.length) {
              newItem.children = processTree(item.children, false);
            }
            return newItem;
          });
        };
        dataLayerList.value = processTree(tree);
      })
      .finally(() => {
        layerLoading.value = false;
      });

  let p2;
  if (targetType == "4") {
    // subject area
    themeLoading.value = true;
    p2 = listThemeDomain({ pageNum: 1, pageSize: 1000, validFlag: true })
        .then((res) => {
          const tree = proxy.handleTree(
              res.data?.rows || res.data || res.rows || [],
              "id",
              "parentId"
          );
          themeDomainList.value = formatTreeData(tree);
        })
        .finally(() => {
          themeLoading.value = false;
        });
  } else {
    // Business classification
    businessLoading.value = true;
    p2 = listBusinessCategory({
      pageNum: 1,
      pageSize: 1000,
      orderByColumn: "create_time",
      isAsc: "descending",
      validFlag: true,
    })
        .then((res) => {
          const tree = proxy.handleTree(
              res.data?.rows || res.data || res.rows || [],
              "id",
              "parentId"
          );
          businessCategoryList.value = formatTreeData(tree);
        })
        .finally(() => {
          businessLoading.value = false;
        });
  }
  return Promise.all([p1, p2]);
};

const fetchDataDomain = (businessDomainId) => {
  if (!businessDomainId) {
    dataDomainList.value = [];
    return Promise.resolve();
  }
  const query = {
    pageNum: 1,
    pageSize: 1000,
    orderByColumn: "create_time",
    isAsc: "descending",
    businessCategoryId: businessDomainId,
    validFlag: true,
  };
  domainLoading.value = true;
  return listDataDomain(query)
      .then((res) => {
        const tree = proxy.handleTree(
            res.data?.rows || res.data || res.rows || [],
            "id",
            "parentId"
        );
        dataDomainList.value = formatTreeData(tree);
      })
      .finally(() => {
        domainLoading.value = false;
      });
};

const generateModelName = (initialData = null) => {
  if (isResetting.value) return;

  const options = {
    dataLayerList: dataLayerList.value,
    dataLayerId: localForm.value.dataLayerId,
    tableType: localForm.value.tableType,
    themeDomainList: themeDomainList.value,
    themeDomainId: localForm.value.themeDomainId,
    businessCategoryList: businessCategoryList.value,
    businessDomainId: localForm.value.businessDomainId,
    dataDomainList: dataDomainList.value,
    dataDomainId: localForm.value.dataDomainId,
    modelName: localForm.value.tableName,
    tableCase: localForm.value.tableCase,
  };

  if (initialData) {
    // Merge the properties in the initial data into options in case the list has not been loaded yet
    Object.assign(options, initialData);
  } else {
    if (isInitializing.value) return;
  }

  localForm.value.namingSpec = formatModelName(options);

  // Sync business/topic codes
  if (localForm.value.tableType === "4") {
    const theme = findInTree(
        themeDomainList.value,
        localForm.value.themeDomainId
    );
    if (theme) localForm.value.themeDomainCode = theme.code;
  } else {
    const biz = findInTree(
        businessCategoryList.value,
        localForm.value.businessDomainId
    );
    if (biz) localForm.value.businessCategoryCode = biz.code;
  }
  emit("update:form", localForm.value);
};

// General data acquisition function
const fetchData = async (requestFn, params, loadingState) => {
  try {
    loadingState.value = true;
    const response = await requestFn(params);
    return response?.data || [];
  } finally {
    loadingState.value = false;
  }
};

const remoteSearchTables = async (query) => {
  if (!localForm.value.id && !localForm.value.projectId) { tablesByDataSource.value = []; return; }
  tablesByDataSource.value = await fetchData(registrationTables, {
    projectId: localForm.value.id ? undefined : localForm.value.projectId,
    assetId: localForm.value.id || undefined,
    search: query,
  }, loadingList);
};
const handleTableSelectVisible = (visible) => { if (visible) remoteSearchTables(""); };
const handleTableChange = (id) => {
  const selected = tablesByDataSource.value.find(item => item.id === id);
  if (!selected) return;
  Object.assign(localForm.value, {
    tableId: selected.id, tableName: selected.tableName, databaseName: selected.databaseName,
    datasourceId: String(selected.datasourceId), datasourceName: selected.datasourceName,
    datasourceType: selected.datasourceType, tableComment: selected.tableComment,
  });
  if (!localForm.value.name) localForm.value.name = selected.tableComment || selected.tableName;
  emit("update:form", localForm.value);
};
watch(() => props.form.projectId, () => { tablesByDataSource.value = []; });

watch(
    () => localForm.value.businessDomainId,
    (newVal) => {
      if (newVal) {
        fetchDataDomain(newVal);
      } else {
        dataDomainList.value = [];
      }
      if (!isInitializing.value) {
        localForm.value.dataDomainId = null;
      }
      emit("update:form", localForm.value);
    }
);

watch(
    () => localForm.value.tableType,
    (newVal) => {
      fetchAllOptions(newVal);
      if (!isInitializing.value) {
        localForm.value.dataLayerId = null;
        localForm.value.businessDomainId = null;
        localForm.value.dataDomainId = null;
        localForm.value.themeDomainId = null;
      }
      emit("update:form", localForm.value);
    }
);

watch(
    [
      () => localForm.value.dataLayerId,
      () => localForm.value.businessDomainId,
      () => localForm.value.dataDomainId,
      () => localForm.value.themeDomainId,
      () => localForm.value.tableName,
      () => localForm.value.tableCase,
      () => localForm.value.tableType,
      () => dataLayerList.value,
      () => businessCategoryList.value,
      () => dataDomainList.value,
      () => themeDomainList.value,
    ],
    () => {
      generateModelName();
    }
);

watchEffect(() => {
  localForm.value = {
    ...props.form,
    datasourceId:
        props.form.datasourceId == -1 ? null : props.form.datasourceId,
    tableName: props.form.tableName == -1 ? null : props.form.tableName,
  };
});

onMounted(async () => {
  isInitializing.value = true;
  await fetchAllOptions();
  if (localForm.value.businessDomainId) {
    await fetchDataDomain(localForm.value.businessDomainId);
  }
  isInitializing.value = false;
  generateModelName(localForm.value.id ? localForm.value : null);
});
</script>
