package tech.qiantong.qdata.common.file;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import tech.qiantong.qdata.common.constant.Constants;

/** Canonical local resource identity shared by uploads, bindings and static reads. */
public final class UploadedFileKey {
    private UploadedFileKey() {}
    public static String of(String raw) {
        if(raw==null || raw.isBlank() || raw.length()>2048 || raw.contains("://"))throw new IllegalArgumentException("请选择本地已上传文件");
        String value=raw; // HTTP path decoding happens exactly once at the request boundary.
        if(value.indexOf('\0')>=0 || value.contains("\\") || value.contains(":") || value.contains("?") || value.contains("#") || value.contains(";"))throw new IllegalArgumentException("上传资源路径无效");
        String prefix=Constants.RESOURCE_PREFIX+"/";
        String relative=value.startsWith(prefix)?value.substring(prefix.length()):value;
        Path path=Path.of(relative);if(path.isAbsolute())throw new IllegalArgumentException("上传资源路径无效");
        for(Path segment:path)if("..".equals(segment.toString()))throw new IllegalArgumentException("上传资源路径不允许跨目录访问");
        String key=prefix+path.normalize().toString().replace('\\','/');
        if(key.equals(prefix) || key.length()>1024)throw new IllegalArgumentException("上传资源路径无效");
        return key;
    }
    public static String hash(String key) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("无法计算文件标识",failure);}
    }
}
