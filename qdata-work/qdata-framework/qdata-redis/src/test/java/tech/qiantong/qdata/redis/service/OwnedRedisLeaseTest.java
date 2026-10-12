package tech.qiantong.qdata.redis.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnedRedisLeaseTest {
    @Test void losingAcquisitionDoesNotRemoveTheCurrentHolder() {
        var redis=mock(IRedisService.class);
        assertThrows(IllegalStateException.class,()->OwnedRedisLease.acquire(redis,"unit-lease",120));
        verify(redis,never()).releaseLock(anyString(),anyString());verify(redis,never()).delete(anyString());
    }
    @Test void lostOwnershipCannotRecoverOrReleaseAnotherOwnersToken() {
        var redis=mock(IRedisService.class);when(redis.tryAcquireLock(anyString(),anyString(),anyLong())).thenReturn(true);
        when(redis.renewLock(anyString(),anyString(),anyLong())).thenReturn(false);
        var lease=OwnedRedisLease.acquire(redis,"unit-lease",120);
        var owner=ArgumentCaptor.forClass(String.class);verify(redis).tryAcquireLock(eq("unit-lease"),owner.capture(),eq(120L));
        assertFalse(lease.isOwner());when(redis.renewLock(anyString(),anyString(),anyLong())).thenReturn(true);
        assertThrows(IllegalStateException.class,lease::requireOwner);lease.close();lease.close();
        verify(redis).releaseLock("unit-lease",owner.getValue());verify(redis,never()).delete(anyString());
    }
    @Test void renewalFailureStopsSuccessConfirmation() {
        var redis=mock(IRedisService.class);when(redis.tryAcquireLock(anyString(),anyString(),anyLong())).thenReturn(true);
        when(redis.renewLock(anyString(),anyString(),anyLong())).thenThrow(new IllegalStateException("Unavailable"));
        try(var lease=OwnedRedisLease.acquire(redis,"unit-lease",120)) {
            assertFalse(lease.isOwner());assertThrows(IllegalStateException.class,lease::requireOwner);
        }
    }
}
