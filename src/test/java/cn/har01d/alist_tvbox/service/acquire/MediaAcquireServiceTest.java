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
        pending.setMediaKey("tmdb:movie:27205");
        when(offlineDownloadService.findMediaTask("tmdb:movie:27205", MediaAcquireService.COMPLETED))
                .thenReturn(Optional.empty());
        when(offlineDownloadService.findMediaTask("tmdb:movie:27205", MediaAcquireService.PENDING))
                .thenReturn(Optional.of(pending));
        when(offlineDownloadService.reconcileMediaTask(pending)).thenReturn(
                new OfflineDownloadService.MediaTaskReconcile(OfflineDownloadService.MediaTaskState.RUNNING, pending));

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
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.COMPLETED))
                .thenReturn(Optional.empty());
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.PENDING))
                .thenReturn(Optional.empty());
        when(provider.supports(identity)).thenReturn(true);
        when(provider.search(identity, "盗梦空间")).thenReturn(List.of(candidate));
        when(offlineDownloadService.submitMagnet(candidate.url(), null, null, 30, identity.key()))
                .thenReturn(MagnetSubmitResult.submitted("离线下载任务未在30秒内完成"));

        var result = service.acquireMovie(7, identity, "盗梦空间");

        assertEquals(MediaAcquireService.PENDING, result.status());
        verify(offlineDownloadService).submitMagnet(candidate.url(), null, null, 30, identity.key());
    }

    @Test
    void completedReconcileIsReturnedWithTargetPath() {
        var identity = MediaIdentity.movie(27205);
        OfflineDownloadTask pending = new OfflineDownloadTask();
        pending.setStatus(MediaAcquireService.PENDING);
        pending.setMediaKey(identity.key());
        OfflineDownloadTask completed = new OfflineDownloadTask();
        completed.setStatus(MediaAcquireService.COMPLETED);
        completed.setMediaKey(identity.key());
        completed.setTaskName("盗梦空间");
        completed.setTargetPath("/media/alist-tvbox-offline/盗梦空间");
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.COMPLETED))
                .thenReturn(Optional.empty());
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.PENDING))
                .thenReturn(Optional.of(pending));
        when(offlineDownloadService.reconcileMediaTask(pending)).thenReturn(
                new OfflineDownloadService.MediaTaskReconcile(OfflineDownloadService.MediaTaskState.COMPLETED, completed));

        var result = service.acquireMovie(7, identity, "盗梦空间");

        assertEquals(MediaAcquireService.COMPLETED, result.status());
        assertEquals(completed.getTargetPath(), result.targetPath());
        verify(provider, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(offlineDownloadService, never()).submitMagnet(
                org.mockito.ArgumentMatchers.anyString(), isNull(), isNull(), eq(30), eq(identity.key()));
    }

    @Test
    void existingCompletedMediaIsReusedDirectly() {
        var identity = MediaIdentity.movie(27205);
        OfflineDownloadTask completed = new OfflineDownloadTask();
        completed.setStatus(MediaAcquireService.COMPLETED);
        completed.setMediaKey(identity.key());
        completed.setTaskName("盗梦空间");
        completed.setTargetPath("/media/alist-tvbox-offline/盗梦空间");
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.COMPLETED))
                .thenReturn(Optional.of(completed));

        var result = service.acquireMovie(7, identity, "盗梦空间");

        assertEquals(MediaAcquireService.COMPLETED, result.status());
        assertEquals(completed.getTargetPath(), result.targetPath());
        verify(provider, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(offlineDownloadService, never()).reconcileMediaTask(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedReconcileTriesNextSortedCandidate() {
        var identity = MediaIdentity.movie(27205);
        OfflineDownloadTask pending = new OfflineDownloadTask();
        pending.setStatus(MediaAcquireService.PENDING);
        pending.setMediaKey(identity.key());
        var first = new MediaAcquireCandidate("source-a", "盗梦空间 1080P", "magnet:?xt=urn:btih:first",
                new MagnetResolver.MagnetInfo("first", "盗梦空间 1080P", 4 * MediaAcquirePolicy.GIB,
                        List.of(new MagnetResolver.MagnetFile("盗梦空间.1080p.mkv", 4 * MediaAcquirePolicy.GIB))));
        var second = new MediaAcquireCandidate("source-b", "盗梦空间 720P", "magnet:?xt=urn:btih:second",
                new MagnetResolver.MagnetInfo("second", "盗梦空间 720P", 4 * MediaAcquirePolicy.GIB,
                        List.of(new MagnetResolver.MagnetFile("盗梦空间.720p.mkv", 4 * MediaAcquirePolicy.GIB))));
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.COMPLETED))
                .thenReturn(Optional.empty());
        when(offlineDownloadService.findMediaTask(identity.key(), MediaAcquireService.PENDING))
                .thenReturn(Optional.of(pending));
        when(offlineDownloadService.reconcileMediaTask(pending)).thenReturn(
                new OfflineDownloadService.MediaTaskReconcile(OfflineDownloadService.MediaTaskState.FAILED, pending));
        when(provider.supports(identity)).thenReturn(true);
        when(provider.search(identity, "盗梦空间")).thenReturn(List.of(first, second));
        when(offlineDownloadService.submitMagnet(first.url(), null, null, 30, identity.key()))
                .thenReturn(MagnetSubmitResult.failed("远端失败"));
        when(offlineDownloadService.submitMagnet(second.url(), null, null, 30, identity.key()))
                .thenReturn(MagnetSubmitResult.submitted("正在获取资源"));

        var result = service.acquireMovie(7, identity, "盗梦空间");

        assertEquals(MediaAcquireService.PENDING, result.status());
        verify(offlineDownloadService).submitMagnet(first.url(), null, null, 30, identity.key());
        verify(offlineDownloadService).submitMagnet(second.url(), null, null, 30, identity.key());
    }
}
