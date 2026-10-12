/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without separate commercial license from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.common.utils.ip;

import tech.qiantong.qdata.common.utils.MessageUtils;

/**
 * Get address class.
 * The external IP geolocation lookup (whois.pconline.com.cn) has been removed:
 * login/operation logs now show the internal-IP label or a placeholder instead
 * of querying a third-party website.
 *
 * @author qdata
 */
public class AddressUtils
{
    // Unknown address
    public static final String UNKNOWN = "XX XX";

    public static String getRealAddressByIP(String ip)
    {
        // Intranet does not query
        if (IpUtils.internalIp(ip))
        {
            return MessageUtils.messageEn("log.address.internal.ip");
        }
        return UNKNOWN;
    }
}
