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

package tech.qiantong.qdata.module.da.service.assetchild.projectRel.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.projectRel.vo.DaAssetProjectRelPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.projectRel.vo.DaAssetProjectRelRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.projectRel.vo.DaAssetProjectRelSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.projectRel.DaAssetProjectRelDO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.projectRel.DaAssetProjectRelMapper;
import tech.qiantong.qdata.module.da.service.assetchild.projectRel.IDaAssetProjectRelService;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
/**
 * Data asset and project relationship Service business layer processing
 *
 * @author qdata
 * @date 2025-04-18
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaAssetProjectRelServiceImpl  extends ServiceImpl<DaAssetProjectRelMapper,DaAssetProjectRelDO> implements IDaAssetProjectRelService {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetProjectAssignmentAccess assignments;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetProjectRelationIntegrity integrity;
    @Resource
    private DaAssetProjectRelMapper daAssetProjectRelMapper;

    @Override
    public PageResult<DaAssetProjectRelDO> getDaAssetProjectRelPage(DaAssetProjectRelPageReqVO pageReqVO) {
        return daAssetProjectRelMapper.selectPage(pageReqVO);
    }

    @Override
    public List<DaAssetProjectRelDO> getDaAssetProjectRelList(DaAssetProjectRelPageReqVO pageReqVO) {
        return null;
    }

    @Override
    public Long createDaAssetProjectRel(DaAssetProjectRelSaveReqVO createReqVO) {
        Long existing=integrity.existingOrValidate(createReqVO);if(existing!=null)return existing;
        DaAssetProjectRelDO dictType = BeanUtils.toBean(createReqVO, DaAssetProjectRelDO.class);
        dictType.setUpdatorId(createReqVO.getUpdatorId());
        daAssetProjectRelMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    public int removeProjectRelByAssetId(Long assetId) {
        daAssetProjectRelMapper.removeProjectRelByAssetId(assetId);
        return 1;
    }

    @Override
    public int updateDaAssetProjectRel(DaAssetProjectRelSaveReqVO updateReqVO) {
        // Validation checks

        // Update data asset and project relationship
        DaAssetProjectRelDO updateObj = BeanUtils.toBean(updateReqVO, DaAssetProjectRelDO.class);
        updateObj.setCreatorId(null);updateObj.setCreateBy(null);updateObj.setCreateTime(null);updateObj.setValidFlag(null);updateObj.setDelFlag(null);
        updateObj.setUpdatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());
        updateObj.setUpdateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());updateObj.setUpdateTime(new java.util.Date());
        return daAssetProjectRelMapper.updateById(updateObj);
    }
    @Override
    public int removeDaAssetProjectRel(Collection<Long> idList) {
        integrity.remove(idList);
        // Batch delete data asset and project relationship
        return integrity.archive(idList);
    }

    @Override
    public DaAssetProjectRelDO getDaAssetProjectRelById(Long id) {
        return daAssetProjectRelMapper.selectById(id);
    }

    @Override
    public List<DaAssetProjectRelDO> getDaAssetProjectRelList() {
        return daAssetProjectRelMapper.selectList();
    }

    @Override
    public Map<Long, DaAssetProjectRelDO> getDaAssetProjectRelMap() {
        List<DaAssetProjectRelDO> daAssetProjectRelList = daAssetProjectRelMapper.selectList();
        return daAssetProjectRelList.stream()
                .collect(Collectors.toMap(
                        DaAssetProjectRelDO::getId,
                        daAssetProjectRelDO -> daAssetProjectRelDO,
                        // Retain existing values
                        (existing, replacement) -> existing
                ));
    }


    /**
     * Import data asset and project relationship data
     *
     * @param importExcelList Data asset and project relationship data list
     * @param isUpdateSupport Whether to support update, if already exists, update the data
     * @param operName Operator user
     * @return Result
     */
    @Override
    public String importDaAssetProjectRel(List<DaAssetProjectRelRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        var requests=assignments.imports(importExcelList,isUpdateSupport);
        for(var request:requests){if(isUpdateSupport)updateDaAssetProjectRel(request);else createDaAssetProjectRel(request);}
        return "成功导入 "+requests.size()+" 条项目关系";
    }
}
