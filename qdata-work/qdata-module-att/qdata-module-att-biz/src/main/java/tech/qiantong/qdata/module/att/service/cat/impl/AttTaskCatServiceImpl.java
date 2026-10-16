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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.YouBianCodeUtil;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.att.api.cat.dto.AttTaskCatReqDTO;
import tech.qiantong.qdata.module.att.api.cat.dto.AttTaskCatRespDTO;
import tech.qiantong.qdata.module.att.api.service.cat.IAttTaskCatApiService;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatPageReqVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatRespVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatSaveReqVO;
import tech.qiantong.qdata.module.att.dal.dataobject.cat.AttTaskCatDO;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttTaskCatMapper;
import tech.qiantong.qdata.module.att.service.cat.IAttTaskCatService;
import tech.qiantong.qdata.module.dpp.api.service.etl.DppEtlTaskService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data Integration Task Category Management Service business layer processing
 *
 * @author qdata
 * @date 2025-03-11
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class AttTaskCatServiceImpl extends ServiceImpl<AttTaskCatMapper, AttTaskCatDO> implements IAttTaskCatService, IAttTaskCatApiService {
    @Resource
    private tech.qiantong.qdata.module.att.service.project.ProjectCatalogAccess catalogs;
    @Resource
    private AttTaskCatMapper attTaskCatMapper;
    @Resource
    private DppEtlTaskService dppEtlTaskService;

    @Override
    public PageResult<AttTaskCatDO> getAttTaskCatPage(AttTaskCatPageReqVO pageReqVO) {
        return attTaskCatMapper.selectPage(pageReqVO);
    }

    @Override
    public Long createAttTaskCat(AttTaskCatSaveReqVO createReqVO) {
        normalizeAndValidate(createReqVO);
        if (createReqVO.getParentId() == null) createReqVO.setParentId(0L);
        // Acquire the project lock before name/code reads; hold it through insert/commit.
        String code = createCode(createReqVO.getProjectId(), createReqVO.getParentId(), null);
        checkDuplicate(createReqVO.getId(), createReqVO.getParentId(), createReqVO.getName(),createReqVO.getProjectId());
        AttTaskCatDO dictType = BeanUtils.toBean(createReqVO, AttTaskCatDO.class);
        dictType.setCode(code);
        attTaskCatMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    public List<AttTaskCatRespDTO> getAttTaskCatApiList(AttTaskCatReqDTO reqVO) {
        var allowed=catalogs.projects(tech.qiantong.qdata.module.att.service.project.ProjectCatalogAccess.Kind.INTEGRATION,"read",reqVO.getProjectId(),reqVO.getProjectCode(),true);
        MPJLambdaWrapper<AttTaskCatDO> wrapper = new MPJLambdaWrapper<>();
        wrapper.selectAll(AttTaskCatDO.class)
                .in(AttTaskCatDO::getProjectId,allowed.isEmpty()?java.util.List.of(-1L):allowed)
                .like(StringUtils.isNotBlank(reqVO.getName()), AttTaskCatDO::getName, reqVO.getName());
        List<AttTaskCatDO> attTaskCatDOS = attTaskCatMapper.selectList(wrapper);
        return BeanUtils.toBean(attTaskCatDOS, AttTaskCatRespDTO.class);
    }

    @Override
    public int updateAttTaskCat(AttTaskCatSaveReqVO updateReqVO) {
        normalizeAndValidate(updateReqVO);
        AttTaskCatDO existing = attTaskCatMapper.selectById(updateReqVO.getId());
        if (existing == null) {
            throw new ServiceException("att.error.cat.notfound", "类目不存在");
        }
        checkDuplicate(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getName(),updateReqVO.getProjectId());
        checkParentCycle(existing, updateReqVO.getParentId());

        // Update Data Integration Task Category Management
        AttTaskCatDO updateObj = BeanUtils.toBean(updateReqVO, AttTaskCatDO.class);
        int rows = attTaskCatMapper.updateById(updateObj);
        if (Boolean.FALSE.equals(updateReqVO.getValidFlag())) {
            this.lambdaUpdate().eq(AttTaskCatDO::getProjectId,existing.getProjectId()).likeRight(AttTaskCatDO::getCode, existing.getCode())
                    .set(AttTaskCatDO::getValidFlag, false).update();
        }
        return rows;
    }

    @Override
    public boolean hasIntegrationTask(Long id) {
        AttTaskCatDO category = attTaskCatMapper.selectById(id);
        if (category == null) {
            throw new ServiceException("att.error.cat.notfound", "类目不存在");
        }
        return dppEtlTaskService.getCountByCatCode(
                category.getCode(), java.util.Collections.singletonList("1")) > 0;
    }

    @Override
    public boolean isNameUsed(Long id, Long parentId, String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        return this.lambdaQuery()
                .eq(AttTaskCatDO::getParentId, parentId)
                .eq(AttTaskCatDO::getName, name.trim())
                .ne(id != null, AttTaskCatDO::getId, id)
                .count() > 0;
    }

    @Override
    public long getIntegrationTaskCount(Long id) {
        if (attTaskCatMapper.selectById(id) == null) {
            throw new ServiceException("att.error.cat.notfound", "类目不存在");
        }
        return dppEtlTaskService.getCountByCatId(
                id, java.util.Collections.singletonList("1"));
    }

    @Override
    public int removeAttTaskCat(Collection<Long> idList) {
        for (AttTaskCatDO category : attTaskCatMapper.selectBatchIds(idList)) {
            long childCount = this.lambdaQuery().likeRight(AttTaskCatDO::getCode, category.getCode())
                    .ne(AttTaskCatDO::getId, category.getId()).count();
            long taskCount = dppEtlTaskService.getCountByCatCode(category.getCode(), java.util.Collections.singletonList("1"));
            if (childCount > 0 || taskCount > 0) {
                throw new ServiceException("att.error.cat.delete.children.tasks",
                        "该类目包含" + childCount + "个子类目和" + taskCount + "个任务，不能直接删除。", childCount, taskCount);
            }
        }
        return attTaskCatMapper.deleteBatchIds(idList);
    }

    private void normalizeAndValidate(AttTaskCatSaveReqVO reqVO) {
        reqVO.setName(reqVO.getName() == null ? null : reqVO.getName().trim());
        if (StringUtils.isBlank(reqVO.getName()) || reqVO.getName().matches(".*\\s+.*")
                || !reqVO.getName().matches(".*[A-Za-z0-9\\u4e00-\\u9fa5].*")) {
            throw new ServiceException("att.error.cat.name.invalid", "类目名称不能为空、不能包含空白字符或仅由符号组成");
        }
        if (reqVO.getSortOrder() != null && reqVO.getSortOrder() < 0) {
            throw new ServiceException("att.error.cat.sort.invalid", "排序值不合法，请输入非负整数");
        }
    }

    private void checkDuplicate(Long id, Long parentId, String name,Long projectId) {
        // Current read after the project lock, also under MySQL REPEATABLE READ.
        var duplicates = attTaskCatMapper.selectList(new LambdaQueryWrapper<AttTaskCatDO>()
                .select(AttTaskCatDO::getId).eq(AttTaskCatDO::getProjectId,projectId)
                .eq(AttTaskCatDO::getParentId, parentId).eq(AttTaskCatDO::getName, name)
                .ne(id != null, AttTaskCatDO::getId, id).last("FOR UPDATE"));
        if (!duplicates.isEmpty()) {
            throw new ServiceException("att.error.cat.name.duplicate", "当前上级类目下已存在同名类目，请修改。");
        }
    }

    private void checkParentCycle(AttTaskCatDO current, Long parentId) {
        if (parentId == null || parentId == 0) return;
        if (parentId.equals(current.getId())) {
            throw new ServiceException("att.error.cat.parent.cycle", "上级类目不能选择自身或其子级。");
        }
        AttTaskCatDO parent = attTaskCatMapper.selectById(parentId);
        if (parent == null || (parent.getCode() != null && parent.getCode().startsWith(current.getCode()))) {
            throw new ServiceException("att.error.cat.parent.cycle", "上级类目不能选择自身或其子级。");
        }
    }

    @Override
    public AttTaskCatDO getAttTaskCatById(Long id) {
        return attTaskCatMapper.selectById(id);
    }

    @Override
    public List<AttTaskCatDO> getAttTaskCatList() {
        return attTaskCatMapper.selectList();
    }

    @Override
    public List<AttTaskCatDO> getAttTaskCatList(AttTaskCatPageReqVO reqVO) {
        LambdaQueryWrapperX<AttTaskCatDO> queryWrapperX = new LambdaQueryWrapperX<>();
        queryWrapperX.in(AttTaskCatDO::getProjectId,reqVO.getAccessibleProjectIds()==null || reqVO.getAccessibleProjectIds().isEmpty()?java.util.List.of(-1L):reqVO.getAccessibleProjectIds())
                .likeIfPresent(AttTaskCatDO::getName, reqVO.getName())
                .eqIfPresent(AttTaskCatDO::getParentId, reqVO.getParentId())
                .eqIfPresent(AttTaskCatDO::getSortOrder, reqVO.getSortOrder())
                .eqIfPresent(AttTaskCatDO::getDescription, reqVO.getDescription())
                .eqIfPresent(AttTaskCatDO::getCode, reqVO.getCode())
                .eqIfPresent(AttTaskCatDO::getCreateTime, reqVO.getCreateTime())
                .eqIfPresent(AttTaskCatDO::getProjectId,reqVO.getProjectId())
                .eqIfPresent(AttTaskCatDO::getProjectCode,reqVO.getProjectCode())
                .eqIfPresent(AttTaskCatDO::getValidFlag,reqVO.getValidFlag())
                .orderByAsc(AttTaskCatDO::getSortOrder);
        return attTaskCatMapper.selectList(queryWrapperX);
    }

    @Override
    public Map<Long, AttTaskCatDO> getAttTaskCatMap() {
        List<AttTaskCatDO> attTaskCatList = attTaskCatMapper.selectList();
        return attTaskCatList.stream()
                .collect(Collectors.toMap(
                        AttTaskCatDO::getId,
                        attTaskCatDO -> attTaskCatDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }

    @Override
    public String createCode(Long projectId, Long parentId, String parentCode) {
        if (projectId == null || projectId <= 0 || parentId == null || parentId < 0) {
            throw new ServiceException("请明确类目所属项目和有效上级类目");
        }
        if (attTaskCatMapper.lockProjectForCode(projectId) == null) {
            throw new ServiceException("类目所属项目不存在，请刷新后重试");
        }
        String prefix = "";
        if (parentId != 0) {
            AttTaskCatDO parent = attTaskCatMapper.selectOne(new LambdaQueryWrapper<AttTaskCatDO>()
                    .eq(AttTaskCatDO::getId, parentId).eq(AttTaskCatDO::getProjectId, projectId)
                    .last("FOR UPDATE"));
            if (parent == null || !Boolean.TRUE.equals(parent.getValidFlag())) {
                throw new ServiceException("上级类目不存在、未启用或不属于当前项目");
            }
            prefix = parent.getCode();
            if (StringUtils.isBlank(prefix)) {
                throw new ServiceException("上级类目编码为空，无法生成子类目编码，请联系管理员核对");
            }
        }
        if (StringUtils.isNotBlank(parentCode) && !parentCode.equals(prefix)) {
            throw new ServiceException("上级类目编码不匹配，请刷新后重试");
        }
        int length = prefix.length() + YouBianCodeUtil.ZHANWEI_LENGTH;
        if (length > 128) {
            throw new ServiceException("类目编码长度已达上限，无法新增子类目");
        }
        var occupied = new HashSet<>(attTaskCatMapper.selectOccupiedCodePrefixes(projectId, prefix, length));
        // Only pass generated A01..Z99 segments to the common numeric incrementer.
        // Historical parent codes (e.g. bin11cat) remain opaque and unchanged.
        String segment = YouBianCodeUtil.getNextYouBianCode(null);
        while (true) {
            String candidate = prefix + segment;
            if (!occupied.contains(candidate.toUpperCase(Locale.ROOT))) return candidate;
            if ("Z99".equals(segment)) {
                throw new ServiceException("当前上级类目编码已用尽，无法新增类目");
            }
            segment = YouBianCodeUtil.getNextYouBianCode(segment);
        }
    }

    /**
     * Import Data Integration Task Category Management data
     *
     * @param importExcelList Data Integration Task Category Management data list
     * @param isUpdateSupport Whether to support update; if already exists, update the data
     * @param operName Operator
     * @return Import result
     */
    @Override
    public String importAttTaskCat(List<AttTaskCatRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        if (StringUtils.isNull(importExcelList) || importExcelList.size() == 0) {
            throw new ServiceException("att.error.import.empty", "导入数据不能为空！");
        }

        int successNum = 0;
        int failureNum = 0;
        List<String> successMessages = new ArrayList<>();
        List<String> failureMessages = new ArrayList<>();

        for (AttTaskCatRespVO respVO : importExcelList) {
            try {
                AttTaskCatDO attTaskCatDO = BeanUtils.toBean(respVO, AttTaskCatDO.class);
                Long attTaskCatId = respVO.getId();
                if (isUpdateSupport) {
                    if (attTaskCatId != null) {
                        AttTaskCatDO existingAttTaskCat = attTaskCatMapper.selectById(attTaskCatId);
                        if (existingAttTaskCat != null) {
                            attTaskCatMapper.updateById(attTaskCatDO);
                            successNum++;
                            successMessages.add(MessageUtils.messageWithFallback("att.import.update.success",
                                    "数据Update 成功，ID为 " + attTaskCatId + " 的数据集成任务类目管理记录。", attTaskCatId, "数据集成任务类目管理"));
                        } else {
                            failureNum++;
                            failureMessages.add(MessageUtils.messageWithFallback("att.import.update.fail",
                                    "数据Update 失败，ID为 " + attTaskCatId + " 的数据集成任务类目管理记录不存在。", attTaskCatId, "数据集成任务类目管理"));
                        }
                    } else {
                        failureNum++;
                        failureMessages.add(MessageUtils.messageWithFallback("att.import.update.id.missing",
                                "数据Update 失败，某条记录的ID不存在。"));
                    }
                } else {
                    QueryWrapper<AttTaskCatDO> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("id", attTaskCatId);
                    AttTaskCatDO existingAttTaskCat = attTaskCatMapper.selectOne(queryWrapper);
                    if (existingAttTaskCat == null) {
                        attTaskCatMapper.insert(attTaskCatDO);
                        successNum++;
                        successMessages.add(MessageUtils.messageWithFallback("att.import.insert.success",
                                "数据插入成功，ID为 " + attTaskCatId + " 的数据集成任务类目管理记录。", attTaskCatId, "数据集成任务类目管理"));
                    } else {
                        failureNum++;
                        failureMessages.add(MessageUtils.messageWithFallback("att.import.insert.fail",
                                "数据插入失败，ID为 " + attTaskCatId + " 的数据集成任务类目管理记录已存在。", attTaskCatId, "数据集成任务类目管理"));
                    }
                }
            } catch (Exception e) {
                failureNum++;
                String errorMsg = MessageUtils.messageWithFallback("att.import.error.detail",
                "数据导入失败，错误信息：" + e.getMessage(), e.getMessage());
                failureMessages.add(errorMsg);
                log.error(errorMsg, e);
            }
        }
        StringBuilder resultMsg = new StringBuilder();
        if (failureNum > 0) {
            String failureDetails = String.join("<br/>", failureMessages);
            resultMsg.append(MessageUtils.messageWithFallback("att.import.result.fail",
                    "很抱歉，导入失败！共 " + failureNum + " 条数据格式不正确，错误如下：<br/>" + failureDetails,
                    failureNum, failureDetails));
            throw new ServiceException("att.error.import.fail", resultMsg.toString(), resultMsg.toString());
        } else {
            resultMsg.append(MessageUtils.messageWithFallback("att.import.result.success",
                    "恭喜您，数据已全部导入成功！共 " + successNum + " 条。", successNum));
        }
        return resultMsg.toString();
    }
}
