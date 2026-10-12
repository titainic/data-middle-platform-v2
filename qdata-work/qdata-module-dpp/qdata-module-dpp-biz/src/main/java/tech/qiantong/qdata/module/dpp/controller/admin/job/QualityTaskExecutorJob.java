package tech.qiantong.qdata.module.dpp.controller.admin.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Quartz entry point for data quality tasks.
 *
 * Mirrors the DolphinScheduler HTTP node that qData registers for a quality
 * task: it simply PUTs {ds.quality_url}/{taskId}, which makes the quality
 * executor service run the task. Used only when ds.quality_scheduler=quartz.
 */
@Slf4j
@Component("qualityTaskExecutorJob")
public class QualityTaskExecutorJob {

    @Value("${ds.quality_url}")
    private String qualityUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void runExecuteTask(Long id) {
        String target = qualityUrl + "/" + id;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Quality worker rejected task " + id + "; HTTP " + response.statusCode());
            }
            var result=com.alibaba.fastjson2.JSON.parseObject(response.body());
            if(result==null || !Integer.valueOf(200).equals(result.getInteger("code")))
                throw new IllegalStateException("Quality worker did not accept task " + id);
            log.info("Quality task {} accepted by the worker",id);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Quality task trigger interrupted",interrupted);
        } catch (Exception e) {
            log.error("Quality task {} trigger error", id, e);
            throw new IllegalStateException("Quality task trigger failed: "+id,e);
        }
    }
}
