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
  <!-- Asset lineage tab -->
  <div class="containerServer" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-tabs v-model="view">
    <el-tab-pane label="表级血缘" name="tables">
    <p class="lineage-legend">实线：加工依赖 · 虚线：维度引用</p>
    <el-empty :description="td('common.noAssetLineage')" v-if="noData">
    </el-empty>
    <div id="containerServer-container" ref="graphContainer"></div>
    </el-tab-pane>
    <el-tab-pane label="字段级血缘" name="fields"><WarehouseFieldLineage :lineage="lineagePayload" /></el-tab-pane>
    </el-tabs>
    <TeleportContainer />
  </div>
</template>

<script setup name="lineage">
import useDefaultLang from "@/composables/useDefaultLang";
const { td } = useDefaultLang();
import DataProcessingDagNode from "../components/lineageItem.vue";
import WarehouseFieldLineage from "../components/warehouseFieldLineage.vue";
import { Graph, Path, Edge, Platform } from "@antv/x6";
import { Selection } from "@antv/x6-plugin-selection";
import { register, getTeleport } from "@antv/x6-vue-shape";
import { dataLineage } from "@/api/da/asset/asset";
import { DagreLayout } from '@antv/layout';
const { proxy } = getCurrentInstance();
const props = defineProps({
  form1: {
    type: Object,
    default: () => ({}),
  },
});

let graph = null;
const view=ref('tables'),lineagePayload=ref({});
let requestSequence=0;

const initGraph = () => {
  if(!proxy.$refs.graphContainer)return;
  graph = new Graph({
    container: proxy.$refs.graphContainer,
    width: 1644,
    height: 620,
    autoResize: true,
    interactive: true,
    panning: { enabled: true, eventTypes: ["leftMouseDown", "mouseWheel"] },
    mousewheel: { enabled: true, modifiers: "ctrl", factor: 1.1, maxScale: 1.5, minScale: 0.5 },
    highlighting: { magnetAdsorbed: { name: "stroke", args: { attrs: { fill: "#fff", stroke: "#31d0c6", strokeWidth: 4 } } } },
    interacting: { magnetConnectable: false },
    connecting: {
      snap: true,
      allowBlank: false,
      allowLoop: false,
      highlight: true,
      sourceAnchor: { name: "left", args: { dx: Platform.IS_SAFARI ? 4 : 8 } },
      targetAnchor: { name: "right", args: { dx: Platform.IS_SAFARI ? 4 : -8 } },
      createEdge() {
        return graph.createEdge({ shape: "asset-lineage-curve", attrs: { line: { strokeDasharray: "5 5" } }, zIndex: -1 });
      },
      validateConnection({ sourceMagnet, targetMagnet }) {
        if (!sourceMagnet || sourceMagnet.getAttribute("port-group") === "in") return false;
        if (!targetMagnet || targetMagnet.getAttribute("port-group") !== "in") return false;
        return true;
      },
    },
  });

  graph.use(new Selection({ multiple: true, rubberEdge: true, rubberNode: true, modifiers: "shift", rubberband: true }));
};

Graph.registerConnector("assetLineageConnector", (sourcePoint, targetPoint) => {
  const hgap = Math.abs(targetPoint.x - sourcePoint.x);
  const offset = Math.max(hgap * 0.6, 80); // Control curve bending

  const path = new Path();
  // Use sourcePoint directly as the starting point
  path.appendSegment(Path.createSegment("M", sourcePoint.x, sourcePoint.y));
  // curve control points
  path.appendSegment(Path.createSegment(
    "C",
    sourcePoint.x + offset,
    sourcePoint.y,
    targetPoint.x - offset,
    targetPoint.y,
    targetPoint.x,
    targetPoint.y
  ));

  return path.serialize();
}, true);
Graph.registerEdge("asset-lineage-curve", {
  inherit:"edge",
  markup: [
    { tagName: "path", selector: "wrap", attrs: { fill: "none", cursor: "pointer", stroke: "transparent", strokeLinecap: "round" } },
    { tagName: "path", selector: "line", attrs: { fill: "none", pointerEvents: "none" } },
  ],
  connector: { name: "assetLineageConnector" },
  attrs: { wrap: { connection: true, strokeWidth: 10, strokeLinejoin: "round" }, line: { connection: true, stroke: "#A2B1C3", strokeWidth: 1, targetMarker: { name: "classic", size: 6 } } },
}, true);

register({
  shape: "asset-lineage-node",
  width: 180,
  height: 48,
  component: DataProcessingDagNode,
  ports: {
    groups: {
      in: { position: "left", attrs: { circle: { r: 4, magnet: true, stroke: "transparent", strokeWidth: 1, fill: "transparent", } } },
      out: { position: { name: "right", args: { dx: -32 } }, attrs: { circle: { r: 4, magnet: true, stroke: "transparent", strokeWidth: 1, fill: "transparent" } } },
    },
  },
});

const TeleportContainer = getTeleport();

let noData = ref(false);
const loading = ref(false);
const error = ref('');
const getData = async () => {
  if(!graph || !props.form1.id)return;
  const sequence=++requestSequence,assetId=props.form1.id;lineagePayload.value={};
  loading.value=true;error.value='';
  try {
  const defaultData = await dataLineage(assetId);
  if(!graph || sequence!==requestSequence || props.form1.id!==assetId)return;
  lineagePayload.value=defaultData?.data || {};

  const nodes = [];
  const edges = [];
  const tasks = defaultData?.data?.tasks || [];
  const tables = defaultData?.data?.tables || [];
  const rels = defaultData?.data?.rels || [];

  if (tasks.length === 0 && tables.length === 0) {
    noData.value = true;       //
    graph.clearCells();
    loading.value = false;
    return;
  }
  loading.value = false;
  noData.value = false;
  tasks.forEach(task => {
    nodes.push({
      id: String(task.id),
      shape: 'asset-lineage-node',
      label: task.name,
      zIndex: 1,
      data: { ...task, type1: task.type, type: 'TASK', },
    });
  });

  tables.forEach(table => {
    nodes.push({
      id: String(table.id),
      shape: 'asset-lineage-node',
      label: table.name,
      zIndex: 1,
      data: { ...table, type: 'TABLE', },
    });
  });

  rels.forEach((rel, idx) => {
    edges.push({
      id: `edge-${idx}`,
      shape: 'asset-lineage-curve',
      source: String(rel.startNodeId),
      target: String(rel.endNodeId),
      data: rel.properties,
      attrs:{line:{stroke:rel.properties?.kind==='DIMENSION_REFERENCE'?'#8491a5':'#3471f9',strokeDasharray:rel.properties?.kind==='DIMENSION_REFERENCE'?'6 4':''}},
      zIndex: -1
    });
  });
  nodes.forEach(node => {
    const nodeId = String(node.id)
    const hasChildFromRels = edges.some(e => String(e.source) === nodeId)

    let hasChildFromProps = false
    if (node.data.type === 'TABLE') {
      hasChildFromProps = Array.isArray(node.data.tableToTaskRels) && node.data.tableToTaskRels.length > 0
    } else if (node.data.type === 'TASK') {
      hasChildFromProps = Array.isArray(node.data.taskToTableRels) && node.data.taskToTableRels.length > 0
    }

    const hasChild = hasChildFromRels || hasChildFromProps
    if (hasChild && tasks.length) node.data.leaf = true

    node.data.collapsed = true
  })
  if (nodes.length === 1) {
    await graph.fromJSON({ nodes, edges });
    graph.zoomTo(1, { absolute: true, minScale: 0.5, maxScale: 1.5 });
    return;
  }

  const dagreLayout = new DagreLayout({
    type: 'dagre',
    rankdir: 'LR',
    nodesep: 50,
    ranksep: 80,
  });

  const layoutData = dagreLayout.layout({
    nodes: nodes.map(n => ({ id: n.id, width: 180, height: 48 })),
    edges: edges.map(e => ({ source: e.source, target: e.target })),
  });

  layoutData.nodes.forEach(pos => {
    const node = nodes.find(n => n.id === pos.id);
    if (node) {
      node.x = pos.x;
      node.y = pos.y;
      // highlighting: { magnetAdsorbed: { name: "stroke", args: { attrs: { fill: "#fff", stroke: "#31d0c6", strokeWidth: 4 } } } },
    }
  });
  await graph.fromJSON({ nodes, edges });
  if (nodes.length != 1) {
    graph.zoomToFit({ padding: 50 });
  }


  const activeNode = nodes.find(n => n.data.type === 'TASK');
  if (activeNode) graph.select(activeNode.id);

  }catch(failure){if(sequence===requestSequence){error.value=failure.message||String(failure);lineagePayload.value={};graph?.clearCells();}}finally{if(sequence===requestSequence)loading.value=false;}
};



watch(()=>props.form1.id,()=>{view.value='tables';getData();});
onBeforeUnmount(()=>{requestSequence++;graph?.dispose();graph=null;});
onMounted(() => {
  nextTick(() => {
    initGraph();
    getData();
  });
});
</script>

<style lang="scss">
@keyframes running-line {
  to {
    stroke-dashoffset: -1000;
  }
}
</style>

<style lang="scss" scoped>
.lineage-legend{font-size:13px;color:var(--el-text-color-secondary);margin:8px 0 12px;}
.containerServer {
  width: 100%;
  height: 620px;

  #containerServer-container {
    width: 100%;
    height: 100%;
  }
}
</style>
