package tech.qiantong.qdata.common.file;

/** Records only server-created CSV conversion artifacts for one chosen project. */
public interface DevelopmentFileAccess {
    void authorizeSource(Long projectId, String source);
    void register(Long projectId, String source, String executionPath);
}
