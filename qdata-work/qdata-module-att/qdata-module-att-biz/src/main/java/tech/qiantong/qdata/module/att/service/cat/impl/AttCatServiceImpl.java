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

package tech.qiantong.qdata.module.att.service.cat.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.module.att.api.cat.IAttCatService;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttCatMapper;

import jakarta.annotation.Resource;

/**
 * <P>
 * Purpose:
 * </p>
 *
 * @author: FXB
 * @create: 2025-03-11 16:53
 **/
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class AttCatServiceImpl implements IAttCatService {

    @Resource
    private AttCatMapper attCatMapper;

    @Override
    public Long getCatIdByTableNameAndCatCode(String tableName, String catCode, Long projectId) {
        // Table identifiers are internal enum values, never client-provided SQL.
        var table = tech.qiantong.qdata.common.enums.TaskCatEnum.valueOf(tableName);
        if ((table == tech.qiantong.qdata.common.enums.TaskCatEnum.ATT_TASK_CAT
                || table == tech.qiantong.qdata.common.enums.TaskCatEnum.ATT_DATA_DEV_CAT)
                && (projectId == null || projectId <= 0)) {
            throw new tech.qiantong.qdata.common.exception.ServiceException("请明确类目所属项目");
        }
        return attCatMapper.getCatIdByTableNameAndCatCode(table.name(), catCode, projectId);
    }
}
