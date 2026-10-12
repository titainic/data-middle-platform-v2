package tech.qiantong.qdata.module.ds.api.service;
import java.util.List;
/** Read metadata authorization without exposing execution configuration. */
public interface ServiceManagementAccess {
    List<Long> readableApiIds();
    Long requireReadableApiProject(Long id);
}
