package tech.qiantong.qdata.module.ds.handler;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.qiantong.qdata.module.ds.async.AsyncTask;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.module.ds.service.api.IDsApiService;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServicePublicationLifecycleTest {
    MappingHandlerMapping mappings;RequestMappingHandlerMapping spring;RequestHandler handler;
    @BeforeEach void setup(){ReflectionTestUtils.setField(MappingHandlerMapping.class,"mappings",new ConcurrentHashMap<String,DsApiDO>());spring=mock(RequestMappingHandlerMapping.class);handler=mock(RequestHandler.class);mappings=new MappingHandlerMapping();mappings.setRequestMappingHandlerMapping(spring);mappings.setHandler(handler);}
    @AfterEach void close(){ReflectionTestUtils.setField(MappingHandlerMapping.class,"mappings",new ConcurrentHashMap<String,DsApiDO>());}
    DsApiDO api(long id){var d=new DsApiDO();d.setId(id);d.setApiVersion("v1.0.0");d.setApiUrl("/acceptance/publication");d.setReqMethod("1");d.setName("publication-fixture");return d;}
    DsApiDO current(){return MappingHandlerMapping.getMappingApiInfo("get:/services/v1.0.0/acceptance/publication");}
    @Test void collisionCannotReplaceAnotherApisRoute(){mappings.registerMapping(api(1));clearInvocations(spring);assertThrows(IllegalArgumentException.class,()->mappings.registerMapping(api(2)));assertEquals(1L,current().getId());verifyNoInteractions(spring);}
    @Test void repeatedPublicationKeepsOneSpringRoute(){mappings.registerMapping(api(1));mappings.registerMapping(api(1));assertEquals(1L,current().getId());verify(spring,times(2)).registerMapping(any(),eq(handler),any());verify(spring,never()).unregisterMapping(any());}
    @Test void registrationFailureDoesNotLeaveVisibleDefinition(){doThrow(new IllegalStateException("simulated registry failure")).when(spring).registerMapping(any(),any(),any());assertThrows(IllegalStateException.class,()->mappings.registerMapping(api(1)));assertNull(current());}
    @Test void removalFailureKeepsVisibleDefinition(){mappings.registerMapping(api(1));doThrow(new IllegalStateException("simulated registry failure")).when(spring).unregisterMapping(any());assertThrows(IllegalStateException.class,()->mappings.unregisterMapping(api(1)));assertEquals(1L,current().getId());}
    @Test void anotherApiCannotRemoveExistingRoute(){mappings.registerMapping(api(1));clearInvocations(spring);assertThrows(IllegalArgumentException.class,()->mappings.unregisterMapping(api(2)));assertEquals(1L,current().getId());verifyNoInteractions(spring);}
    @Test void movingPublishedRouteRequiresExplicitUnpublish(){mappings.registerMapping(api(1));var moved=api(1);moved.setApiUrl("/other");assertThrows(IllegalArgumentException.class,()->mappings.registerMapping(moved));assertEquals(1L,current().getId());assertNull(MappingHandlerMapping.getMappingApiInfo("get:/services/v1.0.0/other"));}
    @Test void concurrentCollisionHasExactlyOneOwner()throws Exception{var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);try{var futures=new ArrayList<Future<Boolean>>();for(long id:new long[]{1,2})futures.add(pool.submit(()->{barrier.await();try{mappings.registerMapping(api(id));return true;}catch(IllegalArgumentException denied){return false;}}));int successes=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertNotNull(current());verify(spring,times(1)).registerMapping(any(),eq(handler),any());}finally{pool.shutdownNow();}}
    AsyncTask task(IDsApiService service){var t=new AsyncTask();ReflectionTestUtils.setField(t,"iDsApiService",service);ReflectionTestUtils.setField(t,"mappingHandlerMapping",mappings);return t;}
    @Test void publicationCallbackHasNoAsyncBoundary()throws Exception{assertNull(AsyncTask.class.getMethod("releaseOrCancelDataApi",Map.class).getAnnotation(org.springframework.scheduling.annotation.Async.class));}
    @Test void missingPublicationDefinitionFailsExplicitly(){assertThrows(IllegalArgumentException.class,()->task(mock(IDsApiService.class)).releaseOrCancelDataApi(Map.of("id","1","type","1")));}
    @Test void registryFailureReachesPublicationCaller(){var service=mock(IDsApiService.class);when(service.getDsApiById(1L)).thenReturn(api(1));doThrow(new IllegalStateException("registry unavailable")).when(spring).registerMapping(any(),any(),any());assertThrows(IllegalStateException.class,()->task(service).releaseOrCancelDataApi(Map.of("id","1","type","1")));}
    @ParameterizedTest @ValueSource(strings={"","0","3"}) void unknownPublicationActionFails(String type){var service=mock(IDsApiService.class);when(service.getDsApiById(1L)).thenReturn(api(1));assertThrows(IllegalArgumentException.class,()->task(service).releaseOrCancelDataApi(Map.of("id","1","type",type)));verifyNoInteractions(spring);}

    org.springframework.transaction.support.TransactionTemplate transaction(){
        return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.transaction.support.AbstractPlatformTransactionManager(){
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        });
    }
    @Test void rolledBackRegistrationRemovesUncommittedRoute(){transaction().execute(status->{mappings.registerMapping(api(1));status.setRollbackOnly();return null;});assertNull(current());verify(spring).unregisterMapping(any());}
    @Test void rolledBackUnpublishRestoresRouteAndOwnership(){mappings.registerMapping(api(1));transaction().execute(status->{mappings.unregisterMapping(api(1));assertThrows(IllegalArgumentException.class,()->mappings.registerMapping(api(2)));status.setRollbackOnly();return null;});assertEquals(1L,current().getId());verify(spring,times(2)).registerMapping(any(),eq(handler),any());}
    @Test void repeatedChangesRollbackToOriginalDefinition(){var before=api(1);before.setName("original");mappings.registerMapping(before);transaction().execute(status->{var first=api(1);first.setName("first");mappings.registerMapping(first);var second=api(1);second.setName("second");mappings.registerMapping(second);status.setRollbackOnly();return null;});assertSame(before,current());}
    @Test void committedUnpublishReleasesRouteOwnership(){mappings.registerMapping(api(1));transaction().execute(status->{mappings.unregisterMapping(api(1));return null;});assertNull(current());assertDoesNotThrow(()->mappings.registerMapping(api(2)));}
    @Test void realSpringRegistryKeepsOneMappingAfterRepeat(){
        var context=new org.springframework.web.context.support.GenericWebApplicationContext();context.setServletContext(new org.springframework.mock.web.MockServletContext());context.refresh();
        try{var real=new RequestMappingHandlerMapping();real.setApplicationContext(context);real.afterPropertiesSet();mappings.setRequestMappingHandlerMapping(real);mappings.registerMapping(api(1));mappings.registerMapping(api(1));assertEquals(1,real.getHandlerMethods().size());mappings.unregisterMapping(api(1));assertTrue(real.getHandlerMethods().isEmpty());}finally{context.close();}
    }

    @Test void nestedTransactionRollbackDoesNotJoinOuterRouteChanges(){
        transaction().execute(outer->{mappings.registerMapping(api(1));var inner=transaction();inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);inner.execute(status->{var d=api(2);d.setApiUrl("/inner");mappings.registerMapping(d);status.setRollbackOnly();return null;});assertNull(MappingHandlerMapping.getMappingApiInfo("get:/services/v1.0.0/inner"));return null;});assertEquals(1L,current().getId());
    }
    tech.qiantong.qdata.module.ds.service.api.impl.DsApiServiceImpl business(tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper mapper){
        var service=new tech.qiantong.qdata.module.ds.service.api.impl.DsApiServiceImpl();var reader=mock(IDsApiService.class);when(reader.getDsApiById(1L)).thenReturn(api(1));when(mapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(api(1));ReflectionTestUtils.setField(service,"dsApiMapper",mapper);ReflectionTestUtils.setField(service,"asyncTask",task(reader));return service;
    }
    @Test void registryFailureDoesNotWritePublishedStatus(){var mapper=mock(tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper.class);var service=business(mapper);doThrow(new IllegalStateException("registry failed")).when(spring).registerMapping(any(),any(),any());assertThrows(IllegalStateException.class,()->transaction().execute(status->{service.releaseDataApi("1",927L,"fixture");return null;}));verify(mapper,never()).update(any(tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO.class),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));assertNull(current());}
    @Test void zeroDatabaseUpdatesUndoPublishedRoute(){var service=business(mock(tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper.class));assertThrows(IllegalStateException.class,()->transaction().execute(status->{service.releaseDataApi("1",927L,"fixture");return null;}));assertNull(current());}
    @Test void zeroDatabaseUpdatesRestoreUnpublishedRoute(){mappings.registerMapping(api(1));var service=business(mock(tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper.class));assertThrows(IllegalStateException.class,()->transaction().execute(status->{service.cancelDataApi("1",927L,"fixture");return null;}));assertEquals(1L,current().getId());}
}
