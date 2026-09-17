package cn.har01d.alist_tvbox.service.acquire;

import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.model.MagnetSubmitResult;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import cn.har01d.alist_tvbox.service.TvBoxService;
import cn.har01d.alist_tvbox.service.magnet.MagnetResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAcquireServiceTest {
    @Mock
    private OfflineDownloadService offlineDownloadService;
    @Mock
    private OfflineDownloadTaskRepository taskRepository;
    @Mock
    private MagnetResolver magnetResolver;
    @Mock
    private TvBoxService tvBoxService;
    @Mock
    private MediaAcquireProvider provider;

    private MediaAcquireService service;

    @BeforeEach
    void setUp() {
        service = new MediaAcquireService(offlineDownloadService, taskRepository, magnetResolver,
                tvBoxService, List.of(provider));
    }

    @Test
    void existingPendingMediaIsReturnedWithoutSubmittingAgain() {
        OfflineDownloadTask pending = new OfflineDownloadTask();
        pending.setStatus(MediaAcquireService.PENDING);
        when(taskRepository.findFirstByMediaKeyAndStatusOrderByUpdatedTimeDesc(
                "tmdb:movie:27205", MediaAcquireService.COMPLETED)).thenReturn(Optional.empty());
        when(taskRepository.findFirstByMediaKeyAndStatusOrderByUpdatedTimeDesc(
                "tmdb:movie:27205", MediaAcquireService.PENDING)).thenReturn(Optional.of(pending));

        var first = service.acquireMovie(7, MediaIdentity.movie(27205), "盗梦空间");
        var second = service.acquireMovie(7, MediaIdentity.movie(27205), "盗梦空间");

        assertEquals(MediaAcquireService.PENDING, first.status());
        assertEquals(MediaAcquireService.PENDING, second.status());
        verify(provider, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(offlineDownloadService, never()).submitMagnet(
                org.mockito.ArgumentMatchers.anyString(), isNull(), isNull(), eq(30), eq("tmdb:movie:27205"));
    }

    @Test
    void thirtySecondTimeoutIsReportedAsPending() {
        var identity = MediaIdentity.movie(27205);
        var candidate = new MediaAcquireCandidate("source", "盗梦空间 1080P", "magnet:?xt=urn:btih:abc",
                new MagnetResolver.MagnetInfo("hash", "盗梦空间 1080P", 4 * MediaAcquirePolicy.GIB,
                        List.of(new MagnetResolver.MagnetFile("盗梦空间.1080p.mkv", 4 * MediaAcquirePolicy.GIB))));
        when(taskRepository.findFirstByMediaKeyAndStatusOrderByUpdatedTimeDesc(
                identity.key(), MediaAcquireService.COMPLETED)).thenReturn(Optional.empty());
        when(taskRepository.findFirstByMediaKeyAndStatusOrderByUpdatedTimeDesc(
                identity.key(), MediaAcquireService.PENDING)).thenReturn(Optional.empty());
        when(provider.search(identity, "盗梦空间")).thenReturn(List.of(candidate));
        when(offlineDownloadService.submitMagnet(candidate.url(), null, null, 30, identity.key()))
                .thenReturn(MagnetSubmitResult.submitted("离线下载任务未在30秒内完成"));

        var result = service.acquireMovie(7, identity, "盗梦空间");

        assertEquals(MediaAcquireService.PENDING, result.status());
        verify(offlineDownloadService).submitMagnet(candidate.url(), null, null, 30, identity.key());
    }
}
