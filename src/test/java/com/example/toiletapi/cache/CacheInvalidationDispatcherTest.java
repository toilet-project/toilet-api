package com.example.toiletapi.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheInvalidationDispatcherTest {
    private final CacheInvalidationRepository repository=mock(CacheInvalidationRepository.class);
    private final CacheInvalidationClient client=mock(CacheInvalidationClient.class);
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private final CacheInvalidationDispatcher dispatcher=new CacheInvalidationDispatcher(repository,client,metrics,1);
    private final CacheInvalidationRepository.Pending item=new CacheInvalidationRepository.Pending(1,"event-a",1,CacheInvalidationEvent.Action.UPSERT,false,0);
    @Test void acknowledgesOnlyAfterSuccessfulDelivery() throws Exception {
        when(repository.due()).thenReturn(List.of(item)); dispatcher.dispatch();
        var order=inOrder(client,repository); order.verify(client).send(List.of(1L)); order.verify(repository).acknowledge(item);
    }
    @Test void failedDeliveryRetainsAndReschedulesRows() throws Exception {
        when(repository.due()).thenReturn(List.of(item)); doThrow(new CacheInvalidationClient.DeliveryException("HTTP_503")).when(client).send(anyList());
        dispatcher.dispatch(); verify(repository).retry(item,"HTTP_503"); verify(repository,never()).acknowledge(any());
        assertEquals(1,metrics.counter("web.cache.invalidation.failures").count());
    }
    @Test void emptyQueueMakesNoHttpRequest() { when(repository.due()).thenReturn(List.of()); dispatcher.dispatch(); verifyNoInteractions(client); }
    @Test void v2SendsRevisionedEvents() throws Exception {
        var v2=new CacheInvalidationDispatcher(repository,client,metrics,2);
        when(repository.due()).thenReturn(List.of(item)); v2.dispatch();
        verify(client).sendEvents(List.of(item.event())); verify(repository).acknowledge(item);
    }
    @Test void queueFailureDoesNotEscapeOrExposeExceptionDetails() {
        when(repository.due()).thenThrow(new IllegalStateException("sensitive details")); assertDoesNotThrow(dispatcher::dispatch); verifyNoInteractions(client);
    }
    @Test void retryBackoffIsBoundedAndNeverNegative() {
        assertEquals(10,CacheInvalidationRepository.retrySeconds(0)); assertEquals(20,CacheInvalidationRepository.retrySeconds(1));
        assertEquals(3600,CacheInvalidationRepository.retrySeconds(50)); assertEquals(10,CacheInvalidationRepository.retrySeconds(-1));
    }
}
