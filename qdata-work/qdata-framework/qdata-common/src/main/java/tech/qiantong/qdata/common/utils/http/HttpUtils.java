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

package tech.qiantong.qdata.common.utils.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.qiantong.qdata.common.constant.Constants;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * General HTTP request utility
 *
 * @author qdata
 */
public class HttpUtils
{
    private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

    /**
     * Send a GET request to the specified URL
     *
     * @param url the URL to send the request to
     * @return the response result of the remote resource
     */
    public static String sendGet(String url)
    {
        return sendGet(url, StringUtils.EMPTY);
    }

    /**
     * Send a GET request to the specified URL
     *
     * @param url the URL to send the request to
     * @param param request parameters, should be in the form of name1=value1&name2=value2
     * @return the response result of the remote resource
     */
    public static String sendGet(String url, String param)
    {
        return sendGet(url, param, Constants.UTF8);
    }

    /**
     * Send a GET request to the specified URL
     *
     * @param url the URL to send the request to
     * @param param request parameters, should be in the form of name1=value1&name2=value2
     * @param contentType encoding type
     * @return the response result of the remote resource
     */
    public static String sendGet(String url, String param, String contentType)
    {
        StringBuilder result = new StringBuilder();
        BufferedReader in = null;
        try
        {
            String urlNameString = StringUtils.isNotBlank(param) ? url + "?" + param : url;
            log.info("sendGet - {}", urlNameString);
            URL realUrl = new URL(urlNameString);
            URLConnection connection = realUrl.openConnection();
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("connection", "Keep-Alive");
            connection.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            connection.connect();
            in = new BufferedReader(new InputStreamReader(connection.getInputStream(), contentType));
            String line;
            while ((line = in.readLine()) != null)
            {
                result.append(line);
            }
            log.info("recv - {}", result);
        }
        catch (ConnectException e)
        {
            log.error(MessageUtils.messageEn("log.http.connect.exception"), url, param, e);
        }
        catch (SocketTimeoutException e)
        {
            log.error(MessageUtils.messageEn("log.http.socket.timeout"), url, param, e);
        }
        catch (IOException e)
        {
            log.error(MessageUtils.message("log.http.io.exception"), url, param, e);
        }
        catch (Exception e)
        {
            log.error(MessageUtils.message("log.http.unknown.exception"), url, param, e);
        }
        finally
        {
            try
            {
                if (in != null)
                {
                    in.close();
                }
            }
            catch (Exception ex)
            {
                log.error(MessageUtils.message("log.http.close.exception"), url, param, ex);
            }
        }
        return result.toString();
    }

    /**
     * Send a POST request to the specified URL
     *
     * @param url the URL to send the request to
     * @param param request parameters, should be in the form of name1=value1&name2=value2
     * @return the response result of the remote resource
     */
    public static String sendPost(String url, String param)
    {
        PrintWriter out = null;
        BufferedReader in = null;
        StringBuilder result = new StringBuilder();
        try
        {
            log.info("sendPost - {}", url);
            URL realUrl = new URL(url);
            URLConnection conn = realUrl.openConnection();
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setRequestProperty("Accept-Charset", "utf-8");
            conn.setRequestProperty("contentType", "utf-8");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            out = new PrintWriter(conn.getOutputStream());
            out.print(param);
            out.flush();
            in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null)
            {
                result.append(line);
            }
            log.info("recv - {}", result);
        }
        catch (ConnectException e)
        {
            log.error(MessageUtils.message("log.http.post.connect.exception"), url, param, e);
        }
        catch (SocketTimeoutException e)
        {
            log.error(MessageUtils.message("log.http.post.socket.timeout"), url, param, e);
        }
        catch (IOException e)
        {
            log.error(MessageUtils.message("log.http.post.io.exception"), url, param, e);
        }
        catch (Exception e)
        {
            log.error(MessageUtils.message("log.http.post.unknown.exception"), url, param, e);
        }
        finally
        {
            try
            {
                if (out != null)
                {
                    out.close();
                }
                if (in != null)
                {
                    in.close();
                }
            }
            catch (IOException ex)
            {
                log.error(MessageUtils.message("log.http.close.exception"), url, param, ex);
            }
        }
        return result.toString();
    }

    public static String sendSSLPost(String url, String param)
    {
        HttpsURLConnection connection = null;
        try
        {
            connection = openVerifiedConnection(new URL(url + "?" + param));
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("Accept-Charset", "utf-8");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    if (!line.isBlank()) result.append(line);
                }
            }
            return result.toString();
        }
        catch (IOException failure)
        {
            // 不输出URL及参数，失败不伪装成空响应。
            log.warn("sendSSLPost TLS校验、连接或读取失败，类型={}", failure.getClass().getSimpleName());
            throw new IllegalStateException("HTTPS请求失败，请检查证书、主机名或连接状态", failure);
        }
        finally
        {
            if (connection != null) connection.disconnect();
        }
    }

    /** 沿用 JDK 信任库及主机名验证；内网私有 CA 必须导入受管信任库。 */
    static HttpsURLConnection openVerifiedConnection(URL url) throws IOException
    {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("仅允许HTTPS连接");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
}
