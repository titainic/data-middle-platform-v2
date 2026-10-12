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

package tech.qiantong.qdata.module.da.service.assetchild.files.impl;

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
import tech.qiantong.qdata.module.da.controller.admin.assetchild.files.vo.DaAssetFilesPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.files.vo.DaAssetFilesRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.files.vo.DaAssetFilesSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.files.DaAssetFilesDO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.files.DaAssetFilesMapper;
import tech.qiantong.qdata.module.da.service.assetchild.files.IDaAssetFilesService;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data Asset - Files Service business layer processing
 *
 * @author qdata
 * @date 2025-06-26
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaAssetFilesServiceImpl  extends ServiceImpl<DaAssetFilesMapper,DaAssetFilesDO> implements IDaAssetFilesService {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildAccess children;
    @Resource private tech.qiantong.qdata.module.da.service.asset.FileAssetLifecycle lifecycle;
    @Resource
    private DaAssetFilesMapper daAssetFilesMapper;

    @Override
    public PageResult<DaAssetFilesDO> getDaAssetFilesPage(DaAssetFilesPageReqVO pageReqVO) {
        return daAssetFilesMapper.selectPage(pageReqVO);
    }

    @Override
    public Long createDaAssetFiles(DaAssetFilesSaveReqVO createReqVO) {
        var prepared=lifecycle.prepare(createReqVO,false);
        DaAssetFilesDO dictType = BeanUtils.toBean(createReqVO, DaAssetFilesDO.class);
        dictType.setCreatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());dictType.setCreateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());dictType.setCreateTime(new java.util.Date());
        dictType.setUpdatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());dictType.setUpdateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());dictType.setUpdateTime(new java.util.Date());
        daAssetFilesMapper.insert(dictType);
        lifecycle.synchronize(prepared);
        return dictType.getId();
    }

    @Override
    public int updateDaAssetFiles(DaAssetFilesSaveReqVO updateReqVO) {
        var prepared=lifecycle.prepare(updateReqVO,true);
        // Update data asset - files service
        DaAssetFilesDO updateObj = BeanUtils.toBean(updateReqVO, DaAssetFilesDO.class);
        updateObj.setCreatorId(null);updateObj.setCreateBy(null);updateObj.setCreateTime(null);
        updateObj.setValidFlag(null);updateObj.setDelFlag(null);updateObj.setUpdatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());updateObj.setUpdateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());updateObj.setUpdateTime(new java.util.Date());
        int count=daAssetFilesMapper.updateById(updateObj);
        if(count!=1)throw new IllegalStateException("文件绑定更新失败");
        lifecycle.synchronize(prepared);
        return count;
    }
    @Override
    public int removeDaAssetFiles(Collection<Long> idList) {
        // Batch delete data asset - files service
        return daAssetFilesMapper.deleteBatchIds(idList);
    }

    @Override
    public DaAssetFilesDO getDaAssetFilesById(Long id) {
        return daAssetFilesMapper.selectById(id);
    }

    @Override
    public List<DaAssetFilesDO> getDaAssetFilesList() {
        return daAssetFilesMapper.selectList();
    }

    @Override
    public Map<Long, DaAssetFilesDO> getDaAssetFilesMap() {
        List<DaAssetFilesDO> daAssetFilesList = daAssetFilesMapper.selectList();
        return daAssetFilesList.stream()
                .collect(Collectors.toMap(
                        DaAssetFilesDO::getId,
                        daAssetFilesDO -> daAssetFilesDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }


        /**
         * Import data asset - files service data
         *
         * @param importExcelList Data asset - files service data list
         * @param isUpdateSupport Whether to support update, if already exists, update the data
         * @param operName Operator user
         * @return Result
         */
        @Override
        public String importDaAssetFiles(List<DaAssetFilesRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        var requests=children.imports(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.FILES,importExcelList,isUpdateSupport,DaAssetFilesSaveReqVO.class);
        for(var request:requests){if(isUpdateSupport)updateDaAssetFiles(request);else createDaAssetFiles(request);}
        return "成功导入 "+requests.size()+" 条子资源记录";
    }
}
