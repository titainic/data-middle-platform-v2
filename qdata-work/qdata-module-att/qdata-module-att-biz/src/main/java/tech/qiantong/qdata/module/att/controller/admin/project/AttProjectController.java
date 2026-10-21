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

package tech.qiantong.qdata.module.att.controller.admin.project;

import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.core.page.TableDataInfo;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectPageReqVO;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectRespVO;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectSaveReqVO;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttSysUserReqVO;
import tech.qiantong.qdata.module.att.dal.dataobject.project.AttProjectDO;
import tech.qiantong.qdata.module.att.service.project.IAttProjectService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;

/**
 * Project Controller
 *
 * @author shu
 * @date 2025-01-20
 */
@Tag(name = "项目")
@RestController
@RequestMapping("/att/project")
@Validated
public class AttProjectController extends BaseController {
    @Resource private tech.qiantong.qdata.module.att.service.project.ProjectAuthority authority;
    @Resource
    private IAttProjectService attProjectService;

    @Operation(summary = "查询项目列表")
    @PreAuthorize("@ss.hasPermi('att:project:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<AttProjectRespVO>> list(AttProjectPageReqVO attProject) {
        attProject.setAccessibleProjectIds(authority.projects());
        PageResult<AttProjectDO> page = attProjectService.getAttProjectPage(attProject);
        var result=BeanUtils.toBean(page,AttProjectRespVO.class);for(var row:result.getRows())row.setCanEdit(authority.canEdit(row.getId()));return CommonResult.success(result);
    }

    @Operation(summary = "查询当前用户所属的项目列表")
    @GetMapping("/currentUser/list")
    public CommonResult<List<AttProjectRespVO>> currentUser() {
        List<AttProjectDO> list = attProjectService.getCurrentUserList(getUserId());
        return CommonResult.success(BeanUtils.toBean(list, AttProjectRespVO.class));
    }

    /**
     * Get user list excluding users already in the current project
     */
    @PreAuthorize("@ss.hasPermi('att:project:list')")
    @PostMapping("/noProjectUser/list")
    public TableDataInfo list(AttSysUserReqVO user) {
        authority.manage(user.getProjectId(),"att:projectUserRel:add","att:project:project:add");
        List<SysUser> list = attProjectService.selectNoProjectUserList(user);
        return getDataTable(list);
    }

    @Operation(summary = "导入项目列表")
    @PreAuthorize("@ss.hasPermi('att:project:import')")
    @Log(title = "log.op.title.att.project", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<AttProjectRespVO> util = new ExcelUtil<>(AttProjectRespVO.class);
        List<AttProjectRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){if(row.getId()!=null)authority.edit(BeanUtils.toBean(row,AttProjectSaveReqVO.class));else if(!tech.qiantong.qdata.common.utils.SecurityUtils.hasPermi("att:project:add"))throw new org.springframework.security.access.AccessDeniedException("没有新增项目权限");}
        String operName = getUsername();
        String message = attProjectService.importAttProject(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取项目详细信息")
    @PreAuthorize("@ss.hasPermi('att:project:query')")
    @GetMapping(value = "/{ID:\\d+}")
    public CommonResult<AttProjectRespVO> getInfo(@PathVariable("ID") Long ID) {
        authority.require(ID,"att:project:query");
        AttProjectDO attProjectDO = attProjectService.getAttProjectById(ID);
        var result=BeanUtils.toBean(attProjectDO,AttProjectRespVO.class);if(result!=null)result.setCanEdit(authority.canEdit(result.getId()));return CommonResult.success(result);
    }

    @Operation(summary = "获取当前用户是非具备用户添加和项目管理员")
    @PreAuthorize("@ss.hasPermi('att:project:query')")
    @GetMapping(value = "/addUserAndProject/{id}")
    public CommonResult<JSONObject> addUserAndProjectIsOk(@PathVariable("id") Long id) {
        authority.require(id,"att:project:query");
        return CommonResult.success(attProjectService.addUserAndProjectIsOk(getUserId(), id));
    }

    @Operation(summary = "新增项目")
    @PreAuthorize("@ss.hasPermi('att:project:add')")
    @Log(title = "log.op.title.att.project", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody AttProjectSaveReqVO attProject) {
        if(attProject.getId()!=null)throw new org.springframework.security.access.AccessDeniedException("新增项目不能复用历史标识");
        attProject.setCreatorId(getUserId());
        attProject.setCreateBy(getNickName());
        attProject.setCreateTime(DateUtil.date());
        Long serviceAttProject = attProjectService.createAttProject(attProject);
        if (serviceAttProject == -1) {
            return CommonResult.error(serviceAttProject.intValue(),
                    MessageUtils.messageWithFallback("att.error.project.create.scheduler.or.exists",
                            "创建失败，请检查海豚调度器是否宕机或者是否存在该数据!"));
        }
        if (serviceAttProject == -2) {
            return CommonResult.error(serviceAttProject.intValue(),
                    MessageUtils.messageWithFallback("att.error.project.create.fail", "创建失败!"));
        }
        return CommonResult.toAjax(serviceAttProject);
    }

    @Operation(summary = "修改项目")
    @PreAuthorize("@ss.hasPermi('att:project:edit')")
    @Log(title = "log.op.title.att.project", businessType = BusinessType.UPDATE)
    @PutMapping
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Integer> edit(@Valid @RequestBody AttProjectSaveReqVO attProject) {
        authority.edit(attProject);
        attProject.setUpdatorId(getUserId());
        attProject.setUpdateBy(getNickName());
        attProject.setUpdateTime(DateUtil.date());
        int i = attProjectService.updateAttProject(attProject);
        if (i == -1) {
            return CommonResult.error(i,
                    MessageUtils.messageWithFallback("att.error.project.update.fail", "修改失败！"));
        }
        return CommonResult.toAjax(i);
    }

    @Operation(summary = "修改项目状态")
    @PreAuthorize("@ss.hasPermi('att:project:edit')")
    @GetMapping(value = "/editProjectStatus/{id}/{status}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult editProjectStatus(@PathVariable Long id, @PathVariable Long status) {
        authority.manage(id,"att:project:edit");if(status==null || (status!=0L && status!=1L))throw new IllegalArgumentException("项目状态无效");
        Boolean isOk = attProjectService.editProjectStatus(id, status);
        if (!isOk) {
            return AjaxResult.error(MessageUtils.messageWithFallback("att.error.project.status.update.fail",
                    "任务状态修改失败，请联系系统管理员"));
        }
        return AjaxResult.success(MessageUtils.messageWithFallback("att.success.project.update", "修改成功"));
    }

    @Operation(summary = "删除项目")
    @PreAuthorize("@ss.hasPermi('att:project:remove')")
    @Log(title = "log.op.title.att.project", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:Arrays.stream(ids).sorted().toList())authority.manage(id,"att:project:remove");
        int project = attProjectService.removeAttProject(Arrays.asList(ids));
        if (project == -2) {
            // 控制器是外层事务边界，显式回滚以保留 -2 对应的业务响应。
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return CommonResult.error(500,
                    MessageUtils.messageWithFallback("att.error.project.delete.scheduler.fail",
                            "删除项目失败，DolphinScheduler 未确认删除成功，本地变更已回滚；请检查调度服务、项目状态及权限"));
        }
        return CommonResult.toAjax(project);
    }

}
