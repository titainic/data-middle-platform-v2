package tech.qiantong.qdata.common.httpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;
import static org.junit.jupiter.api.Assertions.*;

class DependencyAdmissionWaitTest {
    DependencyGuard guard(int total,int source,int queued,Duration wait){return new DependencyGuard(total,source,1,Duration.ofSeconds(30),System::nanoTime,queued,wait);}
    void waiting(Thread thread)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(thread.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<until){assertTrue(thread.isAlive());Thread.sleep(1);}assertEquals(Thread.State.TIMED_WAITING,thread.getState());}
    @Test void shortBurstWaitsForReleasedSlot()throws Exception {
        var g=guard(1,1,1,Duration.ofSeconds(1));var held=g.acquire("a");var acquired=new AtomicBoolean();var error=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try(var p=g.acquire("a")){acquired.set(true);p.succeeded();}catch(Throwable failure){error.set(failure);}});
        thread.start();waiting(thread);assertFalse(acquired.get());held.succeeded();held.close();thread.join(1500);assertFalse(thread.isAlive());assertNull(error.get());assertTrue(acquired.get());
    }
    @Test void waitTimeoutIsBoundedAndDoesNotTripCircuit() {
        var g=guard(1,1,1,Duration.ofMillis(40));var held=g.acquire("a");long started=System.nanoTime();
        var error=assertThrows(DependencyUnavailableException.class,()->g.acquire("a"));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertTrue(error.getMessage().contains("繁忙"));assertTrue(elapsed>=20 && elapsed<700);held.succeeded();held.close();try(var p=g.acquire("a")){p.succeeded();}
    }
    @Test void queueBoundAppliesAcrossDependencies()throws Exception {
        var g=guard(2,1,1,Duration.ofSeconds(1));var a=g.acquire("a");var b=g.acquire("b");var error=new AtomicReference<Throwable>();
        var queued=new Thread(()->{try(var p=g.acquire("a")){p.succeeded();}catch(Throwable failure){error.set(failure);}});queued.start();waiting(queued);
        long started=System.nanoTime();assertThrows(DependencyUnavailableException.class,()->g.acquire("b"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<200);
        a.succeeded();a.close();queued.join(1500);assertNull(error.get());assertFalse(queued.isAlive());b.succeeded();b.close();
    }
    @Test void sameSourceWaitersKeepArrivalOrder()throws Exception {
        var g=guard(1,1,2,Duration.ofSeconds(2));var held=g.acquire("a");var order=new CopyOnWriteArrayList<Integer>();var releaseFirst=new CountDownLatch(1);var firstEntered=new CountDownLatch(1);var errors=new CopyOnWriteArrayList<Throwable>();
        var first=new Thread(()->{try(var p=g.acquire("a")){order.add(1);firstEntered.countDown();releaseFirst.await();p.succeeded();}catch(Throwable error){errors.add(error);}});
        var second=new Thread(()->{try(var p=g.acquire("a")){order.add(2);p.succeeded();}catch(Throwable error){errors.add(error);}});
        first.start();waiting(first);second.start();waiting(second);held.succeeded();held.close();assertTrue(firstEntered.await(1,TimeUnit.SECONDS));assertEquals(List.of(1),order);releaseFirst.countDown();first.join(1500);second.join(1500);assertTrue(errors.isEmpty());assertEquals(List.of(1,2),order);
    }
    @Test void interruptedWaiterLeavesQueueAndPreservesInterrupt()throws Exception {
        var g=guard(1,1,1,Duration.ofSeconds(2));var held=g.acquire("a");var interrupted=new AtomicBoolean();var error=new AtomicReference<Throwable>();
        var waiting=new Thread(()->{try(var p=g.acquire("a")){p.succeeded();}catch(Throwable failure){interrupted.set(Thread.currentThread().isInterrupted());error.set(failure);}});waiting.start();waiting(waiting);waiting.interrupt();waiting.join(1500);
        assertFalse(waiting.isAlive());assertTrue(interrupted.get());assertInstanceOf(DependencyUnavailableException.class,error.get());held.succeeded();held.close();try(var next=g.acquire("a")){next.succeeded();}
    }
    @Test void circuitFailureWakesQueuedRequests()throws Exception {
        var g=guard(1,1,1,Duration.ofSeconds(2));var held=g.acquire("a");var error=new AtomicReference<Throwable>();
        var waiter=new Thread(()->{try(var p=g.acquire("a")){p.succeeded();}catch(Throwable failure){error.set(failure);}});waiter.start();waiting(waiter);held.failed();waiter.join(1000);assertFalse(waiter.isAlive());assertInstanceOf(DependencyUnavailableException.class,error.get());assertTrue(error.get().getMessage().contains("等待恢复"));held.close();
    }
    @Test void waitingOnOneSourceDoesNotBlockAnother()throws Exception {
        var g=guard(2,1,1,Duration.ofSeconds(1));var held=g.acquire("a");var error=new AtomicReference<Throwable>();
        var waiter=new Thread(()->{try(var p=g.acquire("a")){p.succeeded();}catch(Throwable failure){error.set(failure);}});waiter.start();waiting(waiter);try(var other=g.acquire("b")){other.succeeded();}held.succeeded();held.close();waiter.join(1500);assertNull(error.get());
    }
    @Test void expiredWaiterCannotTakeSlotReleasedAfterItsDeadline()throws Exception {
        var g=guard(1,1,1,Duration.ofMillis(40));var held=g.acquire("a");var error=new AtomicReference<Throwable>();
        var waiter=new Thread(()->{try(var p=g.acquire("a")){p.succeeded();}catch(Throwable failure){error.set(failure);}});waiter.start();waiting(waiter);
        synchronized(g){Thread.sleep(90);held.succeeded();held.close();}
        waiter.join(1000);assertFalse(waiter.isAlive());assertInstanceOf(DependencyUnavailableException.class,error.get());try(var next=g.acquire("a")){next.succeeded();}
    }
}
