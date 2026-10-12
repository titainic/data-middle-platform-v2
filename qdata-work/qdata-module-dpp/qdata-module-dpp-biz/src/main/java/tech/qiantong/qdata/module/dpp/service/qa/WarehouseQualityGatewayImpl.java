package tech.qiantong.qdata.module.dpp.service.qa;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseQualityGateway;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WarehouseQualityGatewayImpl implements WarehouseQualityGateway {
    private final ObjectProvider<IDppQualityTaskService> tasks;
    private final QualityProjectAccess projects;
    private static final List<String> RULE_FIELDS=List.of("id","taskId","objId","objName","name","ruleCode","ruleName","warningLevel","status","dimensionType","ruleType","ruleDescription","errDescription","suggestion","whereClause","tableName","evaColumn","rule");

    @Override public Map<String,Object> template(long taskId) {
        var task=tasks.getObject().getDppQualityTaskById(taskId);
        if(task==null)throw new IllegalArgumentException("质量模板不存在");
        var objects=task.getDppQualityTaskObjSaveReqVO();
        if(objects==null || objects.size()!=1)throw new IllegalArgumentException("批次质量绑定需选择单一数据集的检查模板");
        var definitions=task.getDppQualityTaskEvaluateRespVOS().stream().filter(rule->"1".equals(rule.getStatus()))
                .sorted(Comparator.comparing(DppQualityTaskEvaluateRespVO::getId)).toList();
        if(definitions.isEmpty())throw new IllegalArgumentException("质量模板没有启用的规则");
        Map<String,Object> result=fields(task,List.of("id","taskName","catCode","priority","workerGroup","retryTimes","retryInterval","delayTime","strategy","assetId"));
        result.put("object",fields(objects.get(0),List.of("id","taskId","name","datasourceId","tableName")));
        result.put("rules",definitions.stream().map(rule->fields(rule,RULE_FIELDS)).toList());
        return result;
    }
    @Override public Prepared create(String name,long datasourceId,String table,long assetId,Map<String,Object> template) {
        var request=JSON.parseObject(JSON.toJSONString(template),DppQualityTaskSaveReqVO.class);
        request.setId(null);request.setTaskName(name.length()>128?name.substring(0,128):name);
        request.setAssetFlag("0");request.setAssetId(assetId);request.setProjectId(projects.chooseProject(null,assetId,"add"));request.setStatus("1");request.setCycle("0 0 0 1 1 ? 2099");
        request.setContact(SecurityUtils.getLoginUser().getUser().getNickName());request.setContactId(String.valueOf(SecurityUtils.getUserId()));
        request.setContactNumber(SecurityUtils.getLoginUser().getUser().getPhonenumber());
        request.setCreatorId(SecurityUtils.getUserId());request.setCreateBy(SecurityUtils.getLoginUser().getUser().getNickName());request.setCreateTime(new Date());
        var object=new DppQualityTaskObjSaveReqVO();object.setName(table);object.setDatasourceId(datasourceId);object.setTableName(table);
        request.setDppQualityTaskObjSaveReqVO(List.of(object));
        var originals=JSON.parseArray(JSON.toJSONString(template.get("rules")),DppQualityTaskEvaluateSaveReqVO.class);
        List<Long> oldIds=originals.stream().map(DppQualityTaskEvaluateSaveReqVO::getId).toList();
        for(var rule:originals) { rule.setId(null);rule.setTaskId(null);rule.setObjId(null);rule.setObjName(table);rule.setDatasourceId(datasourceId);rule.setTableName(table); }
        request.setDppQualityTaskEvaluateSaveReqVO(originals);
        long id=tasks.getObject().createDppQualityTask(request);
        var created=tasks.getObject().getDppQualityTaskById(id);
        var rules=created.getDppQualityTaskEvaluateRespVOS().stream().sorted(Comparator.comparing(DppQualityTaskEvaluateRespVO::getId)).toList();
        if(rules.size()!=oldIds.size())throw new IllegalStateException("质量任务规则没有完整登记");
        Map<Long,Long> ruleIds=new LinkedHashMap<>();for(int i=0;i<oldIds.size();i++)ruleIds.put(oldIds.get(i),rules.get(i).getId());
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("formatVersion",1);plan.put("projectId",request.getProjectId());plan.put("taskId",id);plan.put("taskName",created.getTaskName());
        plan.put("objects",created.getDppQualityTaskObjSaveReqVO().stream().map(value->fields(value,List.of("id","taskId","name","datasourceId","tableName"))).toList());
        plan.put("rules",rules.stream().map(value->fields(value,RULE_FIELDS)).toList());
        return new Prepared(id,JSON.toJSONString(plan),ruleIds);
    }
    @Override public void start(long taskId) {
        var status=new DppQualityTaskSaveReqVO();status.setId(taskId);status.setStatus("0");tasks.getObject().updateDppQualityTaskStatus(status);
        var response=tasks.getObject().startDppQualityTask(taskId);
        if(!Integer.valueOf(200).equals(response.get("code")))throw new IllegalStateException(String.valueOf(response.get("msg")));
    }
    @Override public void pause(long taskId) {
        var status=new DppQualityTaskSaveReqVO();status.setId(taskId);status.setStatus("1");tasks.getObject().updateDppQualityTaskStatus(status);
    }
    private static Map<String,Object> fields(Object value,List<String> names) {
        var bean=new BeanWrapperImpl(value);Map<String,Object> result=new LinkedHashMap<>();
        names.forEach(name->result.put(name,bean.getPropertyValue(name)));return result;
    }
}
