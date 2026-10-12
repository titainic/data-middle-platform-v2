package tech.qiantong.qdata.config.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stream the exact authorized filesystem path without a second URL-resource decoding pass. */
@RestController
public class UploadedFileController {
    @GetMapping("/profile/**")
    public ResponseEntity<Resource> resource(HttpServletRequest request)throws java.io.IOException {
        Object authorized=request.getAttribute("qdata.authorizedUpload");
        if(!(authorized instanceof Path path))throw new org.springframework.security.access.AccessDeniedException("文件访问未授权");
        var file=new FileSystemResource(path);
        var response=ResponseEntity.ok().contentType(MediaTypeFactory.getMediaType(path.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .contentLength(file.contentLength());
        if("HEAD".equals(request.getMethod()))return response.build();
        return response.body(file);
    }
}
