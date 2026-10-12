<template>
  <nav class="model-workspace-tabs" aria-label="模型工作区切换">
    <span class="workspace-label">模型工作区</span>
    <RouterLink
      v-for="item in workspaces"
      :key="item.path"
      :to="item.path"
      :class="{ 'workspace-active': route.path === item.path }"
      :aria-current="route.path === item.path ? 'page' : undefined"
      :title="item.description"
    >
      {{ item.label }}
    </RouterLink>
  </nav>
</template>

<script setup>
import { RouterLink, useRoute } from 'vue-router';
const route = useRoute();
const workspaces = [
  { path: '/dm/model/create', label: '创建模型', description: '表格方式管理模型、字段与物化' },
  { path: '/dm/model/warehouse', label: 'Hive 分层建模', description: 'ODS → DWD → DWS → DWT → ADS 分层建模与加工' },
  { path: '/dm/model/architecture', label: '3D 部署与数据架构', description: '可编辑部署拓扑、数据流转及资源关联' },
];
</script>

<style scoped>
.model-workspace-tabs {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 6px;
  min-height: 48px;
  margin-bottom: 16px;
  padding: 0 12px;
  border-bottom: 1px solid var(--el-border-color-light, #e4e7ed);
  background: var(--el-bg-color, #fff);
  overflow-x: auto;
  white-space: nowrap;
}
.workspace-label { margin-right: 14px; color: #596d82; font-size: 13px; }
.model-workspace-tabs a {
  display: block;
  padding: 14px 18px 12px;
  border-bottom: 3px solid transparent;
  color: var(--el-text-color-regular, #526174);
  font-size: 14px;
  text-decoration: none;
}
.model-workspace-tabs a:hover { background: var(--el-fill-color-light, #f5f7fa); color: var(--el-color-primary, #376ef0); }
.model-workspace-tabs a.workspace-active { border-bottom-color: var(--el-color-primary, #376ef0); color: var(--el-color-primary, #376ef0); font-weight: 600; }
.model-workspace-tabs a:focus-visible { outline: 2px solid var(--el-color-primary, #376ef0); outline-offset: -4px; }
@media (max-width: 900px) { .workspace-label { display: none; } .model-workspace-tabs a { padding-inline: 12px; } }
</style>
