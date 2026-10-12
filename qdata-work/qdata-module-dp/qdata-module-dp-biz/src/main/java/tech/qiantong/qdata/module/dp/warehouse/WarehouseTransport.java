package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import lombok.Data;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;

/** Uses the platform's existing SQL transport codec; authorization remains on the controller. */
public final class WarehouseTransport {
    private WarehouseTransport() {}

    @Data public static class EncodedDefinition { private String payload; }

    public static WarehouseDefinition decode(EncodedDefinition request) {
        if (request == null || request.getPayload() == null || request.getPayload().isBlank()
                || request.getPayload().length() > 2 * 1024 * 1024)
            throw new IllegalArgumentException("建模请求报文为空或超出大小限制");
        try {
            String decoded = AesEncryptUtil.desEncrypt(request.getPayload());
            if (decoded == null || decoded.isBlank()) throw new IllegalArgumentException();
            WarehouseDefinition definition = JSON.parseObject(decoded, WarehouseDefinition.class);
            if (definition == null) throw new IllegalArgumentException();
            return definition;
        } catch (Exception error) {
            throw new IllegalArgumentException("建模请求报文无效，请刷新页面后重试");
        }
    }
}
