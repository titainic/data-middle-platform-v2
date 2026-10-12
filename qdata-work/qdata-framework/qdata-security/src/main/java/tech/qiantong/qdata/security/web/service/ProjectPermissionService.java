package tech.qiantong.qdata.security.web.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.PatternMatchUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import java.util.*;

/** Resolve capabilities from global roles and roles belonging to the target project. */
@Service
@RequiredArgsConstructor
public class ProjectPermissionService {
    private final JdbcTemplate jdbc;
    public boolean isAdministrator(){
        return isAdministrator(SecurityUtils.getUserId());
    }
    private boolean isAdministrator(Long user){
        if(SecurityUtils.isAdmin(user))return true;
        return jdbc.queryForObject("SELECT COUNT(*) FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id WHERE ur.user_id=? AND r.role_key='admin' AND COALESCE(r.project_id,0)=0 AND r.status='0' AND r.del_flag='0'",Long.class,user)>0;
    }
    public boolean has(Long project,String permission){
        if(isAdministrator())return true;
        var grants=jdbc.queryForList("SELECT m.perms FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id JOIN system_role_menu rm ON rm.role_id=r.role_id JOIN system_menu m ON m.menu_id=rm.menu_id WHERE ur.user_id=? AND (COALESCE(r.project_id,0)=0 OR r.project_id=?) AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms IS NOT NULL",String.class,SecurityUtils.getUserId(),project);
        return grants.stream().anyMatch(grant->matchesGrant(grant,permission));
    }
    /** Exact project-permission matching rule shared with a locked grant witness. */
    public static boolean matchesGrant(String raw,String permission){
        return raw!=null && Arrays.stream(raw.split(",")).map(String::trim)
                .anyMatch(grant->"*:*:*".equals(grant) || PatternMatchUtils.simpleMatch(grant,permission));
    }
    private Set<String> globalGrants(Long user) {
        return new HashSet<>(jdbc.queryForList("SELECT m.perms FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id JOIN system_role_menu rm ON rm.role_id=r.role_id JOIN system_menu m ON m.menu_id=rm.menu_id WHERE ur.user_id=? AND COALESCE(r.project_id,0)=0 AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms IS NOT NULL",String.class,user));
    }
    private static boolean matches(Set<String> grants,String permission) {
        return grants.stream().flatMap(grant->Arrays.stream(grant.split(","))).map(SharedCatalogPermissions::canonical).anyMatch(grant->"*:*:*".equals(grant)||PatternMatchUtils.simpleMatch(grant,SharedCatalogPermissions.canonical(permission)));
    }
    public boolean hasGlobal(String permission) {
        Long user=SecurityUtils.getUserId();return isAdministrator(user)||matches(globalGrants(user),permission);
    }
    public Set<String> filterSharedCatalogMutations(Long user,Set<String> supplied) {
        Set<String> result=new HashSet<>(supplied);
        supplied.stream().map(SharedCatalogPermissions::canonical).forEach(result::add);
        if(isAdministrator(user))return result;
        Set<String> grants=globalGrants(user);
        if(result.contains("*:*:*") && !matches(grants,"*:*:*")) {
            result.remove("*:*:*");
            result.addAll(jdbc.queryForList("SELECT perms FROM system_menu WHERE status='0' AND perms IS NOT NULL AND perms<>'' AND perms<>'*:*:*'",String.class));
        }
        result.removeIf(permission->SharedCatalogPermissions.mutation(permission)&&!matches(grants,permission));
        return result;
    }
    public boolean any(Long project,String... permissions){return Arrays.stream(permissions).anyMatch(permission->has(project,permission));}
}
