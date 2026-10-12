package tech.qiantong.qdata.config.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.file.UploadedFileAccess;
import tech.qiantong.qdata.common.file.UploadedFileKey;
import tech.qiantong.qdata.common.utils.file.SafeUploadPath;

/** Authorization precedes the static-resource handler; guessed URLs do not bypass asset scope. */
@Component
public class UploadedFileInterceptor implements HandlerInterceptor {
    private final ObjectProvider<UploadedFileAccess> access;
    public UploadedFileInterceptor(ObjectProvider<UploadedFileAccess> access){this.access=access;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception {
        try {
            String path=request.getRequestURI().substring(request.getContextPath().length());String key=UploadedFileKey.of(org.springframework.web.util.UriUtils.decode(path,StandardCharsets.UTF_8));
            access.getObject().read(key);Path file=SafeUploadPath.resolve(Path.of(AniviaConfig.getProfile()),key);request.setAttribute("qdata.authorizedUpload",file);
            response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","private, no-store");
            String name=file.getFileName().toString();String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
            if(!Set.of("png","jpg","jpeg","gif","bmp","webp","avif","mp4","webm","mp3","wav").contains(extension)) {
                response.setHeader("Content-Security-Policy","sandbox; default-src 'none'");
                response.setHeader("Content-Disposition","attachment");
            }
            return true;
        } catch(org.springframework.security.access.AccessDeniedException denied) {
            response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getOutputStream().write("{\"code\":403,\"msg\":\"文件不属于当前可访问项目，或未获得相应读取权限\"}".getBytes(StandardCharsets.UTF_8));return false;
        } catch(IllegalArgumentException invalid) {
            response.setStatus(404);response.setContentType("application/json;charset=UTF-8");response.getOutputStream().write("{\"code\":404,\"msg\":\"上传文件不存在或路径无效\"}".getBytes(StandardCharsets.UTF_8));return false;
        }
    }
}
