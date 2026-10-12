package tech.qiantong.qdata.module.da.controller.admin.asset;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;

/** Private, raw-body attachment route. Existing /upload* and /profile/** remain separate. */
@RestController
@RequestMapping("/da/managed-objects")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "qdata.managed-object", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class ManagedObjectController {
    private final ManagedObjectFlow flow;

    public ManagedObjectController(ManagedObjectFlow flow) {
        this.flow = flow;
    }

    @PostMapping(consumes = {"image/png", "image/jpeg", "application/pdf", "text/csv"})
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("projectId") long projectId,
            @RequestParam("purpose") ManagedObjectFlow.Purpose purpose,
            HttpServletRequest request) throws IOException {
        // Raw body avoids the global multipart resolver, which otherwise permits 3000 MB.
        byte[] bytes = request.getInputStream().readNBytes(ManagedObjectFlow.MAX_BYTES + 1);
        if (bytes.length > ManagedObjectFlow.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        MediaType declared = MediaType.parseMediaType(request.getContentType());
        String mediaType = declared.getType() + "/" + declared.getSubtype();
        UUID id = flow.upload(SecurityUtils.getUserId(), projectId, purpose, mediaType, bytes);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("fileId", id.toString()));
    }

    @PostMapping("/{fileId}/bind")
    public ResponseEntity<Void> bind(@PathVariable UUID fileId,
                                      @RequestParam("assetId") long assetId) {
        flow.bind(fileId, SecurityUtils.getUserId(), assetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<byte[]> original(@PathVariable UUID fileId) throws IOException {
        byte[] bytes = flow.read(fileId, SecurityUtils.getUserId(),
                ManagedObjectFlow.ReadMode.ORIGINAL);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"object-" + fileId + ".bin\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(bytes);
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> status(ResponseStatusException failure) {
        // Avoid a Servlet ERROR redispatch replacing the bounded-upload 413 with an auth envelope.
        return ResponseEntity.status(failure.getStatusCode()).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}
