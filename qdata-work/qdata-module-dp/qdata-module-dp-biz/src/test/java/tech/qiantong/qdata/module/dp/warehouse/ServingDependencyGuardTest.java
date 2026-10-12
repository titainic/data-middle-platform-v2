package tech.qiantong.qdata.module.dp.warehouse;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;
import static org.junit.jupiter.api.Assertions.*;

class ServingDependencyGuardTest {
    final AtomicLong now=new AtomicLong();
    ServingDependencyGuard guard(int total,int perSource,int failures) {
        return new ServingDependencyGuard(total,perSource,failures,Duration.ofSeconds(30),now::get);
    }
    void fails(ServingDependencyGuard guard,long source) { try(var permit=guard.acquire(source)){permit.failed();} }
    @Test void boundsOneSourceWithoutBlockingAnotherAndReleasesOnClose() {
        var g=guard(3,2,5);var a=g.acquire(1);var b=g.acquire(1);
        assertEquals(503,assertThrows(ServingRequestException.class,()->g.acquire(1)).getStatus());
        var c=g.acquire(2);assertThrows(ServingRequestException.class,()->g.acquire(3));
        a.succeeded();a.close();a.close();try(var d=g.acquire(3)){d.succeeded();}
        b.succeeded();b.close();c.succeeded();c.close();
        try(var d=g.acquire(1)){d.succeeded();}
    }
    @Test void opensAfterConsecutiveFailuresAndDoesNotTouchHealthySources() {
        var g=guard(3,2,2);fails(g,1);try(var success=g.acquire(1)){success.succeeded();}
        fails(g,1);try(var second=g.acquire(1)){second.failed();}
        assertTrue(assertThrows(ServingRequestException.class,()->g.acquire(1)).getMessage().contains("依赖"));
        try(var healthy=g.acquire(2)){healthy.succeeded();}
    }
    @Test void cooldownAdmitsOneProbeAndSuccessfulProbeClosesCircuit() {
        var g=guard(3,2,1);fails(g,1);now.set(Duration.ofSeconds(30).toNanos()-1);
        assertThrows(ServingRequestException.class,()->g.acquire(1));now.incrementAndGet();
        var probe=g.acquire(1);assertThrows(ServingRequestException.class,()->g.acquire(1));probe.succeeded();probe.close();
        try(var a=g.acquire(1);var b=g.acquire(1)){a.succeeded();b.succeeded();}
    }
    @Test void failedProbeRestartsCooldown() {
        var g=guard(3,2,1);fails(g,1);now.set(Duration.ofSeconds(30).toNanos());fails(g,1);
        now.set(Duration.ofSeconds(59).toNanos());assertThrows(ServingRequestException.class,()->g.acquire(1));
        now.set(Duration.ofSeconds(60).toNanos());try(var recovered=g.acquire(1)){recovered.succeeded();}
    }
    @Test void lateSuccessCannotCloseCircuitOpenedByAnotherRequest() {
        var g=guard(3,2,1);var late=g.acquire(1);fails(g,1);late.succeeded();late.close();
        assertThrows(ServingRequestException.class,()->g.acquire(1));
    }
    @Test void abandonedPermitIsFailureButRejectedRequestsDoNotExtendCooldown() {
        var g=guard(2,1,1);g.acquire(1).close();
        for(int i=1;i<30;i++){now.set(Duration.ofSeconds(i).toNanos());assertThrows(ServingRequestException.class,()->g.acquire(1));}
        now.set(Duration.ofSeconds(30).toNanos());try(var recovered=g.acquire(1)){recovered.succeeded();}
    }
    @Test void simultaneousRecoveryAllowsExactlyOneProbe() throws Exception {
        var g=guard(32,16,1);fails(g,1);now.set(Duration.ofSeconds(30).toNanos());
        try(var pool=Executors.newFixedThreadPool(20)) {
            var ready=new CountDownLatch(20);var start=new CountDownLatch(1);
            var permits=new ConcurrentLinkedQueue<ServingDependencyGuard.Permit>();
            var results=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<20;i++)results.add(pool.submit(()->{ready.countDown();start.await();try{permits.add(g.acquire(1));return true;}catch(ServingRequestException rejected){return false;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int accepted=0;
            for(var result:results)if(result.get(5,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);for(var permit:permits){permit.succeeded();permit.close();}
        }
    }
    @Test void closedPermitCannotChangeLaterState() {
        var g=guard(3,2,1);var old=g.acquire(1);old.succeeded();old.close();fails(g,1);old.succeeded();
        assertThrows(ServingRequestException.class,()->g.acquire(1));
    }
}
