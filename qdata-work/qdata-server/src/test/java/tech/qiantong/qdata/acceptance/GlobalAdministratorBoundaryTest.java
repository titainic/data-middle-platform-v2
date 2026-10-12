package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tech.qiantong.qdata.common.core.domain.entity.*;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.security.web.service.PermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GlobalAdministratorBoundaryTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void user(long userId,String key,Long project,String status){
        var role=new SysRole();role.setRoleId(99L);role.setRoleKey(key);role.setProjectId(project);role.setStatus(status);
        var user=new SysUser();user.setUserId(userId);user.setRoles(List.of(role));var login=new LoginUser(userId,null,user,Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,List.of()));
    }
    @Test void anActiveGlobalAdminRoleRemainsAdministrative(){user(925,"admin",0L,"0");assertTrue(SecurityUtils.isGlobalAdministrator());assertTrue(new PermissionService().hasRole("admin"));}
    @Test void aProjectRoleNamedAdminCannotBypassProjectChecks(){user(925,"admin",143L,"0");assertFalse(SecurityUtils.isGlobalAdministrator());assertFalse(SecurityUtils.hasRole("admin"));assertFalse(new PermissionService().hasRole("admin"));}
    @Test void aWildcardRoleCannotBecomeGlobalAdministrator(){user(925,"*",143L,"0");assertFalse(SecurityUtils.isGlobalAdministrator());assertFalse(SecurityUtils.hasRole("admin"));}
    @Test void aRoleTemplateCannotBecomeGlobalAdministrator(){user(925,"admin",-1L,"0");assertFalse(SecurityUtils.isGlobalAdministrator());}
    @Test void disabledGlobalRolesDoNotGiveTheAdministrativeBypass(){user(925,"admin",0L,"1");assertFalse(SecurityUtils.isGlobalAdministrator());}
    @Test void theRootAccountRemainsAdministrative(){user(1,"viewer",143L,"0");assertTrue(SecurityUtils.isGlobalAdministrator());}
}
