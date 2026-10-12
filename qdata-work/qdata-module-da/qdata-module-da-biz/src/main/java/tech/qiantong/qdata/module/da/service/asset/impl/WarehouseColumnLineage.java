package tech.qiantong.qdata.module.da.service.asset.impl;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.repository.*;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.fasterxml.jackson.databind.*;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import com.alibaba.fastjson.JSONObject;
import tech.qiantong.qdata.common.utils.SecretRedactor;
import java.util.*;

/** Field provenance from registered contracts; all DDL is parsed in memory only. */
final class WarehouseColumnLineage {
 record Model(long id,String table,List<String> columns,JsonNode definition) {}
 record Output(String name,SQLExpr expr,SQLTableSource source) {}
 final Map<String,Model> tables=new LinkedHashMap<>();
 final Map<SQLSelectQuery,List<Output>> outputs=new IdentityHashMap<>();
 final Set<String> resolving=new HashSet<>();
 String defaultDatabase;
 static String name(String text){return text==null?"":text.replace("`","").replace("\"","").toLowerCase(Locale.ROOT);}
 record Result(List<JSONObject> fields,List<String> diagnostics,int declared,int resolvedDerived,int rawFields) {}
 private final SchemaRepository repository=new SchemaRepository(DbType.hive);
 private final Map<Long,Model> byId=new LinkedHashMap<>();
 private final Set<SQLSelectQueryBlock> tracedScopes=Collections.newSetFromMap(new IdentityHashMap<>());
 private final Map<SQLSelectQueryBlock,Integer> scopeNumbers=new IdentityHashMap<>();
 private final Map<String,JSONObject> expressionSteps=new LinkedHashMap<>();
 private String clause="VALUE";
 WarehouseColumnLineage(Collection<DpModelRespDTO> definitions,String fallbackDatabase){
  ObjectMapper mapper=new ObjectMapper();
  for(var dto:definitions){
   JsonNode d;try{d=mapper.readTree(dto.getWarehouseConfig());}catch(Exception failure){throw new IllegalArgumentException("数仓字段契约无法读取");}
   String database=d.path("databaseName").asText();if(database.isBlank())database=dto.getDatabaseName();if(database==null||database.isBlank())database=fallbackDatabase;
   String table=name(database+"."+d.path("tableName").asText());List<String> columns=new ArrayList<>();d.path("columns").forEach(c->columns.add(name(c.path("name").asText())));
   Model model=new Model(dto.getId(),table,columns,d);if(tables.putIfAbsent(table,model)!=null)throw new IllegalArgumentException("字段血缘中存在重复物理表");byId.put(model.id,model);
   if(!columns.isEmpty()){
    List<String> fields=new ArrayList<>();d.path("columns").forEach(c->fields.add("`"+c.path("name").asText()+"` "+c.path("type").asText()));
    repository.acceptDDL("CREATE TABLE "+table+" ("+String.join(",",fields)+")",DbType.hive);
   }
  }
 }
 Result resolve(){
  List<JSONObject> result=new ArrayList<>();List<String> diagnostics=new ArrayList<>();int declared=0,resolved=0,raw=0;
  for(Model model:tables.values()){
   declared+=model.columns.size();defaultDatabase=model.table.substring(0,model.table.indexOf('.'));outputs.clear();resolving.clear();scopeNumbers.clear();
   if(model.columns.isEmpty()){diagnostics.add("模型#"+model.id+"没有登记字段契约");continue;}
   SQLSelectQuery query=null;String queryError=null;
   boolean ods=model.definition.path("layer").asText().equals("ODS");
   if(!ods)try{
    String sql=model.definition.path("selectSql").asText();Set<String> expected=new LinkedHashSet<>();
    for(JsonNode id:model.definition.path("upstreamIds")){Model parent=byId.get(id.asLong());if(parent==null)throw new IllegalArgumentException("缺少登记的字段上游");expected.add(parent.table);}
    if(!tech.qiantong.qdata.common.database.core.HiveReadQuery.tables(sql,defaultDatabase).equals(expected))throw new IllegalArgumentException("SQL实际来源与登记上游不一致");
    repository.setDefaultSchema(defaultDatabase);var select=(SQLSelectStatement)SQLUtils.parseSingleStatement(sql,DbType.hive);
    repository.resolve(select,SchemaResolveVisitor.Option.ResolveAllColumn,SchemaResolveVisitor.Option.ResolveIdentifierAlias,SchemaResolveVisitor.Option.CheckColumnAmbiguous);
    select.accept(new SQLASTVisitorAdapter(){public void preVisit(SQLObject object){if(object instanceof SQLSelectQueryBlock block)scopeNumbers.putIfAbsent(block,scopeNumbers.size()+1);}});
    query=select.getSelect().getQuery();if(outputs(query).size()!=model.columns.size())throw new IllegalArgumentException("SELECT输出字段数与登记契约不一致");
   }catch(RuntimeException failure){queryError=brief(failure);}
   for(JsonNode column:model.definition.path("columns")){
    String key=name(column.path("name").asText());JSONObject field=new JSONObject(true);field.put("modelId",model.id);field.put("tableName",model.table);field.put("columnName",key);field.put("columnType",column.path("type").asText());field.put("modelRevision",model.definition.has("revision")?model.definition.get("revision").asInt():null);
    field.put("valueSources",List.of());field.put("conditionSources",List.of());field.put("rowSources",List.of());field.put("expressions",List.of());field.put("inputOrigins",List.of());
    List<Map<String,Object>> dimensions=new ArrayList<>();for(JsonNode relationship:model.definition.path("dimensions"))if(key.equals(name(relationship.path("column").asText()))){Model dimension=byId.get(relationship.path("modelId").asLong());if(dimension==null)throw new IllegalArgumentException("维度字段契约缺失");dimensions.add(reference(dimension,relationship.path("targetColumn").asText()));}field.put("dimensionReferences",dimensions);
    if(ods){field.put("resolution","RAW_BOUNDARY");field.put("originKind","INPUT");field.put("message","输入字段需关联固定接入实例");raw++;}
    else if(queryError!=null){field.put("resolution","UNRESOLVED");field.put("message",queryError);diagnostics.add("模型#"+model.id+"."+key+"："+queryError);}
    else try{
     tracedScopes.clear();expressionSteps.clear();clause="VALUE";Set<String> values=queryField(query,key);Set<String> rows=queryRows(query);Set<String> conditions=conditions();
     field.put("valueSources",references(values));field.put("conditionSources",references(conditions));field.put("rowSources",references(rows));field.put("expressions",new ArrayList<>(expressionSteps.values()));
     field.put("resolution","RESOLVED");field.put("originKind",values.isEmpty()?"GENERATED":"EXPRESSION");resolved++;
    }catch(RuntimeException failure){String reason=brief(failure);field.put("resolution","UNRESOLVED");field.put("message",reason);diagnostics.add("模型#"+model.id+"."+key+"："+reason);}
    result.add(field);
   }
  }
  return new Result(result,diagnostics,declared,resolved,raw);
 }
 private String brief(RuntimeException failure){String value=SecretRedactor.redact(Objects.toString(failure.getMessage(),"字段表达式尚未解析"));return value.length()>400?value.substring(0,400):value;}
 private Map<String,Object> reference(Model model,String column){return Map.of("modelId",model.id,"tableName",model.table,"columnName",column,"kind",column.equals("*")?"ROWS":"FIELD");}
 private List<Map<String,Object>> references(Set<String> names){List<Map<String,Object>> result=new ArrayList<>();for(String value:names){int split=value.lastIndexOf('.');Model model=tables.get(value.substring(0,split));if(model==null)throw new IllegalArgumentException("来源不在已登记血缘中");result.add(reference(model,value.substring(split+1)));}return result;}
 private void trace(SQLSelectQueryBlock block,Output output){
  tracedScopes.add(block);int scope=scopeNumbers.computeIfAbsent(block,unused->scopeNumbers.size()+1);String key=scope+":"+output.name+":"+clause;
  JSONObject step=new JSONObject(true);step.put("scope","query-"+scope);step.put("column",output.name);step.put("clause",clause);step.put("expression",SecretRedactor.redact(output.expr==null?"* → "+output.name:SQLUtils.toSQLString(output.expr,DbType.hive)));expressionSteps.putIfAbsent(key,step);
 }
 private Set<String> conditions(){
  Set<String> result=new TreeSet<>();Set<SQLSelectQueryBlock> done=Collections.newSetFromMap(new IdentityHashMap<>());
  while(true){var pending=tracedScopes.stream().filter(scope->!done.contains(scope)).sorted(Comparator.comparingInt(scope->scopeNumbers.getOrDefault(scope,0))).toList();if(pending.isEmpty())break;
   for(var scope:pending){done.add(scope);collectCondition(scope.getWhere(),"FILTER",result);collectJoin(scope.getFrom(),result);
    if(scope.getGroupBy()!=null){for(var expr:scope.getGroupBy().getItems())collectCondition(expr,"GROUP",result);collectCondition(scope.getGroupBy().getHaving(),"HAVING",result);}
    if(scope.getOrderBy()!=null)for(var item:scope.getOrderBy().getItems())collectCondition(item.getExpr(),"ORDER",result);
   }
  }
  clause="VALUE";return result;
 }
 private void collectCondition(SQLExpr expr,String kind,Set<String> result){if(expr==null)return;String previous=clause;clause=kind;try{result.addAll(expression(expr));JSONObject step=new JSONObject(true);step.put("scope","condition");step.put("column","");step.put("clause",kind);step.put("expression",SecretRedactor.redact(SQLUtils.toSQLString(expr,DbType.hive)));expressionSteps.putIfAbsent(kind+":"+step.getString("expression"),step);}finally{clause=previous;}}
 private void collectJoin(SQLTableSource source,Set<String> result){if(source instanceof SQLJoinTableSource join){collectCondition(join.getCondition(),"JOIN",result);collectJoin(join.getLeft(),result);collectJoin(join.getRight(),result);}}
 List<SQLSelectQuery> branches(SQLUnionQuery union){var rows=union.getRelations();return rows!=null&&!rows.isEmpty()?rows:List.of(union.getLeft(),union.getRight());}
 List<Output> outputs(SQLSelectQuery query){
  if(outputs.containsKey(query))return outputs.get(query);
  if(query instanceof SQLUnionQuery union){var result=outputs(branches(union).get(0));outputs.put(query,result);return result;}
  if(!(query instanceof SQLSelectQueryBlock block))throw new IllegalArgumentException("Unsupported query "+query.getClass().getSimpleName());
  List<Output> result=new ArrayList<>();
  for(var item:block.getSelectList()){
   var expr=item.getExpr();
   if(expr instanceof SQLAllColumnExpr){for(String col:columns(block.getFrom()))result.add(new Output(col,null,block.getFrom()));}
   else if(expr instanceof SQLPropertyExpr property && property.getName().equals("*")){
    var table=property.getResolvedTableSource();if(table==null)throw new IllegalArgumentException("Wildcard owner unresolved");for(String col:columns(table))result.add(new Output(col,null,table));
   }else{
    String alias=item.getAlias();if(alias==null && expr instanceof SQLName sqlName)alias=sqlName.getSimpleName();
    if(alias==null)throw new IllegalArgumentException("Output alias missing: "+expr);result.add(new Output(name(alias),expr,null));
   }
  }
  if(result.stream().map(Output::name).distinct().count()!=result.size())throw new IllegalArgumentException("Ambiguous duplicate output labels");
  outputs.put(query,result);return result;
 }
 Set<String> queryField(SQLSelectQuery query,String column){
  List<Output> list=outputs(query);int index=-1;for(int i=0;i<list.size();i++)if(list.get(i).name.equals(name(column))){index=i;break;}
  if(index<0)throw new IllegalArgumentException("Query field missing: "+column);
  return queryPosition(query,index);
 }
 Set<String> queryPosition(SQLSelectQuery query,int position){
  if(query instanceof SQLUnionQuery union){Set<String> refs=new TreeSet<>();for(var branch:branches(union)){if(outputs(branch).size()!=outputs(query).size())throw new IllegalArgumentException("UNION output widths differ");refs.addAll(queryPosition(branch,position));}return refs;}
  Output output=outputs(query).get(position);trace((SQLSelectQueryBlock)query,output);return output.expr==null?field(output.source,output.name):expression(output.expr);
 }
 Model physical(SQLExprTableSource source){String table=name(source.getExpr().toString());if(!table.contains("."))table=defaultDatabase+"."+table;return tables.get(table);}
 SQLTableSource virtual(SQLExprTableSource source){
  if(source.getExpr() instanceof SQLIdentifierExpr identifier){SQLTableSource target=identifier.getResolvedTableSource();if(target!=null && target!=source)return target;}
  return null;
 }
 List<String> columns(SQLTableSource source){
  if(source==null)return List.of();
  if(source instanceof SQLExprTableSource expr){var virtual=virtual(expr);if(virtual!=null)return columns(virtual);var physical=physical(expr);if(physical!=null)return physical.columns;}
  if(source instanceof SQLSubqueryTableSource sub)return outputs(sub.getSelect().getQuery()).stream().map(Output::name).toList();
  if(source instanceof SQLUnionQueryTableSource union)return outputs(union.getUnion()).stream().map(Output::name).toList();
  if(source instanceof SQLWithSubqueryClause.Entry entry)return outputs(entry.getSubQuery().getQuery()).stream().map(Output::name).toList();
  if(source instanceof SQLJoinTableSource join){List<String> result=new ArrayList<>(columns(join.getLeft()));result.addAll(columns(join.getRight()));return result;}
  throw new IllegalArgumentException("Unresolved source schema: "+source);
 }
 Set<String> field(SQLTableSource source,String column){
  if(source==null)throw new IllegalArgumentException("Column owner missing: "+column);
  String guard=System.identityHashCode(source)+":"+name(column);if(!resolving.add(guard))throw new IllegalArgumentException("Cyclic column resolution: "+column);
  try{
   if(source instanceof SQLExprTableSource expr){
    var target=virtual(expr);if(target!=null)return field(target,column);
    Model physical=physical(expr);if(physical!=null){if(!column.equals("*")&&!physical.columns.contains(name(column)))throw new IllegalArgumentException("Physical column missing: "+physical.table+"."+column);return Set.of(physical.table+"."+name(column));}
   }
   if(source instanceof SQLSubqueryTableSource sub)return queryField(sub.getSelect().getQuery(),column);
   if(source instanceof SQLUnionQueryTableSource union)return queryField(union.getUnion(),column);
   if(source instanceof SQLWithSubqueryClause.Entry entry)return queryField(entry.getSubQuery().getQuery(),column);
   if(source instanceof SQLJoinTableSource join){
    boolean left=columns(join.getLeft()).contains(name(column)),right=columns(join.getRight()).contains(name(column));
    if(left==right)throw new IllegalArgumentException("Ambiguous or missing joined field: "+column);return field(left?join.getLeft():join.getRight(),column);
   }
   throw new IllegalArgumentException("Unsupported column owner: "+source.getClass().getSimpleName());
  }finally{resolving.remove(guard);}
 }
 Set<String> rowSources(SQLTableSource source){
  if(source==null)return Set.of();
  if(source instanceof SQLExprTableSource expr){var virtual=virtual(expr);if(virtual!=null)return rowSources(virtual);var model=physical(expr);if(model!=null)return Set.of(model.table+".*");}
  if(source instanceof SQLSubqueryTableSource sub)return queryRows(sub.getSelect().getQuery());
  if(source instanceof SQLUnionQueryTableSource union)return queryRows(union.getUnion());
  if(source instanceof SQLWithSubqueryClause.Entry entry)return queryRows(entry.getSubQuery().getQuery());
  if(source instanceof SQLJoinTableSource join){Set<String> result=new TreeSet<>(rowSources(join.getLeft()));result.addAll(rowSources(join.getRight()));return result;}
  throw new IllegalArgumentException("Unresolved row source");
 }
 Set<String> queryRows(SQLSelectQuery query){
  if(query instanceof SQLSelectQueryBlock block){tracedScopes.add(block);return rowSources(block.getFrom());}
  if(query instanceof SQLUnionQuery union){Set<String> result=new TreeSet<>();for(var branch:branches(union))result.addAll(queryRows(branch));return result;}
  throw new IllegalArgumentException("Unsupported row query");
 }
 SQLSelectQueryBlock block(SQLObject item){for(SQLObject at=item;at!=null;at=at.getParent())if(at instanceof SQLSelectQueryBlock block)return block;throw new IllegalArgumentException("Expression scope missing");}
 Set<String> expression(SQLExpr expression){
  Set<String> refs=new TreeSet<>();
  expression.accept(new SQLASTVisitorAdapter(){
   public boolean visit(SQLExprTableSource source){return false;}
   public boolean visit(SQLPropertyExpr property){refs.addAll(field(property.getResolvedTableSource(),property.getName()));return false;}
   public boolean visit(SQLIdentifierExpr identifier){
    if(Set.of("current_timestamp","current_date","current_user","current_database").contains(name(identifier.getName())))return false;
    SQLTableSource source=identifier.getResolvedTableSource();
    if(source!=null)refs.addAll(field(source,identifier.getName()));
    else if(identifier.getResolvedColumnObject() instanceof SQLSelectItem item && item.getExpr()!=identifier)refs.addAll(expression(item.getExpr()));
    else throw new IllegalArgumentException("Identifier unresolved: "+identifier);
    return false;
   }
   public boolean visit(SQLAllColumnExpr star){refs.addAll(rowSources(block(star).getFrom()));return false;}
   public boolean visit(SQLQueryExpr query){throw new IllegalArgumentException("Scalar subquery requires dedicated lineage support");}
  });
  return refs;
 }
}
