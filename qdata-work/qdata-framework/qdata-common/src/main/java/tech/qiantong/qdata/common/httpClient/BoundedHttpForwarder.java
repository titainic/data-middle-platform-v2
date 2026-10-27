package tech.qiantong.qdata.common.httpClient;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;

/** Reads a bounded response before committing it, including a deadline for continuously trickling bodies. */
final class BoundedHttpForwarder implements AutoCloseable {
    static final BoundedHttpForwarder DEFAULT = new BoundedHttpForwarder(5000,15000,20000,16*1024*1024,new DependencyGuard());
    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor deadlines;
    private final DependencyGuard guard;
    private final Supplier<ForwardTargetPolicy> policy;
    private final int totalMillis, maxBytes;
    BoundedHttpForwarder(int connectMillis,int readMillis,int totalMillis,int maxBytes,DependencyGuard guard) {
        this(connectMillis,readMillis,totalMillis,maxBytes,guard,()->SpringUtils.getBean(ForwardTargetPolicy.class));
    }
    BoundedHttpForwarder(int connectMillis,int readMillis,int totalMillis,int maxBytes,DependencyGuard guard,Supplier<ForwardTargetPolicy> policy) {
        if(connectMillis<1 || readMillis<1 || totalMillis<1 || maxBytes<1)throw new IllegalArgumentException("Invalid forwarding limits");
        this.totalMillis=totalMillis;this.maxBytes=maxBytes;this.guard=guard;this.policy=policy;
        var config=RequestConfig.custom().setConnectTimeout(connectMillis).setSocketTimeout(readMillis)
                .setConnectionRequestTimeout(1000).setMaxRedirects(5).build();
        client=HttpClients.custom().setDefaultRequestConfig(config).setMaxConnTotal(32).setMaxConnPerRoute(16)
                .disableAutomaticRetries().disableCookieManagement().disableRedirectHandling()
                .setDnsResolver(host->policy.get().resolve(host)).build();
        deadlines=new ScheduledThreadPoolExecutor(1,task->{var thread=new Thread(task,"qdata-forwarding-deadline");thread.setDaemon(true);return thread;});
        deadlines.setRemoveOnCancelPolicy(true);
    }
    void forward(String method,String url,Map<String,Object> parameters,List<HeaderEntity> headers,HttpServletResponse downstream)throws IOException {
        URI uri=URI.create(url);String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
        policy.get().checkUri(uri);
        int port=uri.getPort()<0?("https".equals(scheme)?443:80):uri.getPort();
        String origin=scheme+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+port;
        HttpRequestBase request;
        if("GET".equals(method))request=new HttpGet(uri);
        else if("POST".equals(method)) {
            var post=new HttpPost(uri);post.setEntity(new StringEntity(JSONObject.toJSONString(parameters),org.apache.http.entity.ContentType.APPLICATION_JSON));request=post;
        } else throw new IllegalArgumentException("不支持的服务转发方法");
        if(headers!=null)for(var header:headers)request.addHeader(header.getKey(),header.getValue());
        try(var permit=guard.acquire(origin)) {
            var deadline=deadlines.schedule(request::abort,totalMillis,TimeUnit.MILLISECONDS);
            try {
                byte[] body;int status;String contentType;
                try(var upstream=client.execute(request)) {
                    status=upstream.getStatusLine().getStatusCode();var type=upstream.getFirstHeader("Content-Type");
                    contentType=type==null?"application/octet-stream":type.getValue();var entity=upstream.getEntity();
                    if(entity==null)body=new byte[0];
                    else {
                        if(entity.getContentLength()>maxBytes)throw new DependencyUnavailableException("服务响应超过16MiB限制，已停止转发");
                        try(var input=entity.getContent();var buffer=new ByteArrayOutputStream()) {
                            byte[] chunk=new byte[8192];int count;
                            while((count=input.read(chunk))!=-1) {
                                if(buffer.size()>maxBytes-count)throw new DependencyUnavailableException("服务响应超过16MiB限制，已停止转发");
                                buffer.write(chunk,0,count);
                            }
                            body=buffer.toByteArray();
                        }
                    }
                }
                if(request.isAborted())throw new DependencyUnavailableException("服务依赖响应超时，请稍后重试");
                if(status>=500)permit.failed();else permit.succeeded();
                downstream.setStatus(status);downstream.setContentType(contentType);
                if(body.length>0)downstream.getOutputStream().write(body);
            } catch(DependencyUnavailableException failure) {
                request.abort();permit.failed();throw failure;
            } catch(IOException failure) {
                request.abort();permit.failed();
                throw new DependencyUnavailableException("服务依赖连接失败或响应超时，请稍后重试");
            } finally { deadline.cancel(false); }
        }
    }
    @Override public void close()throws IOException {deadlines.shutdownNow();client.close();}
}
