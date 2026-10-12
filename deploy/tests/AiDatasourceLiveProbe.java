import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tech.qiantong.qdata.ai.core.service.AiDatasourceReadService;
import tech.qiantong.qdata.ai.server.QDataAiApplication;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.constants.DbType;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;
import tech.qiantong.qdata.security.web.service.TokenService;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class AiDatasourceLiveProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) throw new IllegalArgumentException("Token file and normal Spring runtime arguments are required");
        Path tokenFile = Path.of(arguments[0]);
        if (Files.isSymbolicLink(tokenFile) || !Files.isRegularFile(tokenFile)
                || !PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile)).equals("rw-------")) {
            throw new IllegalArgumentException("Protected token file must be a regular file with permissions 600");
        }
        JSONObject tokens = JSON.parseObject(Files.readString(tokenFile));
        String memberToken = tokens.getString("memberToken");
        if (memberToken == null) memberToken = tokens.getString("ownerToken");
        String deniedToken = tokens.getString("nonmemberToken");
        if (memberToken == null || deniedToken == null || memberToken.equals(deniedToken)) {
            throw new IllegalArgumentException("Distinct actual member and nonmember tokens are required");
        }
        SpringApplication application = new SpringApplication(QDataAiApplication.class);
        application.setDefaultProperties(java.util.Map.of("server.port", "0", "server.address", "127.0.0.1"));
        String[] runtimeArguments = java.util.stream.Stream.concat(Arrays.stream(arguments).skip(1),
                java.util.stream.Stream.of("--server.port=0", "--server.address=127.0.0.1")).toArray(String[]::new);
        try (ConfigurableApplicationContext context = application.run(runtimeArguments)) {
            TokenService authentication = context.getBean(TokenService.class);
            LoginUser member = principal(authentication, memberToken);
            LoginUser nonmember = principal(authentication, deniedToken);
            LoginUser administrator = principal(authentication, tokens.getString("adminToken"));
            if (member.getUserId().equals(nonmember.getUserId()) || member.getUserId().equals(1L) || nonmember.getUserId().equals(1L)) {
                throw new AssertionError("Normal users must be distinct and cannot be the admin bypass identity");
            }
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String table = "ai_adapter_probe_" + suffix;
            String code = "aiprobe" + suffix;
            jdbc.execute("CREATE TABLE " + table + " (probe_id BIGINT PRIMARY KEY,probe_value VARCHAR(40))");
            jdbc.update("INSERT INTO " + table + "(probe_id,probe_value) VALUES(1,?)", "synthetic");
            long project = insert(jdbc, "INSERT INTO att_project(name,code,manager_id,creator_id,create_by,scheduler_type) VALUES(?,?,?,?,?,'QUARTZ')",
                    "AI adapter probe " + suffix, code, administrator.getUserId(), administrator.getUserId(), "ai-probe");
            jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,creator_id,create_by) VALUES(?,?,?,?)",
                    project, member.getUserId(), administrator.getUserId(), "ai-probe");
            URI mysql = URI.create(context.getEnvironment().getRequiredProperty("mysql.url").substring("jdbc:".length()));
            String database = mysql.getPath().substring(1);
            JSONObject configuration = new JSONObject();
            configuration.put("dbname", database);
            configuration.put("username", context.getEnvironment().getRequiredProperty("mysql.username"));
            configuration.put("password", AesEncryptUtil.encrypt(context.getEnvironment().getRequiredProperty("mysql.password")));
            long source = insert(jdbc, "INSERT INTO da_datasource(datasource_name,datasource_type,datasource_config,ip,port,creator_id,create_by) VALUES(?,?,?,?,?,?,?)",
                    "AI adapter probe " + suffix, DbType.MYSQL.getDb(), configuration.toJSONString(), mysql.getHost(), mysql.getPort(), administrator.getUserId(), "ai-probe");
            jdbc.update("INSERT INTO da_datasource_project_rel(project_id,project_code,datasource_id,creator_id,create_by) VALUES(?,?,?,?,?)",
                    project, code, source, administrator.getUserId(), "ai-probe");
            AiDatasourceReadService adapter = context.getBean(AiDatasourceReadService.class);
            authenticate(member);
            if (adapter.getDatasourceById(source).getId() != source) throw new AssertionError("Actual datasource row not returned");
            var names = adapter.getDbTableColumns(source, table).stream().map(DbColumn::getColName).toList();
            if (!names.containsAll(List.of("probe_id", "probe_value"))) throw new AssertionError("Actual JDBC column metadata not returned");
            authenticate(nonmember);
            denied(() -> adapter.getDatasourceById(source));
            denied(() -> adapter.getDbTableColumns(source, table));
            System.out.println("AI_DATASOURCE_LIVE_PROBE_PASS table=" + table + " project=" + project + " datasource=" + source
                    + " member=" + member.getUserId() + " nonmember=" + nonmember.getUserId() + " columns=" + names.size());
            System.out.println("Created MySQL synthetic table and metadata are retained. No Hive/HDFS operations executed.");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static long insert(JdbcTemplate jdbc, String sql, Object... arguments) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    private static LoginUser principal(TokenService service, String token) {
        if (token == null) throw new IllegalArgumentException("Actual admin token missing");
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(AiDatasourceLiveProbe.class.getClassLoader(), new Class[]{HttpServletRequest.class},
                (proxy, method, arguments) -> method.getName().equals("getHeader") && "Authorization".equals(arguments[0]) ? "Bearer " + token : null);
        LoginUser principal = service.getLoginUser(request);
        if (principal == null) throw new AssertionError("Actual shared Redis session not resolved");
        return principal;
    }

    private static void authenticate(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static void denied(Runnable operation) {
        try { operation.run(); throw new AssertionError("Nonmember reached the datasource"); }
        catch (AccessDeniedException expected) { }
    }
}
