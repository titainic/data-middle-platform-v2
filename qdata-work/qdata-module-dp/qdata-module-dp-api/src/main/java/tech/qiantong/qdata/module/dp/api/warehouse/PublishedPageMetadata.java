package tech.qiantong.qdata.module.dp.api.warehouse;

/** Metadata used for serving audit without serializing the result rows. */
public interface PublishedPageMetadata {
    String getReleaseId();
    int getContractVersion();
}
