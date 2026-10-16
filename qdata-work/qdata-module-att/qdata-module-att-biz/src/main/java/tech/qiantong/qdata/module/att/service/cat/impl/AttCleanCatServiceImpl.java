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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.core.text.Convert;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.YouBianCodeUtil;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttCleanCatPageReqVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttCleanCatRespVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttCleanCatSaveReqVO;
import tech.qiantong.qdata.module.att.dal.dataobject.cat.AttCleanCatDO;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttCleanCatMapper;
import tech.qiantong.qdata.module.att.service.cat.IAttCleanCatService;
import tech.qiantong.qdata.module.att.service.rule.IAttCleanRuleService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;

/**
 * Cleaning Rule Category - Service business layer processing
 *
 * @author qdata
 * @date 2025-08-11
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class AttCleanCatServiceImpl  extends ServiceImpl<AttCleanCatMapper,AttCleanCatDO> implements IAttCleanCatService {
    @Resource
    private AttCleanCatMapper attCleanCatMapper;
    @Resource
    private IAttCleanRuleService attCleanRuleService;

    @Override
    public PageResult<AttCleanCatDO> getAttCleanCatPage(AttCleanCatPageReqVO pageReqVO) {
        return attCleanCatMapper.selectPage(pageReqVO);
    }

    @Override
    public Long createAttCleanCat(AttCleanCatSaveReqVO createReqVO) {
        AttCleanCatDO dictType = BeanUtils.toBean(createReqVO, AttCleanCatDO.class);
        dictType.setCode(createCode(createReqVO.getParentId(), null));
        attCleanCatMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    public int updateAttCleanCat(AttCleanCatSaveReqVO updateReqVO) {
        AttCleanCatDO catDO = attCleanCatMapper.selectById(updateReqVO.getId());
        if (catDO == null) {
            return 0;
        }
        if (Boolean.FALSE.equals(updateReqVO.getValidFlag())) {
            Long countData = attCleanRuleService.getCount(catDO.getCode());
            if (countData > 0) {
                throw new ServiceException("att.error.clean.disable", "Clean rule model exists, disable not allowed");
            }
            attCleanCatMapper.updateValidFlag(catDO.getCode(), updateReqVO.getValidFlag());
        } else if (Boolean.TRUE.equals(updateReqVO.getValidFlag())) {
            AttCleanCatDO parent = attCleanCatMapper.selectById(catDO.getParentId());
            if (parent != null && Boolean.FALSE.equals(parent.getValidFlag())) {
                throw new ServiceException("att.error.parent.disabled", "Please enable the parent category first");
            }
        }        // Update Cleaning Rule Category
        AttCleanCatDO updateObj = BeanUtils.toBean(updateReqVO, AttCleanCatDO.class);
        return attCleanCatMapper.updateById(updateObj);
    }
    @Override
    public int removeAttCleanCat(Long idList) {
        // Batch delete Cleaning Rule Category
        int count = 0;
        AttCleanCatDO cat = baseMapper.selectById(idList);

        // Check if data exists
        if (attCleanRuleService.getCount(cat.getCode()) > 0) {
            throw new ServiceException("att.error.clean.delete", "Cleaning rule models exist; deletion is not allowed.");
        }

        if (cat != null) {
            count += baseMapper.delete(Wrappers.lambdaQuery(AttCleanCatDO.class)
                    .likeRight(AttCleanCatDO::getCode, cat.getCode()));
        }
        return count;
    }

    @Override
    public AttCleanCatDO getAttCleanCatById(Long id) {
        return attCleanCatMapper.selectById(id);
    }

    @Override
    public List<AttCleanCatDO> getAttCleanCatList(AttCleanCatPageReqVO attCleanCat) {
        LambdaQueryWrapperX<AttCleanCatDO> queryWrapperX = new LambdaQueryWrapperX<>();
        queryWrapperX.likeIfPresent(AttCleanCatDO::getName, attCleanCat.getName())
                .likeRightIfPresent(AttCleanCatDO::getCode, attCleanCat.getCode())
                .eqIfPresent(AttCleanCatDO::getValidFlag, attCleanCat.getValidFlag())
                .orderByAsc(AttCleanCatDO::getSortOrder);
        return attCleanCatMapper.selectList(queryWrapperX);
    }

    @Override
    public List<AttCleanCatDO> getAttCleanCatList() {
        return attCleanCatMapper.selectList();
    }

    @Override
    public Map<Long, AttCleanCatDO> getAttCleanCatMap() {
        List<AttCleanCatDO> attCleanCatList = attCleanCatMapper.selectList();
        return attCleanCatList.stream()
                .collect(Collectors.toMap(
                        AttCleanCatDO::getId,
                        attCleanCatDO -> attCleanCatDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }


    /**
     * Import Cleaning Rule Category data
     *
     *  importExcelList Cleaning Rule Category data list
     * @param isUpdateSupport Whether to support update; if already exists, update the data
     *  operName Operator
     *  Result
     */
    @Override
    public String importAttCleanCat(List<AttCleanCatRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        if (StringUtils.isNull(importExcelList) || importExcelList.size() == 0) {
            throw new ServiceException("att.error.import.empty", "Import data cannot be empty!");
        }

        int successNum = 0;
        int failureNum = 0;
        List<String> successMessages = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();

        for (AttCleanCatRespVO respVO : importExcelList) {
            try {
                AttCleanCatDO attCleanCatDO = BeanUtils.toBean(respVO, AttCleanCatDO.class);
                Long attCleanCatId = respVO.getId();
                if (isUpdateSupport) {
                    if (attCleanCatId != null) {
                        AttCleanCatDO existingAttCleanCat = attCleanCatMapper.selectById(attCleanCatId);
                        if (existingAttCleanCat != null) {
                            attCleanCatMapper.updateById(attCleanCatDO);
                            successNum++;
                            successMessages.add(MessageUtils.messageWithFallback("att.import.update.success",
                                    "Data update successful, ID {0} {1} record.", attCleanCatId, MessageUtils.messageWithFallback("att.entity.cleansing.rule.category", "Cleansing rule category")));
                        } else {
                            failureNum++;
                            failureMessages.add(MessageUtils.messageWithFallback("att.import.update.fail",
                                    "Data update failed, ID {0} {1} record does not exist.", attCleanCatId, MessageUtils.messageWithFallback("att.entity.cleansing.rule.category", "Cleansing rule category")));
                        }
                    } else {
                        failureNum++;
                        failureMessages.add(MessageUtils.messageWithFallback("att.import.update.id.missing",
                                "Data update failed, record ID does not exist."));
                    }
                } else {
                    QueryWrapper<AttCleanCatDO> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("id", attCleanCatId);
                    AttCleanCatDO existingAttCleanCat = attCleanCatMapper.selectOne(queryWrapper);
                    if (existingAttCleanCat == null) {
                        attCleanCatMapper.insert(attCleanCatDO);
                        successNum++;
                        successMessages.add(MessageUtils.messageWithFallback("att.import.insert.success",
                                "Data insert successful, ID {0} {1} record.", attCleanCatId, MessageUtils.messageWithFallback("att.entity.cleansing.rule.category", "Cleansing rule category")));
                    } else {
                        failureNum++;
                        failureMessages.add(MessageUtils.messageWithFallback("att.import.insert.fail",
                                "Data insert failed, ID {0} {1} record already exists.", attCleanCatId, MessageUtils.messageWithFallback("att.entity.cleansing.rule.category", "Cleansing rule category")));
                    }
                }
            } catch (Exception e) {
                failureNum++;
                String errorMsg = MessageUtils.messageWithFallback("att.import.error.detail",
                "Data import failed, error: {0}", e.getMessage());
                failureMessages.add(errorMsg);
                log.error(errorMsg, e);
            }
        }
        StringBuilder resultMsg = new StringBuilder();
        if (failureNum > 0) {
            String failureDetails = String.join("<br/>", failureMessages);
            resultMsg.append(MessageUtils.messageWithFallback("att.import.result.fail",
                    "Import failed! {0} records have incorrect format, errors:<br/>{1}",
                    failureNum, failureDetails));
            throw new ServiceException("att.error.import.fail", resultMsg.toString(), resultMsg.toString());
        } else {
            resultMsg.append(MessageUtils.messageWithFallback("att.import.result.success",
                    "Congratulations! All data imported successfully! Total: {0} records.", successNum));
        }
        return resultMsg.toString();
    }


    @Override
    public String createCode(Long parentId, String parentCode) {
        if (parentId == null || parentId < 0) {
            throw new ServiceException("清洗类目父节点无效，请刷新类目树后重试");
        }
        String prefix = "";
        if (parentId != 0) {
            AttCleanCatDO parent = attCleanCatMapper.selectById(parentId);
            if (parent == null || !Boolean.TRUE.equals(parent.getValidFlag())) {
                throw new ServiceException("清洗类目父节点不存在或未启用，请刷新类目树后重试");
            }
            prefix = parent.getCode();
            if (prefix == null || !prefix.matches("(?:[A-Z](?:0[1-9]|[1-9][0-9]))+")) {
                throw new ServiceException("清洗类目父节点编码异常，请联系管理员核对目录");
            }
        }
        if (StringUtils.isNotBlank(parentCode) && !parentCode.equals(prefix)) {
            throw new ServiceException("清洗类目父节点编码不匹配，请刷新类目树后重试");
        }
        int length = prefix.length() + YouBianCodeUtil.ZHANWEI_LENGTH;
        if (length > 128) {
            throw new ServiceException("清洗类目编码长度已达上限，无法分配隔离编码");
        }
        Set<String> occupied = new HashSet<>(attCleanCatMapper.selectOccupiedCodePrefixes(prefix, length));
        String segment = YouBianCodeUtil.getNextYouBianCode(null);
        // Search only this level's A01..Z99; the common utility adds a level after Z99.
        while (true) {
            String candidate = prefix + segment;
            if (!occupied.contains(candidate)) {
                return candidate;
            }
            if ("Z99".equals(segment)) {
                throw new ServiceException("清洗类目本层编码已全部占用，无法分配不关联既有规则的隔离编码，请联系管理员");
            }
            segment = YouBianCodeUtil.getNextYouBianCode(segment);
        }
    }
}
