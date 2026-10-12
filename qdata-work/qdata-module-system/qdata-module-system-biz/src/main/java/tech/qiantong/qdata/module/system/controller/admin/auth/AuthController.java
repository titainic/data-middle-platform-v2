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

package tech.qiantong.qdata.module.system.controller.admin.auth;

import com.ejlchina.okhttps.OkHttps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.enums.UserStatus;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.SoMap;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.module.system.dal.dataobject.auth.RelUserAuthProductDO;
import tech.qiantong.qdata.module.system.enums.auth.AuthProductEnums;
import tech.qiantong.qdata.module.system.service.ISysUserService;
import tech.qiantong.qdata.module.system.service.auth.IRelUserAuthProductService;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.security.web.service.SysPermissionService;
import tech.qiantong.qdata.security.web.service.TokenService;

/**
 * oauth2 Controller
 *
 * @author surge
 * @date 2022-09-16
 */
@RestController
@RequestMapping("/oauth2")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private ISysUserService userService;
    @Autowired
    private SysPermissionService permissionService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private IRedisService redisService;
    @Autowired
    private IRelUserAuthProductService userAuthProductService;

    @Value("${oauth2.redis-prefix}")
    private String redisPrefix;

    // redis secondary folder naming
    public static final String accessTokenPrefix = "accessToken";
    public static final String refreshTokenPrefix = "refreshToken";

    // related parameter configuration
    @Value("${oauth2.clientId}")
    private String clientId;            // application ID
    @Value("${oauth2.clientSecret}")
    private String clientSecret;        // application secret
    @Value("${oauth2.serverUrl}")
    private String serverUrl;           // server endpoint

    // login via authorization code to obtain Access-Token and openid
    @RequestMapping("/codeLogin")
    @Transactional
    public AjaxResult codeLogin(String code) {
        // call server endpoint to obtain Access-Token and related info
        String str = OkHttps.sync(serverUrl + "/oauth2/token")
                .addBodyPara("grant_type", "authorization_code")
                .addBodyPara("code", code)
                .addBodyPara("client_id", clientId)
                .addBodyPara("client_secret", clientSecret)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);

        // code != 200 means request failed
        if(so.getInt("code") != 200) {
            return AjaxResult.error(so.getString("msg"));
        }

        // get userId by openid
        SoMap data = so.getMap("data");

        // idHubId
        Long idHubId = data.getLong("idHubId");
        // Access-Token value
        String accessToken = data.getString("access_token");
        // Refresh-Token value
        String refreshToken = data.getString("refresh_token");
        // Access-Token remaining validity in seconds
        long expiresIn = data.getLong("expires_in");
        // Refresh-Token remaining validity in seconds
        long refreshExpiresIn = data.getLong("refresh_expires_in");

        SysUser user = this.getUserByIdHubId(idHubId);

        if (user == null) {
            // get userInfo via openid
            SoMap userinfo = this.getUserinfo(accessToken);
            if (userinfo != null) {
                // unified identity authentication phone number
                String phone = userinfo.getString("phone");
                // find user by phone number
                SysUser userByPhone = userService.findUserByNameOrPhone(phone);

                if (userByPhone != null) {
                    RelUserAuthProductDO productDO = RelUserAuthProductDO.builder()
                            .userId(userByPhone.getUserId())
                            .authId(idHubId.toString())
                            .authProductType(AuthProductEnums.ANIVIA.code)
                            .build();

                    userAuthProductService.save(productDO);
                    user = userByPhone;
                } else {
                    return AjaxResult.error("system.user.notfound");
                }
            } else {
                return AjaxResult.error("system.error.auth.fetchUserInfo");
            }
        }

        ensureUserCanLogin(user);

        // store in redis
        redisService.set(redisPrefix + ":" + accessTokenPrefix + ":" + user.getUserId().toString(), accessToken, expiresIn);
        redisService.set(redisPrefix + ":" + refreshTokenPrefix + ":" + user.getUserId().toString(), refreshToken, refreshExpiresIn);

        // create login user (password-free)
        LoginUser loginUser = createLoginUser(user);

        // obtain password-free token
        String token = tokenService.createToken(loginUser);

        log.info("User: {} logged in via unified identity authentication platform successfully!", user.getUserName());
        return AjaxResult.success(token);
    }


    // refresh Access-Token
    private String refresh(String refreshToken, Long userId) {
            // call server endpoint to refresh a new Access-Token via Refresh-Token
            String str = OkHttps.sync(serverUrl + "/oauth2/refresh")
                .addBodyPara("grant_type", "refresh_token")
                .addBodyPara("client_id", clientId)
                .addBodyPara("client_secret", clientSecret)
                .addBodyPara("refresh_token", refreshToken)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);

        // code != 200 means request failed
        if(so.getInt("code") != 200) {
            return null;
        }

        // return related parameters (data=new Access-Token)
        SoMap data = so.getMap("data");

        // Access-Token value
        String accessToken = data.getString("access_token");
        // Refresh-Token value
        String refToken = data.getString("refresh_token");
        // Access-Token remaining validity in seconds
        long expiresIn = data.getLong("expires_in");
        // Refresh-Token remaining validity in seconds
        long refreshExpiresIn = data.getLong("refresh_expires_in");

        // store in redis
        redisService.set(redisPrefix + ":" + accessTokenPrefix + ":" + userId.toString(), accessToken, expiresIn);
        redisService.set(redisPrefix + ":" + refreshTokenPrefix + ":" + userId.toString(),
                StringUtils.isNotEmpty(refToken) ? refToken : refreshToken, refreshExpiresIn);

        return data.getString("access_token");
    }

    public LoginUser createLoginUser(SysUser user)
    {
        return new LoginUser(user.getUserId(), user.getDeptId(), user, permissionService.getMenuPermission(user));
    }

    /**
     * Get user by idHubId
     * @param idHubId unified identity authentication ID
     * @return user
     */
    private SysUser getUserByIdHubId(Long idHubId) {
        // Auth platform association
        RelUserAuthProductDO authInfo = userAuthProductService.lambdaQuery()
                .eq(RelUserAuthProductDO::getAuthId, idHubId.toString())
                .eq(RelUserAuthProductDO::getAuthProductType, AuthProductEnums.ANIVIA.code)
                .one();

        if (authInfo != null) {
            SysUser user = userService.selectUserById(authInfo.getUserId());
            if (user == null) {
                throw new ServiceException(MessageUtils.message("user.not.exists"));
            }
            return user;
        }
        return null;
    }

    private void ensureUserCanLogin(SysUser user) {
        if (UserStatus.DELETED.getCode().equals(user.getDelFlag())) {
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus())) {
            throw new ServiceException(MessageUtils.message("user.blocked"));
        }
    }

    /**
     * Exchange Access-Token for resources: get account nickname, avatar, gender, etc.
     * @param accessToken
     * @return
     */
    public SoMap getUserinfo(String accessToken) {
        // call server endpoint to query open resources
        String str = OkHttps.sync(serverUrl + "/oauth2/userinfo")
                .addBodyPara("access_token", accessToken)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);

        // code != 200 means request failed
        if(so.getInt("code") != 200) {
            throw new ServiceException("system.error.account.fetch");
        }

        // return related parameters (data=obtained resources)
        return so.getMap("data");
    }

    /**
     * Logout
     * @return
     */
    @RequestMapping("/sso/logout")
    public AjaxResult loginOut() {
        Long userId = SecurityUtils.getUserId();
        String accessToken = redisService.get(redisPrefix + ":" + accessTokenPrefix + ":" + userId);

        if (StringUtils.isEmpty(accessToken)) {
            String refreshToken = redisService.get(redisPrefix + ":" + refreshTokenPrefix + ":" + userId);
            if (StringUtils.isEmpty(refreshToken)) {
                return AjaxResult.error("system.error.auth.logoutUnavailable");
            }
            accessToken = this.refresh(refreshToken, userId);
            if (StringUtils.isEmpty(accessToken)) {
                return AjaxResult.error("system.error.auth.logoutUnavailable");
            }
        }

        // call server endpoint to query open resources
        String str = OkHttps.sync(serverUrl + "/oauth2/logout")
                .addBodyPara("access_token", accessToken)
                .post()
                .getBody()
                .toString();
        SoMap so = SoMap.getSoMap().setJsonString(str);

        if (so.getInt("code") != 200) {
            return AjaxResult.error("system.error.auth.logoutFailed");
        }
        redisService.delete(redisPrefix + ":" + accessTokenPrefix + ":" + userId);
        redisService.delete(redisPrefix + ":" + refreshTokenPrefix + ":" + userId);
        return AjaxResult.success();
    }
}
