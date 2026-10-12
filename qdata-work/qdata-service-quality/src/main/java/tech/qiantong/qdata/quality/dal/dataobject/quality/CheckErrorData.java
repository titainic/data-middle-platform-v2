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

package tech.qiantong.qdata.quality.dal.dataobject.quality;

import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data

public class CheckErrorData implements Serializable {

    private String id;

    /**
     * Report id
     */
    private String reportId;


    /**
     * Number of erroneous data
     */

    private Integer count;


    /**
     * Number of erroneous data
     */

    private Integer errorCount;


    /**
     * Error data json list
     */

    private String dataJsonStr;

    /**
     * Check time
     */

    private Date time;

    /**
     * Error data json list
     */

    private JSONObject jsonData;

    /**
     * Error data json list
     */

    private JSONObject jsonDataOld;
    /**
     * Error data json list
     */

    private String dataJsonStrOLd;

    /**
     * Has it been fixed? 0: No 1: Yes 2: Ignore
     */

    private Integer repair;


    /**
     * Remarks
     */

    private String remark;

}
