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

package tech.qiantong.qdata.module.da.service.assetchild.gis.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.httpClient.HeaderEntity;
import tech.qiantong.qdata.common.httpClient.HttpUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.da.api.service.assetchild.gis.IDaAssetGisOutService;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.gis.vo.DaAssetGisPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.gis.vo.DaAssetGisReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.gis.vo.DaAssetGisRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.gis.vo.DaAssetGisSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.gis.DaAssetGisDO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.gis.DaAssetGisMapper;
import tech.qiantong.qdata.module.da.service.assetchild.gis.IDaAssetGisService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Data Asset - Geospatial Service business layer processing
 *
 * @author qdata
 * @date 2025-04-14
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaAssetGisServiceImpl  extends ServiceImpl<DaAssetGisMapper,DaAssetGisDO> implements IDaAssetGisService, IDaAssetGisOutService {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildReferences childReferences;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildAccess children;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetForwardingAccess forwardingAccess;
    @Resource
    private DaAssetGisMapper daAssetGisMapper;

    @Override
    public PageResult<DaAssetGisDO> getDaAssetGisPage(DaAssetGisPageReqVO pageReqVO) {
        return daAssetGisMapper.selectPage(pageReqVO);
    }

    @Override
    public DaAssetGisRespVO getDaAssetGisByAssetId(Long assetId) {
        LambdaQueryWrapperX<DaAssetGisDO> queryWrapperX = new LambdaQueryWrapperX<>();
        queryWrapperX.eqIfPresent(DaAssetGisDO::getAssetId,assetId);
        DaAssetGisDO daAssetApiDO = daAssetGisMapper.selectOne(queryWrapperX);
        return BeanUtils.toBean(daAssetApiDO, DaAssetGisRespVO.class);
    }

    @Override
    public Long createDaAssetGis(DaAssetGisSaveReqVO createReqVO) {
        DaAssetGisDO dictType = BeanUtils.toBean(createReqVO, DaAssetGisDO.class);
        daAssetGisMapper.insert(dictType);
        return dictType.getId();
    }

    @Override
    public int updateDaAssetGis(DaAssetGisSaveReqVO updateReqVO) {
        childReferences.stable("GIS",updateReqVO);
        // Related validation

        // Update data asset - geospatial service
        DaAssetGisDO updateObj = BeanUtils.toBean(updateReqVO, DaAssetGisDO.class);
        updateObj.setCreatorId(null);updateObj.setCreateBy(null);updateObj.setCreateTime(null);
        return daAssetGisMapper.updateById(updateObj);
    }
    @Override
    public int removeDaAssetGis(Collection<Long> idList) {
        for(Long id:idList)childReferences.removable("GIS",id);
        // Batch delete data asset - geospatial service
        return daAssetGisMapper.deleteBatchIds(idList);
    }

    @Override
    public DaAssetGisDO getDaAssetGisById(Long id) {
        return daAssetGisMapper.selectById(id);
    }

    @Override
    public List<DaAssetGisDO> getDaAssetGisList() {
        return daAssetGisMapper.selectList();
    }

    @Override
    public Map<Long, DaAssetGisDO> getDaAssetGisMap() {
        List<DaAssetGisDO> daAssetGisList = daAssetGisMapper.selectList();
        return daAssetGisList.stream()
                .collect(Collectors.toMap(
                        DaAssetGisDO::getId,
                        daAssetGisDO -> daAssetGisDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }


        /**
         * Import data asset - geospatial service data
         *
         * @param importExcelList Data asset - geospatial service data list
         * @param isUpdateSupport Whether to support update, if already exists, update the data
         * @param operName Operator user
         * @return Result
         */
        @Override
        public String importDaAssetGis(List<DaAssetGisRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        var requests=children.imports(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GIS,importExcelList,isUpdateSupport,DaAssetGisSaveReqVO.class);
        for(var request:requests){if(isUpdateSupport)updateDaAssetGis(request);else createDaAssetGis(request);}
        return "成功导入 "+requests.size()+" 条子资源记录";
    }

    @Override
    public void queryServiceForwarding(HttpServletResponse response, DaAssetGisReqVO daAssetGisReqVO) {
        daAssetGisReqVO.setAssetId(forwardingAccess.authorize("gis",daAssetGisReqVO.getId(),daAssetGisReqVO.getAssetId()));
        this.executeServiceForwarding(response,daAssetGisReqVO.getId(),daAssetGisReqVO.getQueryParams());
    }

    @Override
    public void executeServiceForwarding(HttpServletResponse response, Long gisId, Map<String, Object> queryParams) {
        // Get third-party API config by ID
        DaAssetGisDO daAssetGisDO = this.getDaAssetGisById(gisId);

        // Validate API info, e.g. whether it is enabled
        chackYapiConfig(daAssetGisDO);

        // Extract URL
        String url = daAssetGisDO.getUrl();

        // Package headers
        List<HeaderEntity> headerEntities = packHeadersOrYApiField(queryParams);
        // Invoke third-party API
        try {
            // Extract request method
            String reqMethod = daAssetGisDO.getHttpMethod();
            // Extract input parameters
            Map<String, Object> params = ( Map<String, Object>) MapUtils.getMap(queryParams, "params", new HashMap<>());
            this.fillDefaultWmtsParams(params,reqMethod);
            // GET
            if (StringUtils.equals(HttpUtils.GET, reqMethod)) {// Package GET request
                HttpUtils.sendGet(HttpUtils.packGetRequestURL(url, params), response, headerEntities);
            } else if (StringUtils.equals(HttpUtils.POST, reqMethod)) {// POST
                HttpUtils.sendPost(url, params, response, headerEntities);
            } else {// Unknown
                throw new DataQueryException("db.error.api.type", "API type error");
            }
        } catch (tech.qiantong.qdata.common.exception.DependencyUnavailableException failure) {
            throw failure;
        } catch (Exception e) {
            log.warn("Service forwarding failed type={}",e.getClass().getSimpleName());
            throw new DataQueryException("db.error.api.http", "HTTP invocation failed");
        }
    }

    private void chackYapiConfig(DaAssetGisDO daAssetGisDO) {
        // Check if null
        if (daAssetGisDO == null) {
            throw new DataQueryException("db.error.api.config.missing", "API call, API config not found");
        }
    }

    /**
     * Explicitly check for missing WMTS parameters and fill defaults
     * @param params Input parameter Map, will be modified
     */
    private void fillDefaultWmtsParams(Map<String, Object> params,String reqMethod ) {
        String service = MapUtils.getString(params, "service");
        if (StringUtils.isBlank(service)) {
            params.put("service", "WMTS");
        }

        String request = MapUtils.getString(params, "request");
        if (StringUtils.isBlank(request)) {
            params.put("request", "GetCapabilities");
        }

        String version = MapUtils.getString(params, "version");
        if (StringUtils.isBlank(version)) {
            params.put("version", "1.0.0");
        }
    }
    /**
     * Package Headers
     *
     * @param queryParams
     * @return
     */
    public static List<HeaderEntity> packHeadersOrYApiField(Map<String, Object> queryParams) {
        List<Map<String,Object>> fieldHerderList = (List<Map<String,Object>>)MapUtils.getObject(queryParams, "fieldHerderList", new ArrayList<>());

        // Package headers
        List<HeaderEntity> headerEntityList = new ArrayList<>();
        if(CollectionUtils.isEmpty(fieldHerderList)){
            return headerEntityList;
        }

        for (Map<String, Object> stringObjectMap : fieldHerderList) {
            if(MapUtils.isNotEmpty(stringObjectMap)){
                HeaderEntity headerEntity = new HeaderEntity();
                headerEntity.setKey(MapUtils.getString(stringObjectMap,"name"));
                String defaultValue = MapUtils.getString(stringObjectMap, "defaultValue");
                if(defaultValue == null){
                    throw new DataQueryException("db.error.api.header.null", "Header value cannot be null");
                }
                headerEntity.setValue(defaultValue);
                headerEntityList.add(headerEntity);
            }
        }
        return headerEntityList;
    }
}
