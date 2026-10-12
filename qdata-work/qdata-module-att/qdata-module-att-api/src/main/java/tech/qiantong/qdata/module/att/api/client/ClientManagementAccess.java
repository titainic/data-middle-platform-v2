package tech.qiantong.qdata.module.att.api.client;

/** Management-only authorization; application token loading remains a separate interface. */
public interface ClientManagementAccess {
    void authorizeClient(Long clientId,String action);
}
