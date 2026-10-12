export const stages = [
 {id:'SOURCE',name:'业务数据源',color:'#8777c7'}, {id:'ODS',name:'ODS · 原始',color:'#4d91bc'},
 {id:'DWD',name:'DWD · 清洗',color:'#3ca89b'}, {id:'DWS',name:'DWS · 融合',color:'#4596af'},
 {id:'DWT',name:'DWT · 主题',color:'#8377c5'}, {id:'ADS',name:'ADS · 应用',color:'#527ecb'},
 {id:'DORIS',name:'Doris · ADS 查询副本',color:'#32aab6'}, {id:'API',name:'API 服务',color:'#d19b55'}, {id:'APP',name:'业务应用',color:'#6485a3'}
];
export const clone = value => JSON.parse(JSON.stringify(value));
// getRandomValues is available on the existing HTTP intranet deployment; randomUUID is secure-context only.
export const uid = prefix => { const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);return `${prefix}-${Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('')}`; };
export const stateLabel = state => ({REGISTERED:'已关联登记',PLANNED:'规划',UNKNOWN:'待确认'})[state] || '待确认';
export function connectionError(a,b,kind='PROCESS') {
 if(!a || !b || a.id===b.id) return '请选择两个不同的节点';
 if(a.scope!==b.scope) return '部署与数据视图通过节点的部署关联字段连接';
 if(a.scope==='DEPLOYMENT') return kind==='NETWORK' ? '' : '部署视图使用部署/网络连接';
 if(kind==='DIMENSION') return a.layer==='DWT' && b.layer==='DWT' ? '' : '星型关系只能连接 DWT 主题内节点';
 if(kind!=='PROCESS') return '数据视图使用加工/同步连线';
 return stages.findIndex(s=>s.id===b.layer)===stages.findIndex(s=>s.id===a.layer)+1 ? '' : `禁止跨层连线：${a.layer} → ${b.layer}`;
}
export function removeNode(graph,id) {
 graph.nodes=graph.nodes.filter(n=>n.id!==id);
 graph.edges=graph.edges.filter(e=>e.source!==id && e.target!==id);
 graph.nodes.forEach(n=>{if(n.deploymentId===id)n.deploymentId=null;});
}
const node=(id,label,kind,scope,x,z,extra={})=>({id,label,kind,scope,x,z,state:'PLANNED',engine:'',layer:'',zone:'',address:'',description:'',refType:null,refId:null,deploymentId:null,...extra});
const edge=(source,target,kind,label,state='PLANNED')=>({id:`edge-${source}-${target}-${kind}`,source,target,kind,label,state,taskId:null});
export function createDefault(catalog) {
 const d={id:null,revision:0,name:'Hive 五层数仓与 Doris 服务架构',nodes:[],edges:[]};
 const hive=catalog.datasources.find(s=>s.engine.toUpperCase()==='HIVE');
 const doris=catalog.datasources.find(s=>s.engine.toUpperCase()==='DORIS');
 d.nodes.push(node('platform-host','qData 应用主机','HOST','DEPLOYMENT',-20,10,{zone:'平台服务',state:'UNKNOWN',description:'手工部署节点，请核对主机地址。图形配置不代表运行探测。'}));
 d.nodes.push(node('platform-app','qData 数据中台','SERVICE','DEPLOYMENT',-20,0,{zone:'平台服务',state:'UNKNOWN',description:'负责建模、开发、治理及 API 管理'}));
 d.edges.push(edge('platform-host','platform-app','NETWORK','部署承载'));
 if(hive){d.nodes.push(node('hive-host',hive.address || 'Hive 连接地址','HOST','DEPLOYMENT',0,10,{zone:'Hive / HDFS',engine:'Hive',address:hive.address,state:'REGISTERED',refType:'DATASOURCE',refId:hive.id,description:'来源于已登记的 JDBC 连接地址；不等同于完整集群主机清单'}));
 d.nodes.push(node('hive-service','HiveServer2','DATABASE','DEPLOYMENT',0,0,{zone:'Hive / HDFS',engine:'Hive',address:`${hive.address}:${hive.port}`,state:'REGISTERED',refType:'DATASOURCE',refId:hive.id}));d.edges.push(edge('hive-host','hive-service','NETWORK','JDBC 服务地址','REGISTERED'));d.edges.push(edge('platform-app','hive-service','NETWORK','JDBC 建模与加工','REGISTERED'));}
 d.nodes.push(node('doris-service','Doris 查询集群','DATABASE','DEPLOYMENT',22,0,{zone:'Doris 查询',engine:'Doris',state:doris?'REGISTERED':'PLANNED',refType:doris?'DATASOURCE':null,refId:doris?.id || null,address:doris?`${doris.address}:${doris.port}`:'',description:doris?'已关联 Doris 数据源':'尚未登记 Doris 数据源；这是规划节点'}));
 d.nodes.push(node('api-service','API 服务入口','SERVICE','DEPLOYMENT',42,0,{zone:'服务与访问',description:'关联已有 API 或通过数据服务注册；本图不会自动发布接口'}));
 d.nodes.push(node('app-client','业务应用 / 大屏','CLIENT','DEPLOYMENT',42,12,{zone:'服务与访问'}));
 if(hive)d.edges.push(edge('hive-service','doris-service','NETWORK','ADS 同步链路'));d.edges.push(edge('doris-service','api-service','NETWORK','查询服务'));d.edges.push(edge('api-service','app-client','NETWORK','接口访问'));
 const byLayer={};catalog.models.forEach(m=>{(byLayer[m.layer] ||= []).push(m);});
 const modelIds=new Set(catalog.models.map(m=>m.id));
 for(const m of catalog.models){const index=(byLayer[m.layer]||[]).indexOf(m);const total=byLayer[m.layer].length;
  d.nodes.push(node(`model-${m.id}`,m.name,'TABLE','DATA',(stages.findIndex(s=>s.id===m.layer)-4)*13,(index-(total-1)/2)*10,{layer:m.layer,engine:'Hive',zone:m.layer,state:'REGISTERED',refType:'MODEL',refId:m.id,deploymentId:hive?'hive-service':null,description:`${m.tableName}\n粒度：${m.grain}\n主题：${m.topic || '未填写'}`}));
  for(const id of m.upstreamIds || [])if(modelIds.has(id))d.edges.push(edge(`model-${id}`,`model-${m.id}`,'PROCESS','模型加工','REGISTERED'));
  for(const rel of m.dimensions || [])if(modelIds.has(rel.modelId))d.edges.push(edge(`model-${m.id}`,`model-${rel.modelId}`,'DIMENSION',`${rel.column} → ${rel.targetColumn}`,'REGISTERED'));
  if(m.layer==='ODS' && m.rawTable){const sourceId=`raw-${m.id}`;const sourceVisible=catalog.datasources.some(source=>source.id===m.datasourceId);d.nodes.push(node(sourceId,m.rawTable,'DATABASE','DATA',-52,index*10,{layer:'SOURCE',zone:'SOURCE',engine:'Hive',refType:sourceVisible?'DATASOURCE':null,refId:sourceVisible?m.datasourceId:null,state:sourceVisible?'REGISTERED':'PLANNED',description:sourceVisible?'ODS 模型已登记的原始来源':'模型原始来源说明，尚未关联数据连接'}));d.edges.push(edge(sourceId,`model-${m.id}`,'PROCESS','原始采集',sourceVisible?'REGISTERED':'PLANNED'));}
 }
 // Empty warehouses still have an editable complete planning chain.
 for(let i=1;i<=5;i++)if(!byLayer[stages[i].id]?.length)d.nodes.push(node(`plan-${stages[i].id}`,stages[i].name,'TABLE','DATA',(i-4)*13,0,{layer:stages[i].id,engine:'Hive',zone:stages[i].id,deploymentId:hive?'hive-service':null}));
 if(!d.nodes.some(n=>n.scope==='DATA' && n.layer==='SOURCE'))d.nodes.push(node('source-plan','业务原始数据','DATABASE','DATA',-52,0,{layer:'SOURCE',zone:'SOURCE'}));
 d.nodes.push(node('doris-copy','ADS 查询副本','DATABASE','DATA',26,0,{layer:'DORIS',engine:'Doris',zone:'DORIS',deploymentId:'doris-service',state:doris?'REGISTERED':'PLANNED',refType:doris?'DATASOURCE':null,refId:doris?.id || null,description:'Hive ADS 同步到 Doris 内表；属于 ADS 的查询副本，不增加数仓层级'}));
 d.nodes.push(node('api-plan','注册 API 服务','API','DATA',39,0,{layer:'API',zone:'API',deploymentId:'api-service',description:'待关联实际 API；先确认 Doris 数据源及同步任务'}));
 d.nodes.push(node('app-plan','报表 / 大屏 / 应用','CLIENT','DATA',52,0,{layer:'APP',zone:'APP',deploymentId:'app-client'}));
 for(let i=0;i<stages.length-1;i++){
  const left=d.nodes.filter(n=>n.scope==='DATA' && n.layer===stages[i].id);const right=d.nodes.filter(n=>n.scope==='DATA' && n.layer===stages[i+1].id);
  for(const a of left)for(const b of right)if(!d.edges.some(e=>e.source===a.id && e.target===b.id) && (a.state==='PLANNED' || b.state==='PLANNED' || i>=5))d.edges.push(edge(a.id,b.id,'PROCESS',i===5?'ADS 同步':i===6?'API 注册 / 查询':'待配置加工'));
 }
 return d;
}
export function importResource(resource,type,scope,layer,index=0){
 const isModel=type==='MODEL';const stage=isModel?resource.layer:layer;
 return node(uid('resource'),resource.name,isModel?'TABLE':type==='API'?'API':'DATABASE',scope,scope==='DATA'?(Math.max(stages.findIndex(s=>s.id===stage),0)-4)*13:0,index*4,{layer:scope==='DATA'?stage:'',zone:scope==='DATA'?stage:'导入资源',engine:isModel?'Hive':resource.engine || '',address:resource.address || '',state:'REGISTERED',refType:type,refId:resource.id,description:isModel?`${resource.tableName}\n粒度：${resource.grain}`:'已关联平台登记信息，未进行实时健康探测'});
}
