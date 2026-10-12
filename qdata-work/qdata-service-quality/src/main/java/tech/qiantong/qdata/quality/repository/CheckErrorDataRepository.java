/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.quality.repository;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.quality.controller.quality.vo.CheckErrorDataReqDTO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.CheckErrorData;
import java.sql.Timestamp;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class CheckErrorDataRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<CheckErrorData> ROW = (rs, n) -> {
        String data = rs.getString("data_json");
        String old = rs.getString("data_json_old");
        return CheckErrorData.builder().id(rs.getString("id")).reportId(rs.getString("report_id"))
                .count(rs.getInt("total_count")).errorCount(rs.getInt("error_count"))
                .time(rs.getTimestamp("check_time",Calendar.getInstance(TimeZone.getTimeZone("UTC")))).repair(rs.getInt("repair")).remark(rs.getString("remark"))
                .dataJsonStr(data).jsonData(JSONObject.parseObject(data))
                .dataJsonStrOLd(old).jsonDataOld(JSONObject.parseObject(rs.getString("json_data_old") == null ? old : rs.getString("json_data_old"))).build();
    };

    @Transactional
    public void saveAll(List<CheckErrorData> records) {
        if (records.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO quality_error_data (id,report_id,total_count,error_count,check_time,data_json,data_json_old,repair,remark,search_json,json_data_old) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                records, 500, (ps, item) -> {
                    if (item.getReportId() == null || item.getReportId().isBlank()) throw new IllegalArgumentException("Report is required");
                    if (item.getId() == null) item.setId(UUID.randomUUID().toString());
                    ps.setString(1,item.getId()); ps.setString(2,item.getReportId());
                    ps.setObject(3,item.getCount()); ps.setObject(4,item.getErrorCount());
                    ps.setTimestamp(5,new Timestamp(item.getTime().getTime()),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    ps.setString(6,item.getDataJsonStr()); ps.setString(7,item.getDataJsonStrOLd());
                    ps.setObject(8,item.getRepair()); ps.setString(9,item.getRemark());
                    ps.setString(10,searchJson(item.getDataJsonStr()));
                    ps.setString(11,item.getJsonDataOld()==null ? item.getDataJsonStrOLd() : JSONObject.toJSONString(item.getJsonDataOld(),JSONWriter.Feature.WriteMapNullValue));
                });
    }

    // Keep the exact payload in text: native MySQL JSON parses fractional numbers as doubles.
    // Search values are strings so precision is preserved in both display and keyword filtering.
    static String searchJson(String json) {
        JSONObject result = new JSONObject();
        JSONObject.parseObject(json).forEach((key,value) -> result.put(key,
                value == null ? null : value instanceof String ? value : JSONObject.toJSONString(value,JSONWriter.Feature.WriteMapNullValue)));
        return result.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }

    public CheckErrorData getById(String reportId, String id) {
        List<CheckErrorData> rows = jdbc.query("SELECT * FROM quality_error_data WHERE report_id=? AND id=?",ROW,reportId,id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Page<CheckErrorData> page(PageRequest page, CheckErrorDataReqDTO dto) {
        StringBuilder where = new StringBuilder(" WHERE report_id=?");
        List<Object> args = new ArrayList<>(); args.add(dto.getReportId());
        if (dto.getRepair()!=null) { where.append(" AND repair=?");args.add(dto.getRepair()); }
        List<String> fields = new ArrayList<>();
        if (dto.getKeyWordData()!=null) dto.getKeyWordData().forEach((key,value)->{
            if (value != null) {
                fields.add("JSON_UNQUOTE(JSON_EXTRACT(search_json,?)) COLLATE utf8mb4_unicode_ci LIKE ? ESCAPE '!'");
                args.add("$."+JSONObject.toJSONString(key));
                args.add("%"+value.toString().replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            }
        });
        if (!fields.isEmpty()) where.append(" AND (").append(String.join(" OR ",fields)).append(")");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM quality_error_data"+where,Long.class,args.toArray());
        args.add(page.getPageSize());args.add(page.getOffset());
        List<CheckErrorData> rows = jdbc.query("SELECT * FROM quality_error_data"+where+" ORDER BY check_time DESC,id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        return new PageImpl<>(rows,page,count==null?0:count);
    }

    public CheckErrorData getByIdForUpdate(String reportId, String id) {
        return lockRows(reportId,List.of(id)).get(0);
    }

    private List<CheckErrorData> lockRows(String reportId, List<String> ids) {
        if (reportId==null || reportId.isBlank() || ids==null || ids.isEmpty() || ids.size()>500
                || ids.stream().anyMatch(id->id==null || id.isBlank()) || new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("请选择同一评测中的1至500条问题记录，不能重复选择");
        List<Object> args=new ArrayList<>();args.add(reportId);args.addAll(ids);
        List<CheckErrorData> rows=jdbc.query("SELECT * FROM quality_error_data WHERE report_id=? AND id IN ("
                +String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id FOR UPDATE",ROW,args.toArray());
        if (rows.size()!=ids.size()) throw new IllegalArgumentException("部分问题记录不存在或不属于当前评测，请刷新后重试");
        return rows;
    }

    @Transactional
    public boolean updateData(String reportId, String id, Map<String,Object> updated, String operator, java.util.function.BooleanSupplier updateSource) {
        CheckErrorData before=getByIdForUpdate(reportId,id);
        if (before.getRepair()!=0) throw new IllegalArgumentException("请先将问题重新打开为待处理，再提交修复");
        String json=JSONObject.toJSONString(updated,JSONWriter.Feature.WriteMapNullValue);
        String search=searchJson(json);
        // Validate and write the audit record before changing the source; both local writes share this transaction.
        audit(before,"REPAIR",1,before.getRemark(),json,"",operator);
        if (!updateSource.getAsBoolean()) throw new IllegalArgumentException("源数据未更新，可能已发生变化或数据源不支持行级修改，请核对后重试");
        jdbc.update("UPDATE quality_error_data SET data_json_old=data_json,json_data_old=data_json,data_json=?,search_json=?,repair=1 WHERE report_id=? AND id=?",json,search,reportId,id);
        return true;
    }

    @Transactional
    public boolean updateRemark(String reportId, String id, String remark, String operator) {
        CheckErrorData before=getByIdForUpdate(reportId,id);
        String text=remark==null?"":remark;
        if (text.length()>2000) throw new IllegalArgumentException("备注不能超过2000字");
        jdbc.update("UPDATE quality_error_data SET remark=? WHERE report_id=? AND id=?",text,reportId,id);
        audit(before,"REMARK",before.getRepair(),text,null,"",operator);
        return true;
    }

    @Transactional
    public boolean changeState(String reportId, List<String> ids, String action, String reason, String operator) {
        Set<Integer> allowed;
        int target;
        String operation;
        switch (action) {
            case "3": allowed=Set.of(0,1,3);target=2;operation="IGNORE";break;
            case "4": allowed=Set.of(1);target=0;operation="REOPEN_REPAIR";break;
            case "5": allowed=Set.of(2);target=0;operation="REVOKE_IGNORE";break;
            case "6": allowed=Set.of(3);target=0;operation="REVOKE_DEFER";break;
            case "7": allowed=Set.of(1,2,3);target=0;operation="RETURN";break;
            case "8": allowed=Set.of(0);target=3;operation="DEFER";break;
            default:throw new IllegalArgumentException("不支持的问题处理操作");
        }
        String note=reason==null?"":reason.trim();
        if (note.length()>1000 || (("7".equals(action)||"8".equals(action)) && note.isEmpty()))
            throw new IllegalArgumentException("暂缓或退回时请填写1至1000字的原因");
        List<CheckErrorData> rows=lockRows(reportId,ids);
        if (rows.stream().anyMatch(r->!allowed.contains(r.getRepair())))
            throw new IllegalArgumentException("所选问题的状态不适用于当前操作，未修改任何记录，请刷新后重新选择");
        for (CheckErrorData before:rows) {
            jdbc.update("UPDATE quality_error_data SET repair=? WHERE report_id=? AND id=?",target,reportId,before.getId());
            audit(before,operation,target,before.getRemark(),null,note,operator);
        }
        return true;
    }

    private void audit(CheckErrorData before, String action, int status, String remark, String data, String reason, String operator) {
        jdbc.update("INSERT INTO quality_error_data_action (error_data_id,report_id,action,old_repair,new_repair,old_remark,new_remark,old_data,new_data,reason,operator_name,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                before.getId(),before.getReportId(),action,before.getRepair(),status,before.getRemark(),remark,
                data==null?null:before.getDataJsonStr(),data,reason,operator==null||operator.isBlank()?"system":operator,System.currentTimeMillis());
    }

    public List<Map<String,Object>> history(String reportId, String id) {
        if (reportId==null || reportId.isBlank() || id==null || id.isBlank()) throw new IllegalArgumentException("评测和问题记录不能为空");
        return jdbc.queryForList("SELECT id,action,old_repair AS oldRepair,new_repair AS newRepair,old_remark AS oldRemark,new_remark AS newRemark,old_data AS oldData,new_data AS newData,reason,operator_name AS operator,created_at AS createdAt FROM quality_error_data_action WHERE report_id=? AND error_data_id=? ORDER BY id DESC LIMIT 200",reportId,id);
    }
}
