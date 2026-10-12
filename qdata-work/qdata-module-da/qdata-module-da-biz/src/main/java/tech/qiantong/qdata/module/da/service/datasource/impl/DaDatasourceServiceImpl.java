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

package tech.qiantong.qdata.module.da.service.datasource.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.constants.DbType;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.core.DbName;
import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import tech.qiantong.qdata.common.enums.KingbaseColumnTypeEnum;
import tech.qiantong.qdata.common.enums.MySqlColumnTypeEnum;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.AesEncryptUtil;
import tech.qiantong.qdata.common.utils.DateUtils;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.att.api.project.IAttProjectApi;
import tech.qiantong.qdata.module.att.api.project.dto.AttProjectReqDTO;
import tech.qiantong.qdata.module.att.api.project.dto.AttProjectRespDTO;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.datasource.dto.DatasourceCreaTeTableListReqDTO;
import tech.qiantong.qdata.module.da.api.datasource.dto.DatasourceCreaTeTableReqDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourcePageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceRespVO;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourceSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.discovery.vo.DaDiscoveryColumnPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.discovery.vo.DaDiscoveryTablePageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.discovery.vo.DaDiscoveryTaskLogSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.discovery.vo.DaDiscoveryTaskRespVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetColumn.DaAssetColumnDO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceProjectRelDO;
import tech.qiantong.qdata.module.da.dal.dataobject.discovery.DaDiscoveryColumnDO;
import tech.qiantong.qdata.module.da.dal.dataobject.discovery.DaDiscoveryTableDO;
import tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceProjectRelService;
import tech.qiantong.qdata.module.da.service.datasource.IDaDatasourceService;
import tech.qiantong.qdata.module.da.service.discovery.*;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelColumnReqDTO;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelColumnRespDTO;
import tech.qiantong.qdata.module.dp.api.service.model.IDpModelApiService;
import tech.qiantong.qdata.module.dpp.api.service.etl.DppEtlTaskService;
import tech.qiantong.qdata.module.system.service.ISysMessageService;
import tech.qiantong.qdata.mybatis.core.query.LambdaQueryWrapperX;
import tech.qiantong.qdata.redis.service.IRedisService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Datasource Service business layer processing
 *
 * @author lhs
 * @date 2025-01-21
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class DaDatasourceServiceImpl extends ServiceImpl<DaDatasourceMapper, DaDatasourceDO> implements IDaDatasourceService, IDaDatasourceApiService {
    // Canary only. External Workers still depend on the legacy "datasource" hash;
    // do not switch or remove it until their credential-resolution contract is verified.
    @Value("${qdata.datasource.cache.secretless-shadow-enabled:false}")
    private boolean secretlessCacheShadowEnabled;
    @Resource
    private tech.qiantong.qdata.module.da.service.datasource.DatasourceReferenceGuard datasourceReferences;
    @Resource
    private org.springframework.beans.factory.ObjectProvider<tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser> hiveNamespaces;
    @Resource
    private DaDatasourceMapper daDatasourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Resource
    private IDpModelApiService dpModelApiService;

    @Resource
    private IDaDatasourceProjectRelService daDatasourceProjectRelService;

    @Resource
    private IAttProjectApi attProjectApi;

    @Resource
    private DppEtlTaskService dppEtlTaskService;

    @Autowired
    private IRedisService redisService;

    @Autowired
    private ISysMessageService iSysMessageService;

    @Autowired
    @Lazy
    private IDaDiscoveryTaskService iDaDiscoveryTaskService;

    @Autowired
    @Lazy
    private IDaDiscoveryColumnService iDaDiscoveryColumnService;

    @Autowired
    @Lazy
    private IDaDiscoveryTableService iDaDiscoveryTableService;

    @Autowired
    @Lazy
    private IDaDiscoveryTaskLogService iDaDiscoveryTaskLogService;

    @Resource
    private IDaDiscoveryLogBodyService iDaDiscoveryLogBodyService;


    /**
     * Initialize the datasource cache in Redis after project startup.
     *
     * Functionality:
     * 1. Load all datasource records from the database;
     * 2. Extract key fields using the DaDatasourceDO.simplify() method;
     * 3. Write the results to a Redis Hash:
     *
     * Scenarios:
     * - After project startup, warm up the datasource cache so that task scheduling
     *   and Worker nodes can directly obtain configuration info from Redis;
     * - Avoid strong runtime dependencies on the database or middle-platform services.
     *
     * Note:
     * - Optional: the existing "datasource" cache in Redis can be cleared before initialization.
     */
    @PostConstruct
    public void initDatasourceCache() {
        try {
            List<DaDatasourceDO> list = daDatasourceMapper.selectList();
            if (list == null || list.isEmpty()) {
                log.info("[Datasource Cache Init] No datasource records found, skipping initialization.");
                return;
            }

            // Optional: clear cache before initialization
            // redisService.del("datasource");

            for (DaDatasourceDO ds : list) {
                if (ds == null || ds.getId() == null) {
                    continue;
                }

                try {
                    // Latest datasource connection info
                    String field = String.valueOf(ds.getId());
                    if (!Boolean.TRUE.equals(ds.getValidFlag())) {
                        // A startup snapshot must not invalidate a newer in-flight SQL change.
                        redisService.evictDatasourceIfUnfenced(field);
                        continue;
                    }
                    // Warmup can only publish rows that have no pending SQL change.
                    refreshDatasourceCache(ds);

                    // TODO: historical datasource connection info (temporary handling, can be removed later)
                    DbQueryProperty property = new DbQueryProperty(
                            ds.getDatasourceType(),
                            ds.getIp(),
                            ds.getPort(),
                            ds.getDatasourceConfig());
                    String key = property.trainToJdbcUrl();

                    // Check if already exists
                    Boolean exists = redisService.hashHasKey("datasource-old", key);
                    if (Boolean.FALSE.equals(exists)) {
                        redisService.hashPut("datasource-old", key, ds.getId().toString());
                        log.info("Stored historical datasource mapping, datasourceId={}", ds.getId());
                    } else {
                        log.info("Historical datasource mapping already exists, datasourceId={}", ds.getId());
                    }
                } catch (Exception e) {
                    log.warn("Unsupported datasource conversion");
                }
            }

            log.info("[Datasource Cache Init] Successfully loaded {} datasources into Redis.", list.size());
        } catch (Exception e) {
            log.error("[Datasource Cache Init] Failed to load Redis cache");
        }
    }

    /**
     * Query datasource connection info for a data asset
     *
     * @param daAsset
     * @return
     */
    @Override
    public List<DaDatasourceDO> getDataSourceByAsset(DaDatasourceRespVO daAsset) {
        return daDatasourceMapper.selectList();
    }

    @Override
    public PageResult<DaDatasourceDO> getDaDatasourcePage(DaDatasourcePageReqVO pageReqVO) {
        return daDatasourceMapper.selectPage(pageReqVO);
    }

    @Override
    public PageResult<DaDatasourceDO> getDaDatasourceDppPage(DaDatasourcePageReqVO pageReqVO) {
        if (StringUtils.isEmpty(pageReqVO.getProjectCode())) {
            return PageResult.empty();
        }
        DaDatasourceProjectRelDO daDatasourceProjectRelDO = new DaDatasourceProjectRelDO();
        daDatasourceProjectRelDO.setProjectCode(pageReqVO.getProjectCode());
        List<DaDatasourceProjectRelDO> daDatasourceProjectRelList = daDatasourceProjectRelService.getJoinProjectAndDatasource(daDatasourceProjectRelDO);
        if (daDatasourceProjectRelList.isEmpty()) {
            return PageResult.empty();
        }
        Map<Long, DaDatasourceProjectRelDO> datasourceProjectRelDOMap = daDatasourceProjectRelList.stream().collect(Collectors.toMap(DaDatasourceProjectRelDO::getDatasourceId, daDatasourceProjectRelDO1 -> daDatasourceProjectRelDO1));
        List<Long> idList = datasourceProjectRelDOMap.keySet().stream().collect(Collectors.toList());
        pageReqVO.setIdList(idList);
        PageResult<DaDatasourceDO> daDatasourceDOPageResult = daDatasourceMapper.selectPage(pageReqVO);
        for (Object row : daDatasourceDOPageResult.getRows()) {
            DaDatasourceDO daDatasourceDO = (DaDatasourceDO) row;
            DaDatasourceProjectRelDO datasourceProjectRelDO = datasourceProjectRelDOMap.get(daDatasourceDO.getId()) == null ? new DaDatasourceProjectRelDO() : datasourceProjectRelDOMap.get(daDatasourceDO.getId());
            if (idList.contains(daDatasourceDO.getId()) && !datasourceProjectRelDO.getDppAssigned()) {
                daDatasourceDO.setIsAdminAddTo(false);
                daDatasourceDO.setProjectName(datasourceProjectRelDO.getProjectName());
            }
        }
        return daDatasourceDOPageResult;
    }

    @Override
    public List<DaDatasourceDO> getDaDatasourceList(DaDatasourcePageReqVO reqVO) {
        LambdaQueryWrapperX<DaDatasourceDO> daDatasourceDOLambdaQueryWrapperX = new LambdaQueryWrapperX<>();
        daDatasourceDOLambdaQueryWrapperX.likeIfPresent(DaDatasourceDO::getDatasourceName, reqVO.getDatasourceName())
                .like(StringUtils.isNotEmpty(reqVO.getDatasourceType()), DaDatasourceDO::getDatasourceType, reqVO.getDatasourceType())
                .eq(StringUtils.isNotEmpty(reqVO.getIp()), DaDatasourceDO::getIp, reqVO.getIp());

        return daDatasourceMapper.selectList(daDatasourceDOLambdaQueryWrapperX);
    }

    @Override
    public Long createDaDatasource(DaDatasourceSaveReqVO createReqVO) {
        DatasourceSecretConfig.rejectClearOnCreate(createReqVO.getClearSecrets());
        normalizeDatasource(createReqVO);
        validateDatasourceConfig(createReqVO);
        checkDuplicateDatasource(createReqVO);
        if (!Boolean.TRUE.equals(createReqVO.getSkipConnectionValidation())) {
            ensureEnabledDatasourceConnectable(createReqVO);
        }

        DaDatasourceDO dictType = BeanUtils.toBean(createReqVO, DaDatasourceDO.class);
        daDatasourceMapper.insert(dictType);
        delAndSaveDaDataSourceProject(dictType);

        stageDatasourceCacheRefresh(this.getDaDatasourceById(dictType.getId()));

        return dictType.getId();
    }

    @Override
    public int updateDaDatasource(DaDatasourceSaveReqVO updateReqVO) {
        String previousConfig = mergeStoredSecrets(updateReqVO);
        normalizeDatasource(updateReqVO);
        validateDatasourceConfig(updateReqVO);
        checkDuplicateDatasource(updateReqVO);
        ensureEnabledDatasourceConnectable(updateReqVO);

        Long datasourceId = updateReqVO.getId();

        // Update datasource
        DaDatasourceDO updateObj = BeanUtils.toBean(updateReqVO, DaDatasourceDO.class);
        // Compare exact stored bytes: a concurrent rotation must make a stale
        // "keep existing" edit fail instead of restoring the old credential.
        var condition = Wrappers.<DaDatasourceDO>lambdaUpdate().eq(DaDatasourceDO::getId, datasourceId);
        if (previousConfig == null) {
            condition.apply("datasource_config IS NULL");
        } else {
            condition.apply("HEX(datasource_config) = HEX({0})", previousConfig);
        }
        int i = daDatasourceMapper.update(updateObj, condition);
        if (i != 1) {
            throw new ServiceException("da.error.datasource.config.concurrent",
                    "The data connection changed; refresh it before saving again");
        }
        delAndSaveDaDataSourceProject(updateObj);
        stageDatasourceCacheRefresh(this.getDaDatasourceById(datasourceId));
        return i;
    }

    /** Evict in the SQL transaction; publish credentials only after commit. */
    private void stageDatasourceCacheRefresh(DaDatasourceDO saved) {
        if (saved == null || saved.getId() == null) {
            throw new ServiceException("da.error.datasource.detail.fail", "Datasource does not exist");
        }
        Long datasourceId = saved.getId();
        // Invalidate the previous Worker credential before SQL commit. A rollback
        // then leaves a cache miss rather than an uncommitted credential.
        String fenceToken = evictDatasourceCacheFields(datasourceId);
        if (!Boolean.TRUE.equals(saved.getValidFlag())) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    DaDatasourceDO committed = daDatasourceMapper.selectById(datasourceId);
                    if (committed != null && Boolean.TRUE.equals(committed.getValidFlag())) {
                        publishDatasourceCache(committed, fenceToken);
                    }
                }
            });
        } else {
            publishDatasourceCache(saved, fenceToken);
        }
    }

    /** Startup warmup has no transaction token and must leave pending rows untouched. */
    private void refreshDatasourceCache(DaDatasourceDO saved) {
        if (saved == null || saved.getId() == null) {
            throw new ServiceException("da.error.datasource.detail.fail", "Datasource does not exist");
        }
        String key = saved.getId().toString();
        if (!Boolean.TRUE.equals(saved.getValidFlag())) {
            redisService.evictDatasourceIfUnfenced(key);
            return;
        }
        redisService.publishDatasourceIfCurrent(key, null,
                com.alibaba.fastjson2.JSONObject.toJSONString(saved.simplify()),
                secretlessCacheShadowEnabled ? DatasourceCacheMetadata.serialize(saved) : null);
    }

    private void publishDatasourceCache(DaDatasourceDO saved, String fenceToken) {
        if (!Boolean.TRUE.equals(saved.getValidFlag())) {
            return;
        }
        String field = saved.getId().toString();
        // The Lua comparison and both hash writes form one command. An older
        // afterCommit callback cannot overwrite a later transaction's fence.
        redisService.publishDatasourceIfCurrent(field, fenceToken,
                com.alibaba.fastjson2.JSONObject.toJSONString(saved.simplify()),
                secretlessCacheShadowEnabled ? DatasourceCacheMetadata.serialize(saved) : null);
    }

    private String evictDatasourceCacheFields(Long datasourceId) {
        String field = datasourceId.toString();
        String fenceToken = UUID.randomUUID().toString();
        // Fence and both evictions are atomic; a Redis error aborts the SQL transaction.
        redisService.fenceAndEvictDatasource(field, fenceToken);
        return fenceToken;
    }

    @Override
    public boolean testDatasourceConnection(DaDatasourceSaveReqVO datasource) {
        try {
            if (datasource.getId() == null) {
                DatasourceSecretConfig.rejectClearOnCreate(datasource.getClearSecrets());
            } else {
                mergeStoredSecrets(datasource);
            }
            normalizeDatasource(datasource);
            validateDatasourceConfig(datasource);
            DbQueryProperty property = new DbQueryProperty(
                    datasource.getDatasourceType(),
                    datasource.getIp(),
                    datasource.getPort(),
                    datasource.getDatasourceConfig()
            );
            return clientTest(property);
        } catch (Exception exception) {
            log.warn("Datasource connection validation failed");
            return false;
        }
    }

    private String mergeStoredSecrets(DaDatasourceSaveReqVO datasource) {
        if (datasource.getId() == null) {
            throw new ServiceException("da.error.datasource.detail.fail", "Datasource ID is required for editing");
        }
        DaDatasourceDO stored = daDatasourceMapper.selectById(datasource.getId());
        if (stored == null) {
            throw new ServiceException("da.error.datasource.detail.fail", "Datasource does not exist");
        }
        datasource.setDatasourceConfig(DatasourceSecretConfig.merge(
                stored.getDatasourceConfig(), datasource.getDatasourceConfig(), datasource.getClearSecrets()));
        return stored.getDatasourceConfig();
    }

    private void normalizeDatasource(DaDatasourceSaveReqVO datasource) {
        datasource.setDatasourceName(StrUtil.trim(datasource.getDatasourceName()));
        datasource.setDatasourceType(StrUtil.trim(datasource.getDatasourceType()));
        datasource.setIp(StrUtil.trim(datasource.getIp()));

        if (StrUtil.isBlank(datasource.getDatasourceConfig()) || !JSONUtil.isJsonObj(datasource.getDatasourceConfig())) {
            return;
        }
        JSONObject config = JSONUtil.parseObj(datasource.getDatasourceConfig());
        trimConfigValue(config, "username");
        trimConfigValue(config, "dbname");
        trimConfigValue(config, "sid");
        datasource.setDatasourceConfig(config.toString());
    }

    private void trimConfigValue(JSONObject config, String key) {
        Object value = config.get(key);
        if (value instanceof String) {
            config.set(key, StrUtil.trim((String) value));
        }
    }

    private void validateDatasourceConfig(DaDatasourceSaveReqVO datasource) {
        if (StrUtil.isBlank(datasource.getDatasourceConfig()) || !JSONUtil.isJsonObj(datasource.getDatasourceConfig())) {
            throw new ServiceException("da.error.datasource.config.invalid",
                    "The data source configuration format is invalid");
        }
        JSONObject config = JSONUtil.parseObj(datasource.getDatasourceConfig());
        String type = datasource.getDatasourceType();
        if (!"OSS-ALIYUN".equals(type) && !"Kafka".equals(type) && !"HDFS".equals(type)
                && StrUtil.isBlank(config.getStr("username"))) {
            throw new ServiceException("da.error.datasource.account.empty",
                    "Account cannot be empty or contain only spaces");
        }
    }

    private void checkDuplicateDatasource(DaDatasourceSaveReqVO datasource) {
        LambdaQueryWrapper<DaDatasourceDO> nameWrapper = new LambdaQueryWrapper<>();
        nameWrapper.eq(DaDatasourceDO::getDatasourceName, datasource.getDatasourceName());
        excludeCurrentDatasource(nameWrapper, datasource.getId());
        if (this.count(nameWrapper) > 0) {
            throw new ServiceException("da.error.datasource.name.duplicate",
                    "A data source with the same name already exists; change the data source name");
        }

        LambdaQueryWrapper<DaDatasourceDO> connectionWrapper = new LambdaQueryWrapper<>();
        connectionWrapper.eq(DaDatasourceDO::getDatasourceType, datasource.getDatasourceType())
                .eq(DaDatasourceDO::getIp, datasource.getIp())
                .eq(DaDatasourceDO::getPort, datasource.getPort())
                .select(DaDatasourceDO::getId, DaDatasourceDO::getDatasourceConfig);
        excludeCurrentDatasource(connectionWrapper, datasource.getId());

        JSONObject targetConfig = JSONUtil.parseObj(datasource.getDatasourceConfig());
        String targetDbName = StrUtil.nullToEmpty(targetConfig.getStr("dbname"));
        String targetUsername = StrUtil.nullToEmpty(targetConfig.getStr("username"));
        for (DaDatasourceDO existing : this.list(connectionWrapper)) {
            if (StrUtil.isBlank(existing.getDatasourceConfig()) || !JSONUtil.isJsonObj(existing.getDatasourceConfig())) {
                continue;
            }
            JSONObject existingConfig = JSONUtil.parseObj(existing.getDatasourceConfig());
            if (targetDbName.equals(StrUtil.nullToEmpty(existingConfig.getStr("dbname")))
                    && targetUsername.equals(StrUtil.nullToEmpty(existingConfig.getStr("username")))) {
                throw new ServiceException("da.error.datasource.connection.duplicate",
                        "A data source with the same connection information already exists");
            }
        }
    }

    private void excludeCurrentDatasource(LambdaQueryWrapper<DaDatasourceDO> wrapper, Long datasourceId) {
        if (datasourceId != null) {
            wrapper.ne(DaDatasourceDO::getId, datasourceId);
        }
    }

    private void ensureEnabledDatasourceConnectable(DaDatasourceSaveReqVO datasource) {
        if (!Boolean.TRUE.equals(datasource.getValidFlag())) {
            return;
        }
        DbQueryProperty property = new DbQueryProperty(
                datasource.getDatasourceType(), datasource.getIp(), datasource.getPort(), datasource.getDatasourceConfig());
        if (!clientTest(property)) {
            throw new ServiceException("da.error.datasource.test.required",
                    "The current data source has not passed testing and cannot be enabled");
        }
    }

    private void delAndSaveDaDataSourceProject(DaDatasourceDO daDatasourceDO) {
        QueryWrapper<DaDatasourceProjectRelDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("DATASOURCE_ID", daDatasourceDO.getId());
        daDatasourceProjectRelService.remove(queryWrapper);
        if (!daDatasourceDO.getProjectList().isEmpty()) {
            for (DaDatasourceProjectRelDO daDatasourceProjectRelDO : daDatasourceDO.getProjectList()) {
                daDatasourceProjectRelDO.setDatasourceId(daDatasourceDO.getId());
                daDatasourceProjectRelDO.setId(null);
            }
            daDatasourceProjectRelService.saveBatch(daDatasourceDO.getProjectList());
        }
    }

    @Override
    public int removeDaDatasource(Collection<Long> idList) {
        datasourceReferences.requireRemovable(idList);
        // Batch delete datasources
        int deleted = daDatasourceMapper.deleteBatchIds(idList);
        idList.forEach(this::evictDatasourceCacheFields);
        return deleted;
    }

    @Override
    public int removeDaDatasourceDppOrDa(List<Long> idList, Long type) {
        datasourceReferences.requireRemovable(idList);
        int datasource = dppEtlTaskService.checkTaskIdInDatasource(idList, null);
        if (datasource > 0) {
            throw new ServiceException("da.error.delete.datasource.ref", "Deletion failed, data source is referenced by a project!");
        }
        if (!idList.isEmpty()) {
            QueryWrapper<DaDatasourceProjectRelDO> queryWrapper = new QueryWrapper<>();
            queryWrapper.in("DATASOURCE_ID", idList);
            daDatasourceProjectRelService.remove(queryWrapper);
        }
        // Batch delete datasources
        int deleted = daDatasourceMapper.deleteBatchIds(idList);
        idList.forEach(this::evictDatasourceCacheFields);
        return deleted;
    }


    @Override
    public DaDatasourceRespDTO getDatasourceById(Long id) {
        DaDatasourceRespDTO dto = new DaDatasourceRespDTO();
        DaDatasourceDO daDatasourceDO = daDatasourceMapper.selectById(id);
        org.springframework.beans.BeanUtils.copyProperties(daDatasourceDO, dto);
        return dto;
    }

    @Override
    public DaDatasourceDO getDaDatasourceById(Long id) {
        DaDatasourceDO daDatasourceDO = daDatasourceMapper.selectById(id);
        if (daDatasourceDO == null) {
            return null;
        }
        DaDatasourceProjectRelDO daDatasourceProjectRelDO = new DaDatasourceProjectRelDO();
        daDatasourceProjectRelDO.setDatasourceId(daDatasourceDO.getId());
        List<DaDatasourceProjectRelDO> daDatasourceProjectRelList = daDatasourceProjectRelService.getJoinProjectAndDatasource(daDatasourceProjectRelDO);
        daDatasourceDO.setProjectList(daDatasourceProjectRelList);
        return daDatasourceDO;
    }

    @Override
    public DaDatasourceRespVO getDaDatasourceByIdSimple(Long id) {
        return BeanUtils.toBean(daDatasourceMapper.selectById(id), DaDatasourceRespVO.class);
    }

    @Override
    public List<DaDatasourceDO> getDaDatasourceList() {
        return daDatasourceMapper.selectList();
    }

    @Override
    public Map<Long, DaDatasourceDO> getDaDatasourceMap() {
        List<DaDatasourceDO> daDatasourceList = daDatasourceMapper.selectList();
        return daDatasourceList.stream()
                .collect(Collectors.toMap(
                        DaDatasourceDO::getId,
                        daDatasourceDO -> daDatasourceDO,
                        // Preserve existing value
                        (existing, replacement) -> existing
                ));
    }


    /**
     * Import datasource data
     *
     * @param importExcelList list of datasource data
     * @param isUpdateSupport whether to update existing records
     * @param operName        operator name
     * @return result
     */
    @Override
    public String importDaDatasource(List<DaDatasourceRespVO> importExcelList,
                                     boolean isUpdateSupport, String operName) {
        // This legacy service entry point bypassed project authorization and
        // credential merging. The controller's scoped importData path is the
        // supported import API; keep this interface method closed.
        throw new ServiceException("da.error.import.legacy.disabled",
                "Use the project-scoped data connection import endpoint");
    }

    @Override
    public AjaxResult clientsTest(Long id) {
        DbQuery dbQuery = this.buildDbQuery(id);
        if (dbQuery.valid()) {
            dbQuery.close();
            return AjaxResult.success(MessageUtils.messageWithFallback("da.error.connection.success", "Database connection successful"));
        }
        dbQuery.close();
        return AjaxResult.error(MessageUtils.messageWithFallback("da.error.connection.fail", "Database connection failed"));

    }

    @Override
    public Boolean clientTest(Long id) {
        DbQuery dbQuery = null;
        try {
            dbQuery = this.buildDbQuery(id);
            return dbQuery.valid();
        } catch (Exception exception) {
            log.warn("Datasource connection test failed, datasourceId={}", id);
            return false;
        } finally {
            if (dbQuery != null) {
                dbQuery.close();
            }
        }
    }

    @Override
    public Boolean clientTest(DbQueryProperty dbQueryProperty) {
        DbQuery dbQuery = null;
        try {
            dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
            return dbQuery.valid();
        } catch (Exception exception) {
            log.warn("Datasource connection test failed");
            return false;
        } finally {
            if (dbQuery != null) {
                dbQuery.close();
            }
        }
    }

    public DbQuery buildDbQuery(Long id) {
        DaDatasourceDO daDatasourceBy = this.getDaDatasourceById(id);
        if (daDatasourceBy == null) {
            throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        }
        DbQueryProperty dbQueryProperty = new DbQueryProperty(
                daDatasourceBy.getDatasourceType(),
                daDatasourceBy.getIp(),
                daDatasourceBy.getPort(),
                daDatasourceBy.getDatasourceConfig()
        );
        return dataSourceFactory.createDbQuery(dbQueryProperty);
    }

    /**
     * @param id datasource id
     * @return
     */
    @Override
    public List<DbTable> getDbTables(Long id) {
        DaDatasourceDO daDatasourceBy = this.getDaDatasourceById(id);
        if (daDatasourceBy == null) {
            throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        }

        DbQueryProperty dbQueryProperty = new DbQueryProperty(daDatasourceBy.getDatasourceType()
                , daDatasourceBy.getIp(), daDatasourceBy.getPort(), daDatasourceBy.getDatasourceConfig());
        if ("Hive".equalsIgnoreCase(daDatasourceBy.getDatasourceType())) {
            return hiveNamespaces.getObject().tables(id, dbQueryProperty.getDbName());
        }
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        if (!dbQuery.valid()) {
            throw new DataQueryException("db.error.connection.fail", "Database connection failed");
        }
        List<DbTable> tables = dbQuery.getTables(dbQueryProperty);
        dbQuery.close();
        return tables;
    }

    /**
     * Get the data fields inside a data table
     *
     * @param jsonObject datasource id and data table
     * @return
     */
    @Override
    public List<DpModelColumnReqDTO> getColumnsList(JSONObject jsonObject) {
        List<DpModelColumnRespDTO> modelIdColumnList = new ArrayList<>();
        Boolean isOld = jsonObject.getStr("isOld") == null ? null : Boolean.valueOf(jsonObject.getStr("isOld"));
        if (isOld != null && !isOld && jsonObject.getStr("modelId") != null) {
            modelIdColumnList = dpModelApiService.getModelIdColumnList(Long.valueOf(jsonObject.getStr("modelId")));
        }
        if (modelIdColumnList.size() > 0) {
            List<DpModelColumnReqDTO> columnReqDTOList = BeanUtils.toBean(modelIdColumnList, DpModelColumnReqDTO.class);
            return columnReqDTOList;
        }
        // Get the database type
        DbType dbTypeEnum = DbType.getDbType(jsonObject.getStr("type"));
        List<DbColumn> columnList = this.getDbTableColumns(Long.valueOf(jsonObject.getStr("id")), jsonObject.getStr("tableName"));
        List<DpModelColumnReqDTO> columnReqDTOList = new ArrayList<>();
        for (DbColumn column : columnList) {
            String dataType = column.getDataType();
            switch (dbTypeEnum) {
                case DM8:
                case ORACLE:
                    break;
                case MYSQL:
                    column.setDataType(MySqlColumnTypeEnum.convertToDmType(dataType));
                    break;
                case KINGBASE8:
                    column.setDataType(KingbaseColumnTypeEnum.convertToDmType(dataType));
                    break;
            }
            DpModelColumnReqDTO dpModelColumnReqDTO = new DpModelColumnReqDTO(column);
            columnReqDTOList.add(dpModelColumnReqDTO);
        }
        return columnReqDTOList;
    }

    @Override
    public List<DaAssetColumnDO> columnsAsAssetColumnList(JSONObject jsonObject) {
        List<DbColumn> columnsList = this.getDbTableColumns(Long.valueOf(jsonObject.getStr("id")), jsonObject.getStr("tableName"));

        return this.convertDbColumns(columnsList);
    }

    @Override
    public List<DaAssetColumnDO> columnsAsAssetColumnList(Long id, String tableName) {
        List<DbColumn> columnsList = this.getDbTableColumns(id, tableName);
        return convertDbColumns(columnsList);
    }


    /**
     * Convert List<DpModelColumnReqDTO> to List<DaAssetColumnDO>
     *
     * @param columnsList list of DbColumn objects
     * @return converted DaAssetColumnDO list; returns an empty ArrayList if input is empty or null
     */
    public static List<DaAssetColumnDO> convertDbColumns(List<DbColumn> columnsList) {
        if (columnsList == null || columnsList.isEmpty()) {
            return new ArrayList<>();
        }
        List<DaAssetColumnDO> assetColumns = new ArrayList<>(columnsList.size());
        for (DbColumn dbColumn : columnsList) {
            // Use DpModelColumnReqDTO constructor to wrap DbColumn into a DTO object
            DpModelColumnReqDTO dto = new DpModelColumnReqDTO(dbColumn);
            // Use DTO data mapping to generate a DaAssetColumnDO object
            DaAssetColumnDO assetColumn = DaAssetColumnDO.builder()
                    // engName maps to field name
                    .columnName(dto.getEngName())
                    // cnName maps to field comment
                    .columnComment(dto.getCnName())
                    .columnType(dto.getColumnType())
                    .columnLength(dto.getColumnLength())
                    .columnScale(dto.getColumnScale())
                    .nullableFlag(dto.getNullableFlag())
                    .pkFlag(dto.getPkFlag())
                    .defaultValue(dto.getDefaultValue())
                    .build();
            assetColumns.add(assetColumn);
        }
        return assetColumns;
    }


    @Override
    public boolean creaDatasourceTeTable(DatasourceCreaTeTableReqDTO datasourceCreaTeTableReqDTO) {
        DbQueryProperty dbQueryProperty = new DbQueryProperty(datasourceCreaTeTableReqDTO.getDatasourceType()
                , datasourceCreaTeTableReqDTO.getIp(), datasourceCreaTeTableReqDTO.getPort(), datasourceCreaTeTableReqDTO.getDatasourceConfig());
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        if (!dbQuery.valid()) {
            throw new DataQueryException("db.error.connection.fail", "Database connection failed");
        }

        int tableStatus = dbQuery.generateCheckTableExistsSQL(dbQueryProperty, datasourceCreaTeTableReqDTO.getTableName());
        if (tableStatus > 0) {
            dbQuery.close();
            return false;
        }

        List<String> tableSQLList = dbQuery.generateCreateTableSQL(dbQueryProperty, datasourceCreaTeTableReqDTO.getTableName(), datasourceCreaTeTableReqDTO.getTableComment(), datasourceCreaTeTableReqDTO.getColumnsList());

        for (String sql : tableSQLList) {
            dbQuery.execute(sql);
        }
        dbQuery.close();

        return true;
    }


    @Override
    public boolean creaDatasourceTeTable(DbQuery dbQuery, DbQueryProperty dbQueryProperty, DatasourceCreaTeTableReqDTO datasourceCreaTeTableReqDTO) {

        int tableStatus = dbQuery.generateCheckTableExistsSQL(dbQueryProperty, datasourceCreaTeTableReqDTO.getTableName());
        if (tableStatus > 0) {
            return false;
        }

        List<String> tableSQLList = dbQuery.generateCreateTableSQL(dbQueryProperty, datasourceCreaTeTableReqDTO.getTableName(), datasourceCreaTeTableReqDTO.getTableComment(), datasourceCreaTeTableReqDTO.getColumnsList());

        for (String sql : tableSQLList) {
            dbQuery.execute(sql);
        }
        return true;
    }


    @Override
    public boolean creaDatasourceTeTableApi(DatasourceCreaTeTableReqDTO datasourceCreaTeTableReqDTO) {
        return this.creaDatasourceTeTable(datasourceCreaTeTableReqDTO);
    }


    @Override
    public boolean creaDatasourceTeTableApi(DbQuery dbQuery, DbQueryProperty dbQueryProperty, DatasourceCreaTeTableReqDTO creaTeTableReqDTO) {
        return this.creaDatasourceTeTable(dbQuery, dbQueryProperty, creaTeTableReqDTO);
    }


    @Override
    public boolean creaDatasourceTeTableListApi(DatasourceCreaTeTableListReqDTO datasourceCreaTeTableReqDTO) {

        DbQueryProperty dbQueryProperty = new DbQueryProperty(datasourceCreaTeTableReqDTO.getDatasourceType()
                , datasourceCreaTeTableReqDTO.getIp(), datasourceCreaTeTableReqDTO.getPort(), datasourceCreaTeTableReqDTO.getDatasourceConfig());
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        List<DatasourceCreaTeTableReqDTO> dtoList = datasourceCreaTeTableReqDTO.getDtoList();
        if (CollectionUtils.isNotEmpty(dtoList)) {
            for (DatasourceCreaTeTableReqDTO creaTeTableReqDTO : dtoList) {
                this.creaDatasourceTeTable(dbQuery, dbQueryProperty, creaTeTableReqDTO);
            }
        }
        return true;
    }

    @Override
    public PageResult<AttProjectRespDTO> getNoDppAddList(AttProjectReqDTO pageReqVO) {
        PageResult<AttProjectRespDTO> attProjectPage = attProjectApi.getAttProjectPage(pageReqVO);
        Map<Long, DaDatasourceProjectRelDO> datasourceProjectRelDOMap = new HashMap<>();
        if (pageReqVO.getDatasourceId() != null) {
            DaDatasourceProjectRelDO daDatasourceProjectRelDO = new DaDatasourceProjectRelDO();
            daDatasourceProjectRelDO.setDatasourceId(pageReqVO.getDatasourceId());
            List<DaDatasourceProjectRelDO> daDatasourceProjectRelList = daDatasourceProjectRelService.getDaDatasourceProjectRelList(daDatasourceProjectRelDO);
            datasourceProjectRelDOMap = daDatasourceProjectRelList.stream().collect(Collectors.toMap(DaDatasourceProjectRelDO::getProjectId, daDatasourceProjectRelDO1 -> daDatasourceProjectRelDO1));
        }
        for (Object row : attProjectPage.getRows()) {
            AttProjectRespDTO attProjectRespDTO = (AttProjectRespDTO) row;
            Boolean dppAssigned = datasourceProjectRelDOMap.get(attProjectRespDTO.getId()) != null && datasourceProjectRelDOMap.get(attProjectRespDTO.getId()).getDppAssigned();
            attProjectRespDTO.setDppAssigned(dppAssigned);
        }
        return attProjectPage;
    }

    @Override
    public List<DaDatasourceDO> getDaDatasourceDppNoKafka(DaDatasourcePageReqVO daDatasource) {
        List<Long> idList = new ArrayList<>();
        Map<Long, DaDatasourceProjectRelDO> datasourceProjectRelDOMap = new HashMap<>();
        if (StringUtils.isNotEmpty(daDatasource.getProjectCode())) {
            DaDatasourceProjectRelDO daDatasourceProjectRelDO = new DaDatasourceProjectRelDO();
            daDatasourceProjectRelDO.setProjectCode(daDatasource.getProjectCode());
            List<DaDatasourceProjectRelDO> daDatasourceProjectRelList = daDatasourceProjectRelService.getJoinProjectAndDatasource(daDatasourceProjectRelDO);
            if (daDatasourceProjectRelList.isEmpty()) {
                return new ArrayList<>();
            }
            datasourceProjectRelDOMap = daDatasourceProjectRelList.stream().collect(Collectors.toMap(DaDatasourceProjectRelDO::getDatasourceId, daDatasourceProjectRelDO1 -> daDatasourceProjectRelDO1));
            idList = datasourceProjectRelDOMap.keySet().stream().collect(Collectors.toList());
            daDatasource.setIdList(idList);
        }

        LambdaQueryWrapperX<DaDatasourceDO> queryWrapperX = new LambdaQueryWrapperX<>();
        queryWrapperX.inIfPresent(DaDatasourceDO::getId, idList)
                .neIfPresent(DaDatasourceDO::getDatasourceType, "Kafka")
                .likeIfPresent(DaDatasourceDO::getDatasourceType, daDatasource.getDatasourceType())
                .likeIfPresent(DaDatasourceDO::getDatasourceName, daDatasource.getDatasourceName());
        List<DaDatasourceDO> datasourceDOList = daDatasourceMapper.selectList(queryWrapperX);
        for (DaDatasourceDO daDatasourceDO : datasourceDOList) {
            DaDatasourceProjectRelDO datasourceProjectRelDO = datasourceProjectRelDOMap.get(daDatasourceDO.getId()) == null ? new DaDatasourceProjectRelDO() : datasourceProjectRelDOMap.get(daDatasourceDO.getId());
            if (idList.contains(daDatasourceDO.getId()) && !datasourceProjectRelDO.getDppAssigned()) {
                daDatasourceDO.setIsAdminAddTo(false);
                daDatasourceDO.setProjectName(datasourceProjectRelDO.getProjectName());
            }
        }
        return datasourceDOList;
    }

    @Override
    public tech.qiantong.qdata.common.database.core.PageResult<Map<String, Object>> executeSqlQuery(DaDatasourcePageReqVO daDatasource) {
        String sqlText = decryptSqlText(daDatasource.getSqlText(), daDatasource.getId());
        hiveNamespaces.getObject().authorizeQuery(daDatasource.getId(),sqlText);
        DbQuery dbQuery = getDbQuery(daDatasource);
        try {
            int[] paging = getPagingParameters(daDatasource);
            return dbQuery.queryByPage(sqlText, paging[0], paging[1]);
        } finally { dbQuery.close(); }
    }

    @SneakyThrows
    @Override
    public void exportSqlQueryResult(HttpServletResponse response, DaDatasourcePageReqVO daDatasource) {
        String sqlText = decryptSqlText(daDatasource.getSqlText(), daDatasource.getId());
        hiveNamespaces.getObject().authorizeQuery(daDatasource.getId(),sqlText);
        DbQuery dbQuery = getDbQuery(daDatasource);
        int[] paging = getPagingParameters(daDatasource);
        tech.qiantong.qdata.common.database.core.PageResult<Map<String, Object>> result;
        try { result = dbQuery.queryByPage(sqlText, paging[0], paging[1]); }
        finally { dbQuery.close(); }
        List<Map<String, Object>> dataList = result.getData();
        // Remove the ROW_ID field from each record
        dataList.forEach(map -> map.remove("ROW_ID"));
            String schemeName = "Export-Page-" + paging[2] + "-Data-" + IdUtil.simpleUUID();
        exportByList(response, dataList, schemeName);
    }

    @Override
    public List<DbColumn> sqlParse(String sourceId, String sqlText) {
        hiveNamespaces.getObject().authorizeQuery(Long.valueOf(sourceId),sqlText);
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sqlText);
        } catch (JSQLParserException e) {
            throw new ServiceException("da.error.sql.parse", "SQL syntax error, parsing failed");
        }

        // Query datasource info
        DaDatasourceRespDTO datasourceById = this.getDatasourceById(Long.valueOf(sourceId));
        DbQueryProperty dbQueryProperty = new DbQueryProperty(
                datasourceById.getDatasourceType(),
                datasourceById.getIp(),
                datasourceById.getPort(),
                datasourceById.getDatasourceConfig());
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        return dbQuery.getColumnsByQuerySql(sqlText);
    }

    /**
     * Decrypt the incoming SQL statement
     */
    private String decryptSqlText(String encryptedSqlText, Long datasourceId) {
        String sqlText = "";
        try {
//            sqlText =  encryptedSqlText;
            sqlText = AesEncryptUtil.desEncrypt(encryptedSqlText).trim();
        } catch (Exception e) {
            throw new ServiceException("da.error.decrypt.fail", "Failed to decrypt the execution statement. Please contact the administrator.");
        }

        if (sqlText == null || sqlText.isEmpty()) {
            throw new DataQueryException("db.error.sql.empty", "SQL statement cannot be empty");
        }

        var source = getDaDatasourceById(datasourceId);
        if (source == null) throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        String type = source.getDatasourceType().toLowerCase(Locale.ROOT);
        com.alibaba.druid.DbType dialect = type.equals("hive") ? com.alibaba.druid.DbType.hive
                : type.contains("postgres") || type.contains("kingbase") ? com.alibaba.druid.DbType.postgresql
                : type.contains("oracle") || type.startsWith("dm") ? com.alibaba.druid.DbType.oracle
                : type.contains("sqlserver") || type.contains("sql_server") ? com.alibaba.druid.DbType.sqlserver
                : com.alibaba.druid.DbType.mysql;
        tech.qiantong.qdata.common.database.core.ReadOnlySql.tables(sqlText, dialect);
        return sqlText.endsWith(";") ? sqlText.substring(0, sqlText.length() - 1).trim() : sqlText;
    }

    /**
     * Get a DbQuery object based on the datasource ID in the request
     */
    private DbQuery getDbQuery(DaDatasourcePageReqVO daDatasource) {
        DaDatasourceDO datasource = this.getDaDatasourceById(daDatasource.getId());
        if (datasource == null) {
            throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        }
        DbQueryProperty property = new DbQueryProperty(
                datasource.getDatasourceType(),
                datasource.getIp(),
                datasource.getPort(),
                datasource.getDatasourceConfig()
        );
        DbQuery dbQuery = dataSourceFactory.createDbQuery(property);
        try {
            if (!dbQuery.valid()) throw new DataQueryException("db.error.connection.fail", "Database connection failed");
            return dbQuery;
        } catch (RuntimeException failure) {
            try { dbQuery.close(); } catch (RuntimeException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    /**
     * Get paging parameters: returns an int array where
     * paging[0] is offset,
     * paging[1] is pageSize,
     * paging[2] is pageNum (used for displaying page number during export)
     */
    private int[] getPagingParameters(DaDatasourcePageReqVO daDatasource) {
        int pageSize = daDatasource.getPageSize() != null ? daDatasource.getPageSize() : 20;
        int pageNum = daDatasource.getPageNum() != null ? daDatasource.getPageNum() : 1;
        int offset = pageNum > 0 ? (pageNum - 1) * pageSize : 0;
        return new int[]{offset, pageSize, pageNum};
    }

    @SneakyThrows
    private static void exportByList(HttpServletResponse response, List<Map<String, Object>> dataList, String tableName) {
        if (dataList == null) {
            throw new ServiceException("da.error.form.notfound", "No form information is available.");
        }

        // Get all column names from the first row as the order
        Map<String, Object> firstRow = dataList.get(0);
        // Use a Set to ensure column name uniqueness
        List<String> order = new ArrayList<>(firstRow.keySet());

        //1. Create workbook
        XSSFWorkbook workbook = new XSSFWorkbook();

        // Header field font
        XSSFFont headFont = workbook.createFont();
        // Font height
        headFont.setFontHeightInPoints((short) 24);
        // Font
        headFont.setFontName("宋体");
        headFont.setBold(true);
        // Set cell style
        XSSFCellStyle headCellStyle = workbook.createCellStyle();
        headCellStyle.setFont(headFont);
        // Horizontal alignment: center
        headCellStyle.setAlignment(HorizontalAlignment.CENTER);
        // Vertical alignment: center
        headCellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headCellStyle.setWrapText(true);

        // Annotation/markup font
        XSSFFont font = workbook.createFont();
        // Font height
        font.setFontHeightInPoints((short) 11);
        // Font
        font.setFontName("宋体");

        // Column style
        XSSFCellStyle cellStyle = workbook.createCellStyle();
        cellStyle.setFont(font);
        // Horizontal alignment: center
        cellStyle.setAlignment(HorizontalAlignment.CENTER);
        // Vertical alignment: center
        cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        cellStyle.setWrapText(true);

        // 2. Create worksheet
        XSSFSheet sheet = workbook.createSheet(tableName);

        // Freeze the first row
        sheet.createFreezePane(0, 1, 0, 1);

        // 3. Create header row
        XSSFRow row = sheet.createRow(0);
        row.setHeight((short) (2 * 200));

        int index = 0;
        for (String key : order) {
            // Set default width
            sheet.setColumnWidth(index, 25 * 256);
            XSSFCell labelCell = row.createCell(index);
            labelCell.setCellStyle(cellStyle);
            labelCell.setCellValue(key);
            index++;
        }

        // 4. Data rows
        for (int i = 0; i < dataList.size(); i++) {
            Map<String, Object> map = dataList.get(i);
            // Data row
            XSSFRow dataRow = sheet.createRow(i + 1);
            dataRow.setHeight((short) (4 * 200));
            int columnIndex = 0;
            for (String key : order) {
                Object valueObj = map.get(key);
                String value = "";
                if (valueObj == null) {
                    value = "";
                } else if (valueObj instanceof java.math.BigDecimal decimal) {
                    value = tech.qiantong.qdata.common.database.core.BrowserQueryValues.decimalText(decimal);
                } else if (valueObj instanceof Date) {
                    // If it is a date type, convert to a fixed-format string
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    value = dateFormat.format((Date) valueObj);
                } else {
                    value = String.valueOf(valueObj);
                }
                XSSFCell labelCell = dataRow.createCell(columnIndex);
                labelCell.setCellStyle(cellStyle);
                labelCell.setCellValue(value);
                columnIndex++;
            }
        }

        if (response != null) {
            // 5. Output stream - write to output
            ByteArrayOutputStream baos = null;
            try {
                baos = new ByteArrayOutputStream();
                workbook.write(baos);
                baos.flush();
                byte[] aa = baos.toByteArray();
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(tableName + ".xls", "UTF-8"));
                response.setHeader("Content-Type", "application/vnd.ms-excel");
                response.setCharacterEncoding("UTF-8");
                response.getOutputStream().write(aa);
                response.setContentLength(aa.length);
            } catch (Exception e) {
                e.printStackTrace();
                throw new ServiceException("da.error.system", "System error.");
            } finally {
                if (response.getOutputStream() != null) {
                    response.getOutputStream().flush();
                }
                // 6. Flush the stream and release resources
                if (baos != null) {
                    baos.flush();
                    baos.close();
                }
                // Close resources
                workbook.close();
            }
        }
    }


    @Override
    public Boolean editDatasourceStatus(Long datasourceId, Long status) {
        if (!Objects.equals(status, 0L) && !Objects.equals(status, 1L)) {
            throw new IllegalArgumentException("Datasource status must be 0 or 1");
        }
        if (Objects.equals(status, 1L) && !clientTest(datasourceId)) {
            throw new ServiceException("da.error.datasource.test.required",
                    "The current data source has not passed testing and cannot be enabled");
        }
        boolean updated = this.update(Wrappers.lambdaUpdate(DaDatasourceDO.class)
                .eq(DaDatasourceDO::getId, datasourceId)
                .set(DaDatasourceDO::getValidFlag, status));
        if (!updated) {
            return false;
        }
        if (Objects.equals(status, 0L)) {
            evictDatasourceCacheFields(datasourceId);
        } else {
            DaDatasourceDO saved = daDatasourceMapper.selectById(datasourceId);
            if (saved == null || !Boolean.TRUE.equals(saved.getValidFlag())) {
                throw new ServiceException("da.error.datasource.detail.fail", "Datasource status was not updated");
            }
            stageDatasourceCacheRefresh(saved);
        }
        return true;
    }

    /**
     * @param id        datasource id
     * @param tableName table name
     * @return
     */
    @Override
    public List<DbColumn> getDbTableColumns(Long id, String tableName) {
        if (StringUtils.isEmpty(tableName)) {
            throw new DataQueryException("db.error.table.empty", "Table name cannot be empty");
        }

        DaDatasourceDO daDatasourceBy = this.getDaDatasourceById(id);
        if (daDatasourceBy == null) {
            throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        }

        DbQueryProperty dbQueryProperty = new DbQueryProperty(daDatasourceBy.getDatasourceType()
                , daDatasourceBy.getIp(), daDatasourceBy.getPort(), daDatasourceBy.getDatasourceConfig());
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        if (!dbQuery.valid()) {
            throw new DataQueryException("db.error.connection.fail", "Database connection failed");
        }
        List<DbColumn> tableColumns = dbQuery.getTableColumns(dbQueryProperty, tableName);
        dbQuery.close();

        return tableColumns;
    }

    @Override
    public DbTable getDbTable(Long datasourceId, String tableName) {
        DaDatasourceDO daDatasourceBy = this.getDaDatasourceById(datasourceId);
        if (daDatasourceBy == null) {
            throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        }

        DbQueryProperty dbQueryProperty = new DbQueryProperty(daDatasourceBy.getDatasourceType()
                , daDatasourceBy.getIp(), daDatasourceBy.getPort(), daDatasourceBy.getDatasourceConfig());
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        if (!dbQuery.valid()) {
            throw new DataQueryException("db.error.connection.fail", "Database connection failed");
        }
        List<DbTable> tables = dbQuery.getTables(dbQueryProperty);
        if (StringUtils.isNotEmpty(tableName)) {
            tables = tables.stream()
                    .filter(dbTable -> org.apache.commons.lang3.StringUtils.indexOfIgnoreCase(dbTable.getTableName(), tableName) > -1)
                    .collect(Collectors.toList());
        }
        dbQuery.close();
        if (tables.size() > 0) {
            return tables.get(0);
        }
        return null;
    }

    @Override
    public List<DbName> getDatabaseListByDatasourceId(Long id) {
        DaDatasourceRespDTO datasource = this.getDatasourceById(id);
        if (datasource == null) {
            throw new DataQueryException("db.error.datasource.detail.fail", "Failed to query datasource details");
        }
        if (DbType.HIVE.getDb().equalsIgnoreCase(datasource.getDatasourceType())) {
            return hiveNamespaces.getObject().databases(id).stream()
                    .map(database -> DbName.builder().dbName(database).level(1).totalLevels(1).build()).toList();
        }

        DbQueryProperty baseProperty = new DbQueryProperty(
                datasource.getDatasourceType(),
                datasource.getIp(),
                datasource.getPort(),
                datasource.getDatasourceConfig()
        );

        // 1. First get the top-level databases
        List<DbName> dbNames;
        DbQuery rootQuery = dataSourceFactory.createDbQuery(baseProperty);
        try {
            if (!rootQuery.valid()) {
                throw new DataQueryException("db.error.connection.fail", "Database connection failed");
            }
            dbNames = rootQuery.getDbNames(null);
        } finally {
            rootQuery.close();
        }

        if (CollectionUtils.isEmpty(dbNames)) {
            return dbNames;
        }

        // Single-level structure, return directly
        if (dbNames.get(0).getLevel() == 1 && dbNames.get(0).getTotalLevels() == 1) {
            return dbNames;
        }

        // 2. Iteratively load child databases
        for (DbName dbName : dbNames) {

            // Kingbase / PostgreSQL need to switch dbName
            DbQueryProperty childProperty = baseProperty;
            if (DbType.KINGBASE8.getDb().equals(baseProperty.getDbType())
                    || DbType.POSTGRE_SQL.getDb().equals(baseProperty.getDbType())) {

                childProperty = baseProperty.copy();
                childProperty.setDbName(dbName.getDbName());
            }

            DbQuery childQuery = dataSourceFactory.createDbQuery(childProperty);
            try {
                if (!childQuery.valid()) {
                    // Does not affect the whole, skip current node directly
                    continue;
                }
                List<DbName> children = childQuery.getDbNames(dbName);
                dbName.setChildren(children);
            } catch (Exception e) {
                // Optional: log the error
                // log.warn("Failed to get children for database {}", dbName.getDbName(), e);
            } finally {
                childQuery.close();
            }
        }

        return dbNames;
    }

    @Override
    public List<DaDatasourceRespDTO> getDatabaseListByIds(List<Long> ids) {
        List<DaDatasourceDO> daDatasourceDOS = daDatasourceMapper.selectBatchIds(ids);
        return BeanUtils.toBean(daDatasourceDOS, DaDatasourceRespDTO.class);
    }

    private List<DaDiscoveryTableDO> fetchDiscoveryTableList(DaDiscoveryTaskRespVO daDiscoveryTaskDO, Long daDiscoveryTaskLog) {
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Begin loading the discovery task table snapshot list from the local database; task ID: " + daDiscoveryTaskDO.getId());
        DaDiscoveryTablePageReqVO daDiscoveryTablePageReqVO = new DaDiscoveryTablePageReqVO();
        daDiscoveryTablePageReqVO.setTaskId(daDiscoveryTaskDO.getId());

        List<DaDiscoveryTableDO> result = iDaDiscoveryTableService.getDaDiscoveryTableList(daDiscoveryTablePageReqVO);
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Loaded the discovery task table snapshot list from the local database; table count: " + (result != null ? result.size() : 0));
        return result;
    }

    private List<DaDiscoveryColumnDO> fetchDaDiscoveryColumnDOList(DaDiscoveryTableDO matchedTable, Long daDiscoveryTaskLog) {
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Begin loading table column snapshot information from the local database; table ID: "
                        + matchedTable.getId() + ", task ID: " + matchedTable.getTaskId());

        DaDiscoveryColumnPageReqVO daDiscoveryTablePageReqVO = new DaDiscoveryColumnPageReqVO();
        daDiscoveryTablePageReqVO.setTaskId(matchedTable.getTaskId());
        daDiscoveryTablePageReqVO.setTableId(matchedTable.getId());

        List<DaDiscoveryColumnDO> result = iDaDiscoveryColumnService.getDaDiscoveryColumnList(daDiscoveryTablePageReqVO);
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Loaded table column snapshot information from the local database; column count: " + (result != null ? result.size() : 0));
        return result;
    }

    private List<DaDiscoveryTableDO> mapToMetadataTableList(List<DbTable> tables, Long taskId) {
        return tables.stream().map(table -> {
            DaDiscoveryTableDO metadataTable = new DaDiscoveryTableDO();
            metadataTable.setTaskId(taskId);
            metadataTable.setTableName(table.getTableName());
            metadataTable.setTableComment(table.getTableComment());
            return metadataTable;
        }).collect(Collectors.toList());
    }

    private void updateTableDataCount(DbQuery dbQuery, DaDiscoveryTableDO table, int fieldCount) {
        int dataCount = dbQuery.countNew(table.getTableName(), new HashMap<>());
        table.setDataCount((long) dataCount);
        table.setFieldCount((long) fieldCount);
        table.setCreateBy("超级管理员");
        table.setCreatorId(1L);
    }


    private int updateTableStatus(DaDiscoveryTableDO matchedTable, DaDiscoveryTableDO table, boolean modifiedTablesBoolean, Long daDiscoveryTaskLog) {
        if (modifiedTablesBoolean) {
            // 1: new, 2: modified, 3: deleted, 4: unchanged
            table.setChangeFlag("2");
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Updated table status to modified: " + matchedTable.getTableName());

            iDaDiscoveryTableService.updateDaDiscoveryTable(table);
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Update completed");
            return 1;
        } else {
            // 1: new, 2: modified, 3: deleted, 4: unchanged
            matchedTable.setChangeFlag("4");
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Updated table status to unchanged: " + matchedTable.getTableName());

            iDaDiscoveryTableService.updateDaDiscoveryTable(matchedTable);
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Update completed");
            return 0;
        }

    }
    private void saveNewTable(DaDiscoveryTableDO table, List<DbColumn> columns, DbQuery dbQuery, DbQueryProperty dbQueryProperty, Long daDiscoveryTaskLog) {
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Saving new table: " + table.getTableName());

        updateTableDataCount(dbQuery, table, columns.size());

        // 1: new, 2: modified, 3: deleted, 4: unchanged
        table.setChangeFlag("1");
        iDaDiscoveryTableService.createDaDiscoveryTable(table);

        if (CollUtil.isNotEmpty(columns)) {
            List<DaDiscoveryColumnDO> metadataColumnEntityList = columns.stream()
                    .map(column -> new DaDiscoveryColumnDO(table.getTaskId(), table.getId(), column))
                    .collect(Collectors.toList());
            metadataColumnEntityList.forEach(iDaDiscoveryColumnService::createDaDiscoveryColumn);
        }
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Save completed");
    }

    public DaDiscoveryTableDO findMatchedTable(DaDiscoveryTableDO table, List<DaDiscoveryTableDO> daDiscoveryTableDOList) {
        return daDiscoveryTableDOList.stream()
                .filter(existingTable -> existingTable.getTableName().equals(table.getTableName()) &&
                        existingTable.getTaskId().equals(table.getTaskId()))
                .findFirst()
                .orElse(null);  // Return null if no match found
    }

    private boolean isTableCommentModified(DaDiscoveryTableDO table, DaDiscoveryTableDO matchedTable) {
        return !StringUtils.equals(table.getTableComment(), matchedTable.getTableComment());
    }

    private List<DaDiscoveryColumnDO> generateMetadataColumnList(List<DbColumn> columns, DaDiscoveryTableDO matchedTable) {
        if (CollUtil.isEmpty(columns)) {
            return new ArrayList<>();
        }

        return columns.stream()
                .map(column -> new DaDiscoveryColumnDO(matchedTable.getTaskId(), matchedTable.getId(), column))
                .collect(Collectors.toList());
    }

    private boolean compareColumnsAndUpdate(List<DaDiscoveryColumnDO> metadataColumnEntityList, List<DaDiscoveryColumnDO> discoveryColumnDOList) {
        boolean modifiedTablesBoolean = false;

        for (DaDiscoveryColumnDO column : metadataColumnEntityList) {
            DaDiscoveryColumnDO matchedColumn = findMatchedColumn(column, discoveryColumnDOList);
            if (matchedColumn == null) {
                modifiedTablesBoolean = true;
                iDaDiscoveryColumnService.createDaDiscoveryColumn(column);
            } else if (!column.isEqual(matchedColumn)) {
                modifiedTablesBoolean = true;
                iDaDiscoveryColumnService.updateDaDiscoveryColumn(column);
            }
        }
        return modifiedTablesBoolean;
    }

    public DaDiscoveryColumnDO findMatchedColumn(DaDiscoveryColumnDO table, List<DaDiscoveryColumnDO> daDiscoveryTableDOList) {
        return daDiscoveryTableDOList.stream()
                .filter(existingTable -> StringUtils.equals(existingTable.getColumnName(), table.getColumnName()))
                .findFirst()
                .orElse(null);  // Return null if no match found
    }


    private boolean deleteUnmatchedColumns(List<DaDiscoveryColumnDO> discoveryColumnDOList, List<DaDiscoveryColumnDO> metadataColumnEntityList) {
        List<DaDiscoveryColumnDO> notInMetadataTable = findNotInDaDiscoveryColumn(discoveryColumnDOList, metadataColumnEntityList);
        if (CollectionUtils.isEmpty(notInMetadataTable)) {
            return false;
        }

        Collection<Long> idList = notInMetadataTable.stream()
                .map(DaDiscoveryColumnDO::getId)
                .collect(Collectors.toList());

        iDaDiscoveryColumnService.removeDaDiscoveryColumn(idList);
        return true;
    }

    public List<DaDiscoveryColumnDO> findNotInDaDiscoveryColumn(List<DaDiscoveryColumnDO> discoveryColumnDOList, List<DaDiscoveryColumnDO> metadataTableEntityList) {
        return discoveryColumnDOList.stream()
                .filter(table -> metadataTableEntityList.stream()
                        .noneMatch(existingTable -> {
                            return StringUtils.equals(existingTable.getColumnName(), table.getColumnName());
                        }))
                .collect(Collectors.toList()); // Return tables in daDiscoveryTableDOList that are not present in metadataTableEntityList
    }


    private int updateExistingTable(DbQuery dbQuery, DaDiscoveryTableDO matchedTable, DaDiscoveryTableDO table, List<DbColumn> columns, Long daDiscoveryTaskLog) {
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Updating table: " + table.getTableName());

        boolean modifiedTablesBoolean = false;
        // Query the snapshot field structure stored in the table
        List<DaDiscoveryColumnDO> discoveryColumnDOList = this.fetchDaDiscoveryColumnDOList(matchedTable, daDiscoveryTaskLog);
        discoveryColumnDOList = discoveryColumnDOList == null ? new ArrayList<>() : discoveryColumnDOList;

        if (isTableCommentModified(table, matchedTable)) {
            modifiedTablesBoolean = true;
        }

        List<DaDiscoveryColumnDO> metadataColumnEntityList = generateMetadataColumnList(columns, matchedTable);

        modifiedTablesBoolean |= compareColumnsAndUpdate(metadataColumnEntityList, discoveryColumnDOList);

        modifiedTablesBoolean |= deleteUnmatchedColumns(discoveryColumnDOList, metadataColumnEntityList);

        updateTableDataCount(dbQuery, table, columns.size());

        return updateTableStatus(matchedTable, table, modifiedTablesBoolean, daDiscoveryTaskLog);
    }

    private Map<String, Object> logSchemaModifications(DbQueryProperty dbQueryProperty, DbQuery dbQuery, DaDiscoveryTaskRespVO daDiscoveryTaskById, Long daDiscoveryTaskLog) {
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Starting schema modification operation, task ID: " + daDiscoveryTaskById.getId());

        int newTables = 0;
        int modifiedTables = 0;
        int deletedTables = 0;
        int totalTables = 0;

        List<DaDiscoveryTableDO> daDiscoveryTableDOList = this.fetchDiscoveryTableList(daDiscoveryTaskById, daDiscoveryTaskLog);
        daDiscoveryTableDOList = daDiscoveryTableDOList == null ? new ArrayList<>() : daDiscoveryTableDOList;

        List<DbTable> tables = dbQuery.getTables(dbQueryProperty);
        List<DaDiscoveryTableDO> metadataTableEntityList = new ArrayList<>();
        if (CollUtil.isNotEmpty(tables)) {
            totalTables = tables.size();
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Fetching table column count info from datasource in real-time: " + totalTables);

            metadataTableEntityList = mapToMetadataTableList(tables, daDiscoveryTaskById.getId());
            if (CollUtil.isNotEmpty(metadataTableEntityList)) {
                for (DaDiscoveryTableDO table : metadataTableEntityList) {
                    DaDiscoveryTableDO matchedTable = findMatchedTable(table, daDiscoveryTableDOList);
                    iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Processing table: " + table.getTableName());

                    List<DbColumn> columns = dbQuery.getTableColumns(dbQueryProperty, table.getTableName());

                    columns = columns == null ? new ArrayList<>() : columns;
                    iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Fetching column count info from datasource in real-time: " + columns.size());
                    if (matchedTable == null) {
                        newTables++;
                        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "New table discovered, table: " + table.getTableName() + ", starting to save");
                        saveNewTable(table, columns, dbQuery, dbQueryProperty, daDiscoveryTaskLog);
                    } else {
                        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Checking table [" + table.getTableName() + "] configuration info in database");
                        // Whether to ignore; 0: no, 1: yes
                        String ignoreFlag = matchedTable.getIgnoreFlag();
                        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Checking table [" + table.getTableName() + "] configuration info in database, found ignoreFlag: " + ignoreFlag);
                        if (StringUtils.equals("1", ignoreFlag)) {
                            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Checking table [" + table.getTableName() + "] configuration info in database, found config: ignored. Scanning of this table ends!");
                            continue;
                        }
                        table.setId(matchedTable.getId());
                        modifiedTables += updateExistingTable(dbQuery, matchedTable, table, columns, daDiscoveryTaskLog);
                        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Existing table updated, table: " + table.getTableName());

                    }
                }
            }
        }

        deletedTables = deleteUnmatchedTables(daDiscoveryTableDOList, metadataTableEntityList, daDiscoveryTaskLog);
        String executionTime = DateUtils.getExecutionTime();

        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Schema modification operation completed, total tables: " + totalTables + ". Among them, new tables: " + newTables + ", modified tables: " + modifiedTables + ", deleted tables: " + deletedTables);

        Map<String, Object> map = new HashMap<>();
        map.put("taskName", daDiscoveryTaskById.getName());
        map.put("executionTime", executionTime);
        map.put("totalTables", totalTables);
        map.put("newTables", newTables);
        map.put("modifiedTables", modifiedTables);
        map.put("deletedTables", deletedTables);

        daDiscoveryTaskById.setLastTableCount((long) (newTables + modifiedTables + deletedTables));
        return map;
    }


    public List<DaDiscoveryTableDO> findNotInMetadataTable(List<DaDiscoveryTableDO> daDiscoveryTableDOList, List<DaDiscoveryTableDO> metadataTableEntityList) {
        return daDiscoveryTableDOList.stream()
                .filter(table -> metadataTableEntityList.stream()
                        .noneMatch(existingTable -> existingTable.getTableName().equals(table.getTableName()) &&
                                existingTable.getTaskId().equals(table.getTaskId())))
                .collect(Collectors.toList()); // Return tables in daDiscoveryTableDOList that are not present in metadataTableEntityList
    }

    private int deleteUnmatchedTables(List<DaDiscoveryTableDO> daDiscoveryTableDOList, List<DaDiscoveryTableDO> metadataTableEntityList, Long daDiscoveryTaskLog) {
        List<DaDiscoveryTableDO> notInMetadataTable = findNotInMetadataTable(daDiscoveryTableDOList, metadataTableEntityList);
        if (CollectionUtils.isEmpty(notInMetadataTable)) return 0;

        for (DaDiscoveryTableDO daDiscoveryTableDO : notInMetadataTable) {
            daDiscoveryTableDO.setUpdateBy("超级管理员");
            daDiscoveryTableDO.setUpdatorId(1L);
            // 1: new, 2: modified, 3: deleted, 4: unchanged
            daDiscoveryTableDO.setChangeFlag("3");
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Deleting unmatched table: " + daDiscoveryTableDO.getTableName());
            iDaDiscoveryTableService.updateDaDiscoveryTable(daDiscoveryTableDO);
        }

        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Deletion completed");
        return notInMetadataTable.size();
    }



    private Map<String, Object> runJobTableSchemaUpdates(DaDiscoveryTaskRespVO daDiscoveryTaskById, Long daDiscoveryTaskLog) {

        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Task execution - getting datasource details for discovery task by datasource ID");
        DaDatasourceDO daDatasourceBy = this.getDaDatasourceById(daDiscoveryTaskById.getDatasourceId());
        if (daDatasourceBy == null) {
            throw new DataQueryException("db.error.task.datasource.detail",
                    "Task execution - failed to get datasource details for discovery task by datasource ID!");
        }
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Task execution - successfully got datasource details for discovery task by datasource ID");
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Task execution - establishing real-time datasource connection by datasource connection info");
        DbQueryProperty dbQueryProperty = new DbQueryProperty(daDatasourceBy.getDatasourceType()
                , daDatasourceBy.getIp(), daDatasourceBy.getPort(), daDatasourceBy.getDatasourceConfig());
        DbQuery dbQuery = dataSourceFactory.createDbQuery(dbQueryProperty);
        if (!dbQuery.valid()) {
            throw new DataQueryException("da.error.datasource.realtime.connection.fail",
                    "Failed to establish a real-time data source connection from the task connection information");
        }
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Task execution - Established a live data source connection from the data source connection details");

        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Task execution - Begin retrieving live database information through the data source connection");
        try {
            Map<String, Object> map = logSchemaModifications(dbQueryProperty, dbQuery, daDiscoveryTaskById, daDiscoveryTaskLog);
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                    "Task execution - Finished retrieving live database information through the data source connection");
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                    "Task execution - Retrieved information map: " + map);
            iDaDiscoveryTaskService.updateDaDiscoveryTask(daDiscoveryTaskById);
            return map;
        } catch (Exception e) {
            throw e;
        } finally {
            dbQuery.close();
        }
    }


    /**
     * Detect table schema updates
     *
     * @param id
     */
    @Override
    public void detectTableSchemaUpdates(Long id) {
        String key = "detectTableSchemaUpdates-" + id;
        String status = redisService.get(key);
        if (StringUtils.isEmpty(status) && StringUtils.equals("1", status)) {
            throw new ServiceException("da.error.task.running", "A previous task is still running. Please try again later.");
        }
        DaDiscoveryTaskRespVO daDiscoveryTaskById = iDaDiscoveryTaskService.getDaDiscoveryTaskById(id);
        if (daDiscoveryTaskById == null) {
            throw new DataQueryException("db.error.task.discovery.detail",
                    "Task execution: Failed to get discovery task details by task ID!");
        }
        redisService.set(key, "1", 1200);
        // Create a log record table
        DaDiscoveryTaskLogSaveReqVO createReqVO = new DaDiscoveryTaskLogSaveReqVO();
        Date executionDate = DateUtils.getExecutionDate();
        createReqVO.setStartTime(executionDate);
        createReqVO.populateFromTask(daDiscoveryTaskById);
        Long daDiscoveryTaskLog = iDaDiscoveryTaskLogService.createDaDiscoveryTaskLog(createReqVO);
        createReqVO.setId(daDiscoveryTaskLog);
        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                "Task execution - Loaded discovery task details by task code");

        iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Task execution started");

        try {
            daDiscoveryTaskById.setLastExecuteTime(executionDate);
            //
            Map<String, Object> map = runJobTableSchemaUpdates(daDiscoveryTaskById, daDiscoveryTaskLog);

            int newTables = MapUtils.getIntValue(map, "newTables");
            int modifiedTables = MapUtils.getIntValue(map, "modifiedTables");
            int deletedTables = MapUtils.getIntValue(map, "deletedTables");
            createReqVO.setNewTableCount((long) newTables);
            createReqVO.setModifiedTableCount((long) modifiedTables);
            createReqVO.setDeletedTableCount((long) deletedTables);

            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                    "Task execution - Begin sending an in-app notification for this task");
            iSysMessageService.sendDbChangeMessage(daDiscoveryTaskById.getContactId(), map);
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog,
                    "Task execution - Finished sending the in-app notification for this task");
            createReqVO.setStatus("2");
        } catch (Exception e) {
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Task execution failed");
            createReqVO.setStatus("3");
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, e.getMessage().toString());
            redisService.set(key, "3", 300);
        } finally {
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "FINALIZE_SESSION");
            createReqVO.setPath("");
            iDaDiscoveryLogBodyService.taskLogAppend(daDiscoveryTaskLog, "Task execution finished");
            createReqVO.setEndTime(DateUtils.getExecutionDate());
            iDaDiscoveryTaskLogService.updateDaDiscoveryTaskLog(createReqVO);
            redisService.set(key, "2", 300);
        }
    }
}
