package tech.qiantong.qdata.common.file;

import java.util.Set;

/** Ownership is supplied by the authenticated server context, never by a file URL caller. */
public interface UploadedFileAccess {
    void register(String resource,String purpose);
    void read(String resource);
    void use(String resource,Set<Long> targetProjects,Long targetAsset);
    void bind(String resource,Long targetAsset);
    void indexAsset(String kind,Long child,Long asset,String resource);
}
