package tech.qiantong.qdata.module.da.service.asset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;

/** A private Store is mandatory whenever the managed-object feature is enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "qdata.managed-object", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class ManagedObjectConfiguration {
    @Bean
    ManagedObjectFlow managedObjectFlow(ManagedObjectFlow.Repository repository,
                                        ManagedObjectFlow.Store store,
                                        ManagedObjectFlow.Authorizer authorizer) {
        return new ManagedObjectFlow(repository, store, authorizer);
    }
}
