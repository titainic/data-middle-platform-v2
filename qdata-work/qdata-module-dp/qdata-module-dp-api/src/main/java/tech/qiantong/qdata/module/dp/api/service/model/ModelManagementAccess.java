package tech.qiantong.qdata.module.dp.api.service.model;
/** Interactive native model field access, separate from trusted scheduled model execution. */
public interface ModelManagementAccess {
    void requireModelColumns(Long modelId);
}
