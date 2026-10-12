package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.module.system.controller.admin.system.SysLoginController;
import tech.qiantong.qdata.security.web.service.SysPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginPermissionRefreshTest {
    @Test void exactCasePermissionIsRefreshedOnNormalGetInfo(){check(Set.of("da:datasource:query"),Set.of("da:datasource:query","da:dataSource:query"),true);}
    @Test void revokedPermissionIsRemovedFromTheExistingSession(){check(Set.of("mc:metadata:table:list","mc:metadata:table:edit"),Set.of("mc:metadata:table:list"),true);}
    @Test void unchangedPermissionsDoNotRewriteTheSession(){check(Set.of("mc:metadata:table:list"),Set.of("mc:metadata:table:list"),false);}
    private void check(Set<String> before,Set<String> current,boolean changed){
        var user=new SysUser();user.setUserId(926L);var login=new LoginUser(926L,null,user,before);
        var permissions=mock(SysPermissionService.class);var tokens=mock(TokenService.class);var controller=new SysLoginController();ReflectionTestUtils.setField(controller,"permissionService",permissions);ReflectionTestUtils.setField(controller,"tokenService",tokens);
        when(permissions.getRolePermission(user)).thenReturn(Set.of("viewer"));when(permissions.getMenuPermission(user)).thenReturn(current);
        try(var security=mockStatic(SecurityUtils.class)){security.when(SecurityUtils::getLoginUser).thenReturn(login);var result=controller.getInfo();assertEquals(current,result.get("permissions"));assertEquals(current,login.getPermissions());if(changed)verify(tokens).setLoginUser(login);else verifyNoInteractions(tokens);}
    }
}
