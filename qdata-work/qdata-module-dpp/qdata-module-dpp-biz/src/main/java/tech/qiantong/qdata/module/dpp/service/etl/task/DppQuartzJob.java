/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.springframework.stereotype.Component;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;

/**
 * Quartz entry point for DPP.
 * Quartz invokes this class when triggered, while the DPP task service performs the actual execution.
 */
@Component("dppQuartzJob")
public class DppQuartzJob {

    private final IDppEtlTaskService dppEtlTaskService;

    public DppQuartzJob(IDppEtlTaskService dppEtlTaskService) {
        this.dppEtlTaskService = dppEtlTaskService;
    }

    /**
     * Data integration.
     */
    public void dataIntegration(Long taskId) {
        dppEtlTaskService.startDppEtlTaskDataIntegration(taskId);
    }

    /** Exceptions must reach Quartz so startup and execution failures are recorded. */
    public void dataDevelopment(Long id) {
        dppEtlTaskService.startDppEtlTaskDataDevelopment(id);
    }
}
