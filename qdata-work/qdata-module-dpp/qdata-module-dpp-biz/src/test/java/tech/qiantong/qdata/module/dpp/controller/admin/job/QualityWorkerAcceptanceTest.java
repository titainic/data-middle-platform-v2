package tech.qiantong.qdata.module.dpp.controller.admin.job;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QualityWorkerAcceptanceTest {
    private QualityTaskExecutorJob job(int status,String body) throws Exception {
        var job=new QualityTaskExecutorJob();var client=mock(HttpClient.class);var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);
        when(client.send(any(),any())).thenAnswer(call->response);
        ReflectionTestUtils.setField(job,"httpClient",client);ReflectionTestUtils.setField(job,"qualityUrl","http://unit.invalid/quality");return job;
    }
    @Test void applicationFailureAndNonJsonResponsesFailTheSchedulerInvocation() throws Exception {
        assertThrows(IllegalStateException.class,()->job(200,"{\"code\":500}").runExecuteTask(1L));
        assertThrows(IllegalStateException.class,()->job(200,"<html>shell</html>").runExecuteTask(1L));
        assertThrows(IllegalStateException.class,()->job(503,"{}").runExecuteTask(1L));
    }
    @Test void acceptedWorkerSubmissionCompletesNormally() throws Exception {
        assertDoesNotThrow(()->job(200,"{\"code\":200}").runExecuteTask(1L));
    }
}
