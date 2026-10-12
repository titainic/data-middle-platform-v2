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

package tech.qiantong.qdata.module.da.service.assetchild.api.impl;

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
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamSaveReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.api.DaAssetApiParamDO;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.api.DaAssetApiParamMapper;
import tech.qiantong.qdata.module.da.service.assetchild.api.IDaAssetApiParamService;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
/**
 * Data Asset - External API - Parameters Service Business Layer
 *
 * @author qdata
 * @date 2025-04-14
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaAssetApiParamServiceImpl  extends ServiceImpl<DaAssetApiParamMapper,DaAssetApiParamDO> implements IDaAssetApiParamService {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildReferences childReferences;
    @Resource private tech.qiantong.qdata.module.da.service.asset.ApiParameterGraph parameterGraph;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildAccess children;
    @Resource
    private DaAssetApiParamMapper daAssetApiParamMapper;

    @Override
    public PageResult<DaAssetApiParamDO> getDaAssetApiParamPage(DaAssetApiParamPageReqVO pageReqVO) {
        return daAssetApiParamMapper.selectPage(pageReqVO);
    }

    @Override
    public Long createDaAssetApiParam(DaAssetApiParamSaveReqVO request) {
        parameterGraph.prepareCreate(request);
        return insertPrepared(request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createDaAssetApiParamDeep(List<DaAssetApiParamSaveReqVO> paramList, Long apiId) {createDaAssetApiParamDeep(paramList,apiId,null);}
    @Override
    public void createDaAssetApiParamDeep(List<DaAssetApiParamSaveReqVO> paramList, Long apiId,String expectedVersion) {
        var plan=parameterGraph.plan(paramList,apiId,expectedVersion);
        for(var node:plan.nodes()){
            var request=node.request();request.setParentId(node.parent()==null?0L:node.parent().request().getId());
            if(node.before()==null)request.setId(insertPrepared(request));
            else if(node.changed())updatePrepared(request);
        }
        if(!plan.removed().isEmpty())parameterGraph.archive(plan.removed());
    }

    private Long insertPrepared(DaAssetApiParamSaveReqVO request){
        request.setCreatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());
        request.setCreateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());request.setCreateTime(new java.util.Date());
        request.setUpdatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());request.setUpdateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());request.setUpdateTime(new java.util.Date());
        var row=BeanUtils.toBean(request,DaAssetApiParamDO.class);daAssetApiParamMapper.insert(row);return row.getId();
    }
    private int updatePrepared(DaAssetApiParamSaveReqVO request){
        var row=BeanUtils.toBean(request,DaAssetApiParamDO.class);row.setCreatorId(null);row.setCreateBy(null);row.setCreateTime(null);row.setValidFlag(null);row.setDelFlag(null);
        row.setUpdatorId(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());row.setUpdateBy(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());row.setUpdateTime(new java.util.Date());
        return daAssetApiParamMapper.updateById(row);
    }
    @Override
    public int updateDaAssetApiParam(DaAssetApiParamSaveReqVO request) {parameterGraph.prepareUpdate(request);return updatePrepared(request);}
    @Override
    public int removeDaAssetApiParam(Collection<Long> ids) {if(ids==null || ids.isEmpty())return 0;parameterGraph.prepareRemove(ids);return parameterGraph.archive(ids);}
    @Override
    public int removeThemeRelByAssetApiId(Long apiId) {
        var plan=parameterGraph.plan(java.util.List.of(),apiId);if(!plan.removed().isEmpty())parameterGraph.archive(plan.removed());return 0;
    }

    @Override
    public DaAssetApiParamDO getDaAssetApiParamById(Long id) {
        return daAssetApiParamMapper.selectById(id);
    }

    @Override
    public List<DaAssetApiParamDO> getDaAssetApiParamList() {
        return daAssetApiParamMapper.selectList();
    }
    @Override
    public List<DaAssetApiParamRespVO> getDaAssetApiParamList(Long id) {
        MPJLambdaWrapper<DaAssetApiParamDO> lambdaWrapper = new MPJLambdaWrapper();
        lambdaWrapper.eq(id != null, DaAssetApiParamDO::getApiId, id);
        List<DaAssetApiParamDO> daAssetApiParamDOS = daAssetApiParamMapper.selectList(lambdaWrapper);
        List<DaAssetApiParamRespVO> daAssetApiParamRespVOList = BeanUtils.toBean(daAssetApiParamDOS, DaAssetApiParamRespVO.class);
        return buildParamTree(daAssetApiParamRespVOList);
    }


    /**
     * Assemble a flat parameter list into a tree structure
     *
     * @param flatList The RespVO list queried and converted from the database
     * @return Tree-structured RespVO list (only root nodes)
     */
    public List<DaAssetApiParamRespVO> buildParamTree(List<DaAssetApiParamRespVO> flatList) {
        if (flatList == null || flatList.isEmpty()) {
            return Collections.emptyList();
        }
        // Use id -> node mapping to speed up lookup
        Map<Long, DaAssetApiParamRespVO> idMap = flatList.stream()
                .collect(Collectors.toMap(DaAssetApiParamRespVO::getId, Function.identity()));

        List<DaAssetApiParamRespVO> tree = new ArrayList<>();
        for (DaAssetApiParamRespVO node : flatList) {
            Long parentId = node.getParentId();
            if (parentId == null || parentId == 0) {
                // No parent node, treat as root
                tree.add(node);
            } else {
                DaAssetApiParamRespVO parent = idMap.get(parentId);
                if (parent != null) {
                    if (parent.getDaAssetApiParamList() == null) {
                        parent.setDaAssetApiParamList(new ArrayList<>());
                    }
                    parent.getDaAssetApiParamList().add(node);
                } else {
                    // Parent node not found, also treat as root
                    tree.add(node);
                }
            }
        }
        return tree;
    }

    @Override
    public Map<Long, DaAssetApiParamDO> getDaAssetApiParamMap() {
        List<DaAssetApiParamDO> daAssetApiParamList = daAssetApiParamMapper.selectList();
        return daAssetApiParamList.stream()
                .collect(Collectors.toMap(
                        DaAssetApiParamDO::getId,
                        daAssetApiParamDO -> daAssetApiParamDO,
                        // Keep existing value
                        (existing, replacement) -> existing
                ));
    }


    /**
     * Import Data Asset - External API - Parameters data
     *
     * @param importExcelList Data Asset - External API - Parameters data list
     * @param isUpdateSupport Whether to support update; if already exists, update the data
     * @param operName Operating user
     * @return Result
     */
    @Override
    public String importDaAssetApiParam(List<DaAssetApiParamRespVO> importExcelList, boolean isUpdateSupport, String operName) {
        var requests=children.imports(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.PARAM,importExcelList,isUpdateSupport,DaAssetApiParamSaveReqVO.class);
        if(isUpdateSupport){parameterGraph.prepareBatchUpdate(requests);for(var request:requests)updatePrepared(request);}
        else{new java.util.TreeSet<>(requests.stream().map(DaAssetApiParamSaveReqVO::getApiId).toList()).forEach(parameterGraph::lock);for(var request:requests)createDaAssetApiParam(request);}
        return "成功导入 "+requests.size()+" 条子资源记录";
    }
}
