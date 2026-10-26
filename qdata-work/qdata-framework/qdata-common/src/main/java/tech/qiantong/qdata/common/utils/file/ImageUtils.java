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

package tech.qiantong.qdata.common.utils.file;

import org.apache.poi.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.constant.Constants;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Image processing tools
 *
 * @author qdata
 */
public class ImageUtils
{
    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    public static byte[] getImage(String imagePath)
    {
        return readFile(imagePath);
    }

    public static InputStream getFile(String imagePath)
    {
        try
        {
            byte[] result = readFile(imagePath);
            if (result == null) return null;
            return new ByteArrayInputStream(result);
        }
        catch (Exception e)
        {
            log.error("Failed to get image {}", e);
        }
        return null;
    }

    /**
     * Read file as byte data
     *
     * @param url address
     * @return byte data
     */
    public static byte[] readFile(String url)
    {
        InputStream in = null;
        try
        {
            // Excel 图片仅使用受管上传文件，任何协议URL、相对路径均不触发网络请求。
            String prefix = Constants.RESOURCE_PREFIX + "/";
            if (url == null || !url.startsWith(prefix))
            {
                log.warn("拒绝读取导出图片：仅允许受管上传文件");
                return null;
            }
            Path root = Path.of(AniviaConfig.getProfile()).toRealPath();
            Path image = root.resolve(url.substring(prefix.length())).normalize().toRealPath();
            if (!image.startsWith(root) || !Files.isRegularFile(image))
            {
                log.warn("拒绝读取导出图片：路径超出上传目录");
                return null;
            }
            in = Files.newInputStream(image);
            return IOUtils.toByteArray(in);
        }
        catch (Exception e)
        {
            log.error("Failed to get file path {}", e);
            return null;
        }
        finally
        {
            IOUtils.closeQuietly(in);
        }
    }
}
