package tech.qiantong.qdata.module.dp.warehouse;

import java.time.Duration;
import java.util.function.LongSupplier;
import tech.qiantong.qdata.common.httpClient.DependencyGuard;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;

/** Keeps the published service error contract while sharing dependency protection. */
final class ServingDependencyGuard extends DependencyGuard {
    ServingDependencyGuard() { super(); }
    ServingDependencyGuard(int total,int perSource,int failures,Duration cooldown,LongSupplier clock) {
        super(total,perSource,failures,cooldown,clock);
    }
    Permit acquire(long sourceId) {
        try { return super.acquire(sourceId); }
        catch (DependencyUnavailableException failure) { throw new ServingRequestException(503,failure.getMessage()); }
    }
}
