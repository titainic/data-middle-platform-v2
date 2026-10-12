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

package tech.qiantong.qdata.module.da.service.assetchild.geo.impl;

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
import tech.qiantong.qdata.module.da.controller.admin.assetchild.geo.vo.DaAssetGeoPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.geo.vo.DaAssetGeoRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.geo.vo.DaAssetGeoSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.geo.DaAssetGeoDO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.geo.DaAssetGeoMapper;
import tech.qiantong.qdata.module.da.service.assetchild.geo.IDaAssetGeoService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data Asset - Vector Service business layer processing
 *
 * @author qdata
 * @date 2025-04-14
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaAssetGeoServiceImpl  extends ServiceImpl<DaAssetGeoMapper,DaAssetGeoDO> implements IDaAssetGeoService {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildAccess children;
    @Resource
    private DaAssetGeoMapper daAssetGeoMapper;
    @Resource private tech.qiantong.qdata.common.file.UploadedFileAccess uploadedFiles;

    @Override
    public PageResult<DaAssetGeoDO> getDaAssetGeoPage(DaAssetGeoPageReqVO pageReqVO) {
        return daAssetGeoMapper.selectPage(pageReqVO);
    }

    @Override
    public DaAssetGeoRespVO getDaAssetGeoByAssetId(Long assetId) {
        LambdaQueryWrapperX<DaAssetGeoDO> queryWrapperX = new LambdaQueryWrapperX<>();
        queryWrapperX.eqIfPresent(DaAssetGeoDO::getAssetId,assetId);
        DaAssetGeoDO daAssetApiDO = daAssetGeoMapper.selectOne(queryWrapperX);
        return BeanUtils.toBean(daAssetApiDO, DaAssetGeoRespVO.class);
    }

    @Override
    public Long createDaAssetGeo(DaAssetGeoSaveReqVO createReqVO) {
        boolean local=createReqVO.getFileUrl()!=null && !createReqVO.getFileUrl().contains("://");
        if(local)uploadedFiles.bind(createReqVO.getFileUrl(),createReqVO.getAssetId());
        DaAssetGeoDO dictType = BeanUtils.toBean(createReqVO, DaAssetGeoDO.class);
        daAssetGeoMapper.insert(dictType);
        if(local)uploadedFiles.indexAsset("GEO",dictType.getId(),dictType.getAssetId(),dictType.getFileUrl());
        return dictType.getId();
    }

    @Override
    public int updateDaAssetGeo(DaAssetGeoSaveReqVO updateReqVO) {
        // Related validation

        // Update data asset - vector
        DaAssetGeoDO updateObj = BeanUtils.toBean(updateReqVO, DaAssetGeoDO.class);
        updateObj.setCreatorId(null);updateObj.setCreateBy(null);updateObj.setCreateTime(null);
        var stored=daAssetGeoMapper.selectById(updateReqVO.getId());
        if(stored==null)throw new IllegalArgumentException("向量资源不存在");
        if(updateReqVO.getAssetId()!=null && !java.util.Objects.equals(updateReqVO.getAssetId(),stored.getAssetId()))throw new org.springframework.security.access.AccessDeniedException("子资源绑定不可变更");
        String resource=updateReqVO.getFileUrl()!=null?updateReqVO.getFileUrl():stored.getFileUrl();
        boolean local=resource!=null && !resource.contains("://");
        if(local)uploadedFiles.bind(resource,stored.getAssetId());
        int changed=daAssetGeoMapper.updateById(updateObj);
        if(local)uploadedFiles.indexAsset("GEO",stored.getId(),stored.getAssetId(),resource);
        return changed;
    }
    @Override
    public int removeDaAssetGeo(Collection<Long> idList) {
        // Batch delete data asset - vector
        return daAssetGeoMapper.deleteBatchIds(idList);
    }

    @Override
    public DaAssetGeoDO getDaAssetGeoById(Long id) {
        return daAssetGeoMapper.selectById(id);
    }

    @Override
    public List<DaAssetGeoDO> getDaAssetGeoList() {
        return daAssetGeoMapper.selectList();
    }

    @Override
    public Map<Long, DaAssetGeoDO> getDaAssetGeoMap() {
        List<DaAssetGeoDO> daAssetGeoList = daAssetGeoMapper.selectList();
        return daAssetGeoList.stream()
                .collect(Collectors.toMap(
                        DaAssetGeoDO::getId,
                        daAssetGeoDO -> daAssetGeoDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }


        /**
         * Import data asset - vector data
         *
         * @param importExcelList Data asset - vector data list
         * @param isUpdateSupport Whether to support update, if already exists, update the data
         * @param operName Operator user
         * @return Result
         */
        @Override
        public String importDaAssetGeo(List<DaAssetGeoRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        var requests=children.imports(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,importExcelList,isUpdateSupport,DaAssetGeoSaveReqVO.class);
        for(var request:requests){if(isUpdateSupport)updateDaAssetGeo(request);else createDaAssetGeo(request);}
        return "成功导入 "+requests.size()+" 条子资源记录";
    }
}
