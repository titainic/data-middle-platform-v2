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

package tech.qiantong.qdata.config.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA history-mode fallback: the frontend uses createWebHistory, so refreshing or
 * deep-linking a frontend route (e.g. /login, /system/user) sends a plain GET to the
 * backend. Forward such unmatched extension-less GET requests to /index so the SPA
 * boots and its router takes over. Specific controller mappings and static resource
 * handlers always win over these catch-all patterns.
 */
@Controller
public class SpaForwardController implements tech.qiantong.qdata.common.core.SpaPageController {

    @GetMapping({"/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String spaForward() {
        return "forward:" + ENTRY_PATH;
    }
}
