package com.example.toiletapi.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhotoCdnPurgeDispatcherTest {
    @Test void acknowledgesOnlyAConfirmedCloudflarePurge() throws Exception {
        var repository=mock(PhotoCdnPurgeRepository.class);var client=mock(PhotoCdnClient.class);var metrics=new SimpleMeterRegistry();
        var item=new PhotoCdnPurgeRepository.Pending("12345678-1234-1234-1234-123456789abc",0);
        when(repository.pendingCount()).thenReturn(1L,0L);when(repository.due()).thenReturn(List.of(item));
        new PhotoCdnPurgeDispatcher(repository,client,metrics).dispatch();
        verify(client).purge(List.of(item.version()));verify(repository).acknowledge(item);verify(repository,never()).retry(any(),any());
        assertEquals(1,metrics.get("profile.photo.cdn.purges").tag("result","success").counter().count());
    }
    @Test void failureKeepsEveryVersionForBoundedRetry() throws Exception {
        var repository=mock(PhotoCdnPurgeRepository.class);var client=mock(PhotoCdnClient.class);var metrics=new SimpleMeterRegistry();
        var item=new PhotoCdnPurgeRepository.Pending("12345678-1234-1234-1234-123456789abc",0);
        when(repository.pendingCount()).thenReturn(1L);when(repository.due()).thenReturn(List.of(item));
        doThrow(new PhotoCdnClient.PurgeException("HTTP_503")).when(client).purge(any());
        new PhotoCdnPurgeDispatcher(repository,client,metrics).dispatch();
        verify(repository).retry(item,"HTTP_503");verify(repository,never()).acknowledge(any());
        assertEquals(1,metrics.get("profile.photo.cdn.purges").tag("result","failure").counter().count());
    }
}
