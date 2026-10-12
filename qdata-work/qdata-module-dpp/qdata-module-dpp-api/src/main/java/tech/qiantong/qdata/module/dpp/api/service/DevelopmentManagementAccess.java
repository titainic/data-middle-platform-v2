package tech.qiantong.qdata.module.dpp.api.service;
import java.util.List;
/** Read metadata authorization without exposing execution configuration. */
public interface DevelopmentManagementAccess {
    List<Long> readableTaskIds();
    Long requireReadableTaskProject(Long id);
}
