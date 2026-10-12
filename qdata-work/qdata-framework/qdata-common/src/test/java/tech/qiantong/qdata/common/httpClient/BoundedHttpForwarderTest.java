package tech.qiantong.qdata.common.httpClient;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpForwarderTest {
    HttpServer server;ExecutorService workers;BoundedHttpForwarder forwarder;String url;
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);workers=Executors.newCachedThreadPool();server.setExecutor(workers);server.start();
        url="http://127.0.0.1:"+server.getAddress().getPort();forwarder=new BoundedHttpForwarder(300,400,700,1024,new DependencyGuard());
    }
    @AfterEach void close()throws Exception {forwarder.close();server.stop(0);workers.shutdownNow();}
    void endpoint(int status,String type,byte[] data) {
        server.createContext("/payload",request->{try{if(type!=null)request.getResponseHeaders().set("Content-Type",type);request.sendResponseHeaders(status,status==204?-1:data.length);if(status!=204)request.getResponseBody().write(data);}finally{request.close();}});
    }
    MockHttpServletResponse get(){var response=new MockHttpServletResponse();try{forwarder.forward("GET",url+"/payload",Map.of(),List.of(),response);}catch(java.io.IOException error){throw new RuntimeException(error);}return response;}
    @ParameterizedTest @ValueSource(strings={"application/json;charset=UTF-8","text/plain;charset=UTF-8","application/xml;charset=UTF-8","image/png"})
    void preservesResponseBytesAndContentType(String type){byte[] bytes="水 air 土壤\u0000".getBytes(StandardCharsets.UTF_8);endpoint(200,type,bytes);var result=get();assertEquals(200,result.getStatus());assertEquals(type,result.getContentType());assertArrayEquals(bytes,result.getContentAsByteArray());}
    @Test void handlesEmpty204WithoutCommittingAnError(){endpoint(204,null,new byte[0]);var result=get();assertEquals(204,result.getStatus());assertEquals(0,result.getContentAsByteArray().length);}
    @Test void missingContentTypePreservesBinary(){byte[] body={(byte)255,0,1,2};endpoint(200,null,body);var result=get();assertEquals("application/octet-stream",result.getContentType());assertArrayEquals(body,result.getContentAsByteArray());}
    @Test void postCarriesJsonAndExplicitHeader()throws Exception {
        var seen=new AtomicReference<String>();server.createContext("/payload",r->{try{seen.set(r.getRequestMethod()+"|"+r.getRequestHeaders().getFirst("X-Fixture")+"|"+new String(r.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));r.sendResponseHeaders(204,-1);}finally{r.close();}});
        var header=new HeaderEntity();header.setKey("X-Fixture");header.setValue("synthetic");var response=new MockHttpServletResponse();forwarder.forward("POST",url+"/payload",Map.of("medium","water"),List.of(header),response);assertEquals("POST|synthetic|{\"medium\":\"water\"}",seen.get());assertEquals(204,response.getStatus());
    }
    @Test void oversizedKnownBodyIsRejectedBeforeResponseCommit(){endpoint(200,"application/octet-stream",new byte[1025]);var response=new MockHttpServletResponse();assertThrows(DependencyUnavailableException.class,()->forwarder.forward("GET",url+"/payload",Map.of(),List.of(),response));assertEquals(0,response.getContentAsByteArray().length);assertFalse(response.isCommitted());}
    @Test void oversizedChunkedBodyIsRejectedBeforeResponseCommit(){server.createContext("/payload",r->{try{r.sendResponseHeaders(200,0);r.getResponseBody().write(new byte[2048]);}finally{r.close();}});var response=new MockHttpServletResponse();assertThrows(DependencyUnavailableException.class,()->forwarder.forward("GET",url+"/payload",Map.of(),List.of(),response));assertEquals(0,response.getContentAsByteArray().length);}
    @Test void delayedHeadersReturnBoundedSafeFailure(){server.createContext("/payload",r->{try{Thread.sleep(1500);r.sendResponseHeaders(204,-1);}catch(Exception ignored){}finally{r.close();}});long start=System.nanoTime();var failure=assertThrows(DependencyUnavailableException.class,this::get);assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1400);assertFalse(failure.getMessage().contains(url));}
    @Test void wholeRequestDeadlineStopsContinuousTrickle()throws Exception {
        forwarder.close();forwarder=new BoundedHttpForwarder(300,500,220,1024,new DependencyGuard());server.createContext("/payload",r->{try{r.sendResponseHeaders(200,0);for(int i=0;i<50;i++){r.getResponseBody().write('x');r.getResponseBody().flush();Thread.sleep(30);}}catch(Exception ignored){}finally{r.close();}});
        var response=new MockHttpServletResponse();long start=System.nanoTime();assertThrows(DependencyUnavailableException.class,()->forwarder.forward("GET",url+"/payload",Map.of(),List.of(),response));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1200);assertEquals(0,response.getContentAsByteArray().length);
    }
    @Test void upstreamServerErrorsTripCircuitAndProbeRecovers()throws Exception {
        var now=new AtomicLong();forwarder.close();forwarder=new BoundedHttpForwarder(300,400,700,1024,new DependencyGuard(4,2,2,Duration.ofSeconds(30),now::get));var hits=new AtomicInteger();var status=new AtomicInteger(503);
        server.createContext("/payload",r->{hits.incrementAndGet();try{r.sendResponseHeaders(status.get(),-1);}finally{r.close();}});
        assertEquals(503,get().getStatus());assertEquals(503,get().getStatus());assertThrows(DependencyUnavailableException.class,this::get);assertEquals(2,hits.get());status.set(204);now.set(Duration.ofSeconds(30).toNanos());assertEquals(204,get().getStatus());assertEquals(3,hits.get());
    }
    @Test void clientErrorsDoNotTripDependencyCircuit(){endpoint(404,"text/plain","missing".getBytes(StandardCharsets.UTF_8));for(int i=0;i<6;i++)assertEquals(404,get().getStatus());}
    @Test void saturatedOriginFailsWithoutSendingAnotherRequest()throws Exception {
        forwarder.close();forwarder=new BoundedHttpForwarder(300,1000,1400,1024,new DependencyGuard(2,1,5,Duration.ofSeconds(30),System::nanoTime));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var hits=new AtomicInteger();
        server.createContext("/payload",r->{hits.incrementAndGet();entered.countDown();try{release.await(1,TimeUnit.SECONDS);r.sendResponseHeaders(204,-1);}catch(Exception ignored){}finally{r.close();}});
        try(var calls=Executors.newSingleThreadExecutor()){var first=calls.submit(this::get);assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(assertThrows(DependencyUnavailableException.class,this::get).getMessage().contains("繁忙"));assertEquals(1,hits.get());release.countDown();assertEquals(204,first.get(2,TimeUnit.SECONDS).getStatus());}
    }
}
