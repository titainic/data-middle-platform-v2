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
import tech.qiantong.qdata.common.core.domain.entity.SysDept;
import tech.qiantong.qdata.common.core.domain.entity.SysRole;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.core.page.TableDataInfo;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectUserRelPageReqVO;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectUserRelRespVO;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectUserRelSaveReqVO;
import tech.qiantong.qdata.module.att.convert.project.AttProjectUserRelConvert;
import tech.qiantong.qdata.module.att.dal.dataobject.project.AttProjectUserRelDO;
import tech.qiantong.qdata.module.att.service.project.IAttProjectUserRelService;
import tech.qiantong.qdata.module.system.domain.SysUserRole;
import tech.qiantong.qdata.module.system.service.ISysDeptService;
import tech.qiantong.qdata.module.system.service.ISysRoleService;
import tech.qiantong.qdata.module.system.service.ISysUserService;
import tech.qiantong.qdata.security.web.service.SysPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;

/**
 * Project-User Relationship Controller
 *
 * @author qdata
 * @date 2025-02-11
 */
@Tag(name = "项目与用户关联关系")
@RestController
@RequestMapping("/att/projectUserRel")
@Validated
public class AttProjectUserRelController extends BaseController {
    @Resource private tech.qiantong.qdata.module.att.service.project.ProjectAuthority authority;
    @Resource
    private IAttProjectUserRelService attProjectUserRelService;

    @Resource
    private ISysRoleService roleService;

    @Resource
    private TokenService tokenService;

    @Resource
    private SysPermissionService permissionService;

    @Resource
    private ISysUserService userService;

    @Resource
    private ISysDeptService deptService;

    @Operation(summary = "查询项目与用户关联关系列表")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<AttProjectUserRelRespVO>> list(AttProjectUserRelPageReqVO attProjectUserRel) {
        attProjectUserRel.setProjectId(authority.resolve(attProjectUserRel.getProjectId()));authority.require(attProjectUserRel.getProjectId(),"att:projectUserRel:list");
        PageResult<AttProjectUserRelDO> page = attProjectUserRelService.getAttProjectUserRelPage(attProjectUserRel);
        return CommonResult.success(BeanUtils.toBean(page, AttProjectUserRelRespVO.class));
    }

    @Operation(summary = "导出项目与用户关联关系列表")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:export')")
    @Log(title = "log.op.title.att.project.user.rel", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AttProjectUserRelPageReqVO exportReqVO) {
        exportReqVO.setProjectId(authority.resolve(exportReqVO.getProjectId()));authority.require(exportReqVO.getProjectId(),"att:projectUserRel:export");
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<AttProjectUserRelDO> list = (List<AttProjectUserRelDO>) attProjectUserRelService.getAttProjectUserRelPage(exportReqVO).getRows();
        ExcelUtil<AttProjectUserRelRespVO> util = new ExcelUtil<>(AttProjectUserRelRespVO.class);
        util.exportExcel(response, AttProjectUserRelConvert.INSTANCE.convertToRespVOList(list), "应用管理数据");
    }

    @Operation(summary = "导入项目与用户关联关系列表")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:import')")
    @Log(title = "log.op.title.att.project.user.rel", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<AttProjectUserRelRespVO> util = new ExcelUtil<>(AttProjectUserRelRespVO.class);
        List<AttProjectUserRelRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){var request=BeanUtils.toBean(row,AttProjectUserRelSaveReqVO.class);authority.memberRequest(request,row.getId()!=null,false);}
        String operName = getUsername();
        String message = attProjectUserRelService.importAttProjectUserRel(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取项目与用户关联关系详细信息")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<AttProjectUserRelRespVO> getInfo(@PathVariable("id") Long id) {
        var membership=authority.member(id);authority.require(((Number)membership.get("project_id")).longValue(),"att:projectUserRel:query");
        AttProjectUserRelDO attProjectUserRelDO = attProjectUserRelService.getAttProjectUserRelById(id);
        return CommonResult.success(BeanUtils.toBean(attProjectUserRelDO, AttProjectUserRelRespVO.class));
    }

    @Operation(summary = "获取项目与用户关联关系详细信息包括角色信息")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:query')")
    @GetMapping(value = "/roleUser/{id}")
    public CommonResult<AttProjectUserRelRespVO> getRoleUser(@PathVariable("id") Long id) {
        var membership=authority.member(id);authority.require(((Number)membership.get("project_id")).longValue(),"att:projectUserRel:query");
        AttProjectUserRelRespVO attProjectUserRelDO = attProjectUserRelService.getRoleUser(id);
        return CommonResult.success(attProjectUserRelDO);
    }

    @Operation(summary = "新增项目与用户关联关系")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:add')")
    @Log(title = "log.op.title.att.project.user.rel", businessType = BusinessType.INSERT)
    @PostMapping
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Long> add(@Valid @RequestBody AttProjectUserRelSaveReqVO attProjectUserRel) {
        authority.memberRequest(attProjectUserRel,false,false);
        attProjectUserRel.setCreatorId(getUserId());
        attProjectUserRel.setCreateBy(getNickName());
        attProjectUserRel.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(attProjectUserRelService.createAttProjectUserRel(attProjectUserRel));
    }

    @Operation(summary = "新增项目与用户关联关系")
    @PreAuthorize("@ss.hasPermi('att:project:project:add')")
    @Log(title = "log.op.title.att.project", businessType = BusinessType.INSERT)
    @PostMapping("/addUserListAndRoleList")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Boolean> addUserListAndRoleList(@Valid @RequestBody AttProjectUserRelSaveReqVO attProjectUserRel) {
        authority.memberRequest(attProjectUserRel,false,true);
        attProjectUserRel.setCreatorId(getUserId());
        attProjectUserRel.setCreateBy(getNickName());
        attProjectUserRel.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(attProjectUserRelService.createUserListAndRoleList(attProjectUserRel));
    }

    @Operation(summary = "修改项目与用户关联关系")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:edit')")
    @Log(title = "log.op.title.att.project.user.rel", businessType = BusinessType.UPDATE)
    @PutMapping
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Integer> edit(@Valid @RequestBody AttProjectUserRelSaveReqVO attProjectUserRel) {
        authority.memberRequest(attProjectUserRel,true,false);
        attProjectUserRel.setUpdatorId(getUserId());
        attProjectUserRel.setUpdateBy(getNickName());
        attProjectUserRel.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(attProjectUserRelService.updateAttProjectUserRel(attProjectUserRel));
    }

    @Operation(summary = "修改项目与用户关联关系")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:edit')")
    @Log(title = "log.op.title.att.project.user.rel", businessType = BusinessType.UPDATE)
    @PutMapping("/editUserListAndRoleList")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Integer> editUserListAndRoleList(@Valid @RequestBody AttProjectUserRelSaveReqVO attProjectUserRel) {
        authority.memberRequest(attProjectUserRel,true,true);
        attProjectUserRel.setUpdatorId(getUserId());
        attProjectUserRel.setUpdateBy(getNickName());
        attProjectUserRel.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(attProjectUserRelService.updateUserListAndRoleList(attProjectUserRel));
    }

    @Operation(summary = "删除项目与用户关联关系")
    @PreAuthorize("@ss.hasPermi('att:projectUserRel:remove')")
    @Log(title = "log.op.title.att.project.user.rel", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        authority.memberRemoval(ids);
        return CommonResult.toAjax(attProjectUserRelService.removeAttProjectUserRel(Arrays.asList(ids)));
    }

    @PreAuthorize("@ss.hasPermi('att:project:role:list')")
    @GetMapping("/role/list")
    public TableDataInfo list(SysRole role) {
        role.setProjectId(authority.resolve(role.getProjectId()));authority.require(role.getProjectId(),"att:project:role:list");
        startPage();
        List<SysRole> list = roleService.selectRoleList(role);
        return getDataTable(list);
    }

    @Log(title = "log.op.title.att.role", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('att:project:role:export')")
    @PostMapping("/role/export")
    public void export(HttpServletResponse response, SysRole role) {
        role.setProjectId(authority.resolve(role.getProjectId()));authority.require(role.getProjectId(),"att:project:role:export");
        List<SysRole> list = roleService.selectRoleList(role);
        ExcelUtil<SysRole> util = new ExcelUtil<SysRole>(SysRole.class);
        util.exportExcel(response, list, "角色数据");
    }

    /**
     * Get detail info by role ID
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:query')")
    @GetMapping(value = "/role/{roleId}")
    public AjaxResult getRoleInfo(@PathVariable Long roleId) {
        authority.role(roleId,false,"att:project:role:query");
        roleService.checkRoleDataScope(roleId);
        return success(roleService.selectRoleById(roleId));
    }

    /**
     * Add role
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:add')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.INSERT)
    @PostMapping("/role")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult add(@Validated @RequestBody SysRole role) {
        authority.roleSave(role,true);
        role.setRoleName(role.getRoleName().trim());
        if (!roleService.checkRoleNameUnique(role)) {
            return error(MessageUtils.messageWithFallback("att.error.role.name.exists", "角色名称已存在，请修改"));
        } else if (!roleService.checkRoleKeyUnique(role)) {
            return error(MessageUtils.messageWithFallback("att.error.role.key.exists", "权限字符已存在，请修改"));
        }
        role.setCreateBy(getUsername());
        return toAjax(roleService.insertRole(role));

    }

    /**
     * Update and save role
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:edit')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.UPDATE)
    @PutMapping("/role")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult edit(@Validated @RequestBody SysRole role) {
        authority.roleSave(role,false);
        role.setRoleName(role.getRoleName().trim());
        roleService.checkRoleAllowed(role);
        roleService.checkRoleDataScope(role.getRoleId());
        if (!roleService.checkRoleNameUnique(role)) {
            return error(MessageUtils.messageWithFallback("att.error.role.name.exists", "角色名称已存在，请修改"));
        } else if (!roleService.checkRoleKeyUnique(role)) {
            return error(MessageUtils.messageWithFallback("att.error.role.key.exists", "权限字符已存在，请修改"));
        }
        role.setUpdateBy(getUsername());

        if (roleService.updateRole(role) > 0) {
            // Update cached user permissions
            LoginUser loginUser = getLoginUser();
            if (StringUtils.isNotNull(loginUser.getUser()) && !loginUser.getUser().isAdmin()) {
                loginUser.setPermissions(permissionService.getMenuPermission(loginUser.getUser()));
                loginUser.setUser(userService.selectUserByUserName(loginUser.getUser().getUserName()));
                tokenService.setLoginUser(loginUser);
            }
            return success();
        }
        return error(MessageUtils.messageWithFallback("att.error.role.update.fail",
                "修改角色'" + role.getRoleName() + "'失败，请联系管理员", role.getRoleName()));
    }

    /**
     * Update and save data permissions
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:edit')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.UPDATE)
    @PutMapping("/role/dataScope")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult dataScope(@RequestBody SysRole role) {
        authority.roleIdentity(role,"att:project:role:edit");
        roleService.checkRoleAllowed(role);
        roleService.checkRoleDataScope(role.getRoleId());
        return toAjax(roleService.authDataScope(role));
    }

    /**
     * Update status
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:edit')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.UPDATE)
    @PutMapping("/role/changeStatus")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult changeStatus(@RequestBody SysRole role) {
        authority.roleIdentity(role,"att:project:role:edit");
        roleService.checkRoleAllowed(role);
        roleService.checkRoleDataScope(role.getRoleId());
        if ("1".equals(role.getStatus())) {
            int count = roleService.countUserRoleByRoleId(role.getRoleId());
            if (count > 0) {
                throw new ServiceException("att.error.role.assigned.disable",
                        "该角色已分配给" + count + "名成员，不能直接停用", count);
            }
        }
        role.setUpdateBy(getUsername());
        return toAjax(roleService.updateRoleStatus(role));
    }

    /**
     * Delete role
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:remove')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.DELETE)
    @DeleteMapping("/role/{roleIds}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult removeRole(@PathVariable Long[] roleIds) {
        java.util.Set<Long> projects=new java.util.HashSet<>();for(Long id:Arrays.stream(roleIds).sorted().toList())projects.add(((Number)authority.role(id,true,"att:project:role:remove").get("project_id")).longValue());if(projects.size()!=1)throw new org.springframework.security.access.AccessDeniedException("角色批量操作必须属于同一项目");
        return toAjax(roleService.deleteRoleByIds(roleIds));
    }

    /**
     * Get role selection list
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:query')")
    @GetMapping("/role/optionselect")
    public AjaxResult optionselect(@RequestParam(required=false) Long projectId) {
        Long project=authority.resolve(projectId);authority.require(project,"att:project:role:query");SysRole query=new SysRole();query.setProjectId(project);return success(roleService.selectRoleList(query));
    }

    /**
     * Query assigned user role list
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:list')")
    @GetMapping("/role/authUser/allocatedList")
    public TableDataInfo allocatedList(SysUser user) {
        var role=authority.role(user.getRoleId(),false,"att:project:role:list");user.setProjectMemberIds(authority.projectMemberIds(((Number)role.get("project_id")).longValue()));
        startPage();
        List<SysUser> list = userService.selectAllocatedList(user);
        return getDataTable(list);
    }

    /**
     * Query unassigned user role list
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:list')")
    @GetMapping("/role/authUser/unallocatedList")
    public TableDataInfo unallocatedList(SysUser user) {
        var role=authority.role(user.getRoleId(),false,"att:project:role:list");user.setProjectMemberIds(authority.projectMemberIds(((Number)role.get("project_id")).longValue()));
        startPage();
        List<SysUser> list = userService.selectUnallocatedList(user);
        return getDataTable(list);
    }

    /**
     * Cancel authorized user
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:edit')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.GRANT)
    @PutMapping("/role/authUser/cancel")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult cancelAuthUser(@RequestBody SysUserRole userRole) {
        authority.roleUsers(userRole.getRoleId(),new Long[]{userRole.getUserId()},true);
        return toAjax(roleService.deleteAuthUser(userRole));
    }

    /**
     * Batch cancel authorized users
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:edit')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.GRANT)
    @PutMapping("/role/authUser/cancelAll")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult cancelAuthUserAll(Long roleId, Long[] userIds) {
        authority.roleUsers(roleId,userIds,true);
        return toAjax(roleService.deleteAuthUsers(roleId, userIds));
    }

    /**
     * Batch select users for authorization
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:edit')")
    @Log(title = "log.op.title.att.role", businessType = BusinessType.GRANT)
    @PutMapping("/role/authUser/selectAll")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public AjaxResult selectAuthUserAll(Long roleId, Long[] userIds) {
        authority.roleUsers(roleId,userIds,false);
        roleService.checkRoleDataScope(roleId);
        return toAjax(roleService.insertAuthUsers(roleId, userIds));
    }

    /**
     * Get corresponding role department tree list
     */
    @PreAuthorize("@ss.hasPermi('att:project:role:query')")
    @GetMapping(value = "/role/deptTree/{roleId}")
    public AjaxResult deptTree(@PathVariable("roleId") Long roleId) {
        authority.role(roleId,false,"att:project:role:query");
        AjaxResult ajax = AjaxResult.success();
        ajax.put("checkedKeys", deptService.selectDeptListByRoleId(roleId));
        ajax.put("depts", deptService.selectDeptTreeList(new SysDept()));
        return ajax;
    }

}
