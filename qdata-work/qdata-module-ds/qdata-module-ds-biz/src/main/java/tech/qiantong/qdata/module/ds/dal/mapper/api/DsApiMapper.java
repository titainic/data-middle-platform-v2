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

package tech.qiantong.qdata.module.ds.dal.mapper.api;

import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.apache.commons.lang3.StringUtils;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.module.ds.controller.admin.api.vo.DsApiPageReqVO;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.mybatis.core.mapper.BaseMapperX;

import java.util.Arrays;

/**
 * API service mapper interface
 *
 * @author lhs
 * @date 2025-02-12
 */
public interface DsApiMapper extends BaseMapperX<DsApiDO> {

    default PageResult<DsApiDO> selectPage(DsApiPageReqVO reqVO) {
        // Defines sortable fields to prevent SQL injection; values must match database column names.
        String sort=tech.qiantong.qdata.common.utils.StringUtils.toUnderScoreCase(reqVO.getOrderByColumn());
        if(sort!=null && !sort.isBlank())for(String column:sort.split(","))if(!java.util.Set.of("id","create_time","update_time","name","status","api_id","caller_time").contains(column.trim()))throw new IllegalArgumentException("不支持的排序字段");
        MPJLambdaWrapper<DsApiDO> lambdaWrapper = new MPJLambdaWrapper();
        lambdaWrapper.selectAll(DsApiDO.class)
                .eq(reqVO.getProjectId()!=null,DsApiDO::getProjectId,reqVO.getProjectId())
                .select("t2.NAME AS catName", "t3.NAME AS projectName")
                .leftJoin("ATT_PROJECT t3 ON t3.ID=t.PROJECT_ID")
                .leftJoin("ATT_API_CAT t2 on t.CAT_CODE = t2.CODE AND t2.DEL_FLAG = '0'")
                .between(reqVO.getParamByKey("beginCreateTime") != null && reqVO.getParamByKey("endCreateTime") != null,
                        DsApiDO::getCreateTime,
                        reqVO.getParamByKey("beginCreateTime"),
                        reqVO.getParamByKey("endCreateTime"))
                .like(StringUtils.isNotBlank(reqVO.getName()),DsApiDO::getName, reqVO.getName())
                .in(reqVO.getApiIdList() != null && !reqVO.getApiIdList().isEmpty(),DsApiDO::getId,reqVO.getApiIdList())
                .in(reqVO.getCatIds() != null && !reqVO.getCatIds().isEmpty(),DsApiDO::getCatId,reqVO.getCatIds())
                .likeRight(StringUtils.isNotBlank(reqVO.getCatCode()), DsApiDO::getCatCode, reqVO.getCatCode())
                .eq(StringUtils.isNotBlank(reqVO.getStatus()),DsApiDO::getStatus, reqVO.getStatus())
                .orderByStr(StringUtils.isNotBlank(reqVO.getOrderByColumn()), StringUtils.equals("asc", reqVO.getIsAsc()), StringUtils.isNotBlank(reqVO.getOrderByColumn()) ? Arrays.stream(sort.split(",")).map(String::trim).toList() : null);
        if(reqVO.getAccessibleApiIds()!=null){if(reqVO.getAccessibleApiIds().isEmpty())lambdaWrapper.apply("1=0");else lambdaWrapper.in(DsApiDO::getId,reqVO.getAccessibleApiIds());}
        return selectJoinPage(reqVO, DsApiDO.class, lambdaWrapper);
    }
}
