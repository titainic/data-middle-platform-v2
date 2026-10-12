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

package tech.qiantong.qdata.module.da.service.assetchild.video.impl;

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
import tech.qiantong.qdata.module.da.controller.admin.assetchild.video.vo.DaAssetVideoPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.video.vo.DaAssetVideoReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.video.vo.DaAssetVideoRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.video.vo.DaAssetVideoSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.video.DaAssetVideoDO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.video.DaAssetVideoMapper;
import tech.qiantong.qdata.module.da.service.assetchild.video.IDaAssetVideoService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data asset-video data Service business layer processing
 *
 * @author qdata
 * @date 2025-04-14
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaAssetVideoServiceImpl  extends ServiceImpl<DaAssetVideoMapper,DaAssetVideoDO> implements IDaAssetVideoService {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildAccess children;
    @Resource
    private DaAssetVideoMapper daAssetVideoMapper;

    @Override
    public PageResult<DaAssetVideoDO> getDaAssetVideoPage(DaAssetVideoPageReqVO pageReqVO) {
        return daAssetVideoMapper.selectPage(pageReqVO);
    }

    @Override
    public DaAssetVideoRespVO getDaAssetVideoByAssetId(Long assetId) {
        LambdaQueryWrapperX<DaAssetVideoDO> queryWrapperX = new LambdaQueryWrapperX<>();
        queryWrapperX.eqIfPresent(DaAssetVideoDO::getAssetId,assetId);
        DaAssetVideoDO daAssetApiDO = daAssetVideoMapper.selectOne(queryWrapperX);
        return BeanUtils.toBean(daAssetApiDO, DaAssetVideoRespVO.class);
    }

    @Override
    public Long createDaAssetVideo(DaAssetVideoSaveReqVO createReqVO) {
        DaAssetVideoDO dictType = BeanUtils.toBean(createReqVO, DaAssetVideoDO.class);
        daAssetVideoMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    public int updateDaAssetVideo(DaAssetVideoSaveReqVO updateReqVO) {
        // Validation checks

        // Update data asset-video data
        DaAssetVideoDO updateObj = BeanUtils.toBean(updateReqVO, DaAssetVideoDO.class);
        updateObj.setCreatorId(null);updateObj.setCreateBy(null);updateObj.setCreateTime(null);
        return daAssetVideoMapper.updateById(updateObj);
    }
    @Override
    public int removeDaAssetVideo(Collection<Long> idList) {
        // Batch delete data asset-video data
        return daAssetVideoMapper.deleteBatchIds(idList);
    }

    @Override
    public DaAssetVideoDO getDaAssetVideoById(Long id) {
        return daAssetVideoMapper.selectById(id);
    }

    @Override
    public List<DaAssetVideoDO> getDaAssetVideoList() {
        return daAssetVideoMapper.selectList();
    }

    @Override
    public Map<Long, DaAssetVideoDO> getDaAssetVideoMap() {
        List<DaAssetVideoDO> daAssetVideoList = daAssetVideoMapper.selectList();
        return daAssetVideoList.stream()
                .collect(Collectors.toMap(
                        DaAssetVideoDO::getId,
                        daAssetVideoDO -> daAssetVideoDO,
                        // Retain existing values
                        (existing, replacement) -> existing
                ));
    }


        /**
         * Import data asset-video data
         *
         * @param importExcelList Data asset-video data list
         * @param isUpdateSupport Whether to support update, if already exists, update the data
         * @param operName Operator user
         * @return Result
         */
        @Override
        public String importDaAssetVideo(List<DaAssetVideoRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        var requests=children.imports(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.VIDEO,importExcelList,isUpdateSupport,DaAssetVideoSaveReqVO.class);
        for(var request:requests){if(isUpdateSupport)updateDaAssetVideo(request);else createDaAssetVideo(request);}
        return "成功导入 "+requests.size()+" 条子资源记录";
    }

    @Override
    public void queryServiceForwarding(HttpServletResponse response, DaAssetVideoReqVO daAssetVideoReqVO) {

    }
}
