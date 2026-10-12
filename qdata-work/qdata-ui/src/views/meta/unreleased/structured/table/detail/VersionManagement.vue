<template>
  <div class="version-management">
    <qt-wrap
      :columns="tableStroe.columns"
      :tableRef="tableRef"
      :config="{ fullContent: false, actions: { table: { search: false } } }"
    >
      <qt-table :key="detail.id" v-bind="tableStroe" ref="tableRef">
        <template #handle="{ row }">
          <el-button
            link
            type="primary"
            icon="view"
            @click="handleDetailClick(row)"
          >
            {{ td("common.button.details") }}
          </el-button>
        </template>
      </qt-table>
    </qt-wrap>

    <el-dialog
      v-model="dialog.open"
      :title="td('meta.unreleased.structured.table.detail.versionDetail')"
      width="800"
      draggable
    >
      <el-form label-width="auto" :label-position="labelPosition">
        <el-form-item
          :label="td('meta.released.structured.table.tableName') + '：'"
         :label-position="labelPosition">
          <el-input
            v-model="dialog.row.tableName"
            placeholder="—"
            disabled
          />
        </el-form-item>
        <el-form-item
          :label="td('meta.released.structured.table.tableComment') + '：'"
         :label-position="labelPosition">
          <el-input
            v-model="dialog.row.tableComment"
            placeholder="—"
            disabled
          />
        </el-form-item>
        <el-form-item
          :label="td('meta.unreleased.structured.table.detail.version') + '：'"
         :label-position="labelPosition">
          <el-input
            v-model="dialog.row.version"
            placeholder="—"
            disabled
          />
        </el-form-item>
        <el-form-item
          :label="
            td('meta.unreleased.structured.table.detail.updateType') + '：'
          "
         :label-position="labelPosition">
          <el-input
            v-model="dialog.row.updateType"
            placeholder="—"
            disabled
          />
        </el-form-item>
        <el-form-item
          :label="
            td('meta.unreleased.structured.table.detail.updateMsg') + '：'
          "
         :label-position="labelPosition">
          <el-input
            v-model="dialog.row.updateMsg"
            placeholder="—"
            disabled
            type="textarea"
            :min-height="192"
            show-word-limit
            :maxlength="500"
          />
        </el-form-item>

        <el-form-item :label="td('common.texts.createdBy') + '：'" :label-position="labelPosition">
          <el-input
              v-model="dialog.row.createBy"
              placeholder="—"
              disabled
          />
        </el-form-item>
        <el-form-item :label="td('common.texts.createdTime') + '：'" :label-position="labelPosition">
          <el-input
              v-model="dialog.row.createTime"
              placeholder="—"
              disabled
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialog.open = false">{{
            td("common.button.close")
          }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="VersionManagement">
import useDefaultLang from "@/composables/useDefaultLang";
import { reactive, ref } from "vue";
import request from "@/utils/request";

const { td } = useDefaultLang();
const props = defineProps({ detail: { type: Object, required: true } });
const tableRef = ref(null);
const labelPosition = "right";

const tableStroe = reactive({
  columns: [
    {
      label: td("common.texts.number"),
      prop: "id",
      sortable: true,
      width: 60,
    },
    {
      label: td("meta.released.structured.table.tableName"),
      prop: "tableName",
      align: "left",
      showOverflowTooltip: {
        effect: "light",
      },
      minWidth: 140,
    },
    {
      label: td("meta.released.structured.table.tableComment"),
      prop: "tableComment",
      align: "left",
      showOverflowTooltip: {
        effect: "light",
      },
      minWidth: 240,
    },
    {
      label: td("meta.unreleased.structured.table.detail.version"),
      prop: "version",
      width: 90,
    },
    {
      label: td("meta.unreleased.structured.table.detail.updateType"),
      prop: "updateType",
      width: 90,
    },
    {
      label: td("meta.unreleased.structured.table.detail.updateMsg"),
      prop: "updateMsg",
      minWidth: 240,
      align: "left",
      showOverflowTooltip: {
        effect: "light",
      },
    },
    {
      label: td("common.texts.createdBy"),
      prop: "createBy",
      width: 90,
    },
    {
      label: td("common.texts.createdTime"),
      prop: "createTime",
      sortable: true,
      width: 160,
      date: true,
    },
    {
      label: td("common.texts.operation"),
      width: 240,
      fixed: "right",
      slot: "handle",
    },
  ],
  func: (query = {}) => request({
    url: '/mc/mcTableLog/list',
    method: 'get',
    params: { ...query, tableId: props.detail.id },
  }),
});

const dialog = reactive({
  open: false,
  row: {},
});

function handleDetailClick(row) {
  dialog.row = row;
  dialog.open = true;
}
</script>
