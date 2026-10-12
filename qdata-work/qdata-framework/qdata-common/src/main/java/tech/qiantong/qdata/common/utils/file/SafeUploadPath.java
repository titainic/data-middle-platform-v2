package tech.qiantong.qdata.common.utils.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tech.qiantong.qdata.common.constant.Constants;

/** Resolve uploaded local files without permitting traversal or an escaping symbolic link. */
public final class SafeUploadPath {
    private SafeUploadPath() { }
    public static Path resolve(Path uploadRoot, String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.indexOf('\0') >= 0 || supplied.contains("://"))
            throw new IllegalArgumentException("请选择有效的已上传文件");
        String prefix = Constants.RESOURCE_PREFIX + "/";
        String relative = supplied.startsWith(prefix) ? supplied.substring(prefix.length()) : supplied;
        Path request = Path.of(relative);
        if (request.isAbsolute()) throw new IllegalArgumentException("上传文件路径不允许使用绝对路径");
        for (Path segment : request) if ("..".equals(segment.toString()))
            throw new IllegalArgumentException("上传文件路径不允许跨目录访问");
        try {
            Path root = uploadRoot.toRealPath();
            Path file = root.resolve(request).toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file))
                throw new IllegalArgumentException("文件不在有效上传目录内");
            return file;
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("上传文件不存在或不可读取", unavailable);
        }
    }
}
