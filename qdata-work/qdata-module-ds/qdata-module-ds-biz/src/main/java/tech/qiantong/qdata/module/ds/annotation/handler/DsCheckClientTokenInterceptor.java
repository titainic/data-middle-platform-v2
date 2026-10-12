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

package tech.qiantong.qdata.module.ds.annotation.handler;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.oauth2.consts.SaOAuth2Consts;
import cn.dev33.satoken.oauth2.data.model.ClientTokenModel;
import cn.dev33.satoken.oauth2.template.SaOAuth2Util;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import tech.qiantong.qdata.module.ds.annotation.DsCheckClientToken;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ClientToken validation interceptor.
 * @author Ming
 */
@Component
public class DsCheckClientTokenInterceptor implements HandlerInterceptor {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private tech.qiantong.qdata.module.ds.handler.ServiceCallAudit audit;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            DsCheckClientToken annotation = handlerMethod.getMethodAnnotation(DsCheckClientToken.class);
            if (annotation != null) {
                // Execute the validation logic.
                String token=request.getHeader("X-Client-Token");
                if(token==null || token.isBlank())token=SaHolder.getRequest().getParam(SaOAuth2Consts.Param.client_token);
                ClientTokenModel clientTokenModel;
                try {clientTokenModel=SaOAuth2Util.checkClientToken(token);}
                catch(Exception invalid) {return deny(request,response,401,"应用令牌无效或已过期");}
                if(clientTokenModel==null)return deny(request,response,401,"应用令牌无效或已过期");
                var api=tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping.getMappingApiInfo(request);
                if(api==null)return deny(request,response,404,"服务不存在或已下线");
                request.setAttribute("qdata.clientId",clientTokenModel.getClientId());
                var now=java.sql.Timestamp.from(java.time.Instant.now());
                Long allowed=jdbc.queryForObject("SELECT COUNT(*) FROM att_client c JOIN att_client_api_rel r ON r.client_id=c.id JOIN ds_api a ON a.id=r.api_id WHERE c.id=? AND r.api_id=? AND c.valid_flag='1' AND c.del_flag='0' AND r.valid_flag='1' AND r.del_flag='0' AND r.status='1' AND a.status='1' AND a.del_flag='0' AND (c.project_id IS NULL OR EXISTS(SELECT 1 FROM att_project cp WHERE cp.id=c.project_id AND cp.valid_flag='1' AND cp.del_flag='0')) AND (a.project_id IS NULL OR EXISTS(SELECT 1 FROM att_project ap WHERE ap.id=a.project_id AND ap.valid_flag='1' AND ap.del_flag='0')) AND (r.pv_flag='1' OR (r.start_time<=? AND r.end_time>=?))",Long.class,clientTokenModel.getClientId(),api.getId(),now,now);
                if(allowed==null || allowed==0)return deny(request,response,403,"应用未获该服务授权或授权已失效");
                request.setAttribute("qdata.clientId",clientTokenModel.getClientId());
                return true;
            }
        }
        return true;
    }
    private boolean deny(HttpServletRequest request,HttpServletResponse response,int status,String message) throws java.io.IOException {
        audit.denied(request,tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping.getMappingApiInfo(request),status,message);
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(com.alibaba.fastjson2.JSON.toJSONString(java.util.Map.of("code",status,"msg",message)));return false;
    }


    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // Called after the handler processes the request and renders the view.
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // Called after the entire request completes, including view rendering.
    }
}
