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

package tech.qiantong.qdata.module.att.dal.mapper.project;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;

/** 项目删除专用清理，避免改变公共角色接口及其他项目的授权。 */
public interface AttProjectDeletionMapper {

    @Delete("""
            <script>
            DELETE FROM system_user_role WHERE role_id IN (
                SELECT role_id FROM system_role WHERE project_id IN
                <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
                    #{projectId}
                </foreach>
            )
            </script>
            """)
    int deleteProjectUserRoles(@Param("projectIds") Collection<Long> projectIds);

    // 与 SysRoleMapper 的逻辑删除标记保持一致，包含历史已删角色的残留绑定。
    @Update("""
            <script>
            UPDATE system_role SET del_flag = '2' WHERE project_id IN
            <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
                #{projectId}
            </foreach>
            </script>
            """)
    int deleteProjectRoles(@Param("projectIds") Collection<Long> projectIds);
}
