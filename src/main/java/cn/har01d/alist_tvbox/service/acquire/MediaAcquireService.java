package cn.har01d.alist_tvbox.service.acquire;

import cn.har01d.alist_tvbox.entity.OfflineDownloadTask;
import cn.har01d.alist_tvbox.entity.OfflineDownloadTaskRepository;
import cn.har01d.alist_tvbox.service.OfflineDownloadService;
import cn.har01d.alist_tvbox.service.magnet.MagnetResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Unified acquire orchestration. Download tasks remain OfflineDownloadTask rows. */
@Slf4j
@Service
public class MediaAcquireService {
    public static final String ACQUIRE_PLAY_PREFIX = "macquire-";
    public static final String COMPLETED = "COMPLETED";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    private final OfflineDownloadService offlineDownloadService;
    private final OfflineDownloadTaskRepository taskRepository;
    private final MagnetResolver magnetResolver;
    private final cn.har01d.alist_tvbox.service.TvBoxService tvBoxService;
    private final List<MediaAcquireProvider> providers;
    private final MediaAcquirePolicy policy = new MediaAcquirePolicy();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public MediaAcquireService(OfflineDownloadService offlineDownloadService,
                               OfflineDownloadTaskRepository taskRepository,
                               MagnetResolver magnetResolver,
                               cn.har01d.alist_tvbox.service.TvBoxService tvBoxService,
                               List<MediaAcquireProvider> providers) {
        this.offlineDownloadService = offlineDownloadService;
        this.taskRepository = taskRepository;
        this.magnetResolver = magnetResolver;
        this.tvBoxService = tvBoxService;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public AcquireResult acquireMovie(int uid, MediaIdentity identity, String title) {
        if (!identity.isMovie()) {
            throw new IllegalArgumentException("Media acquire only accepts movie identity");
        }
        return acquire(identity, title);
    }

    public AcquireResult acquire(MediaIdentity identity, String title) {
        String mediaKey = identity.key();
        Object lock = locks.computeIfAbsent(mediaKey, ignored -> new Object());
        synchronized (lock) {
            try {
                AcquireResult existing = existing(mediaKey, identity);
                if (existing != null) {
                    return existing;
                }
                List<ScoredCandidate> candidates = candidates(identity, title);
                if (candidates.isEmpty()) {
                    return AcquireResult.failed(identity, "未找到可获取资源");
                }
                for (ScoredCandidate scored : candidates) {
                    try {
                        var result = offlineDownloadService.submitMagnet(scored.candidate.url(), null, null, 30, mediaKey);
                        if (cn.har01d.alist_tvbox.model.MagnetSubmitResult.COMPLETED.equals(result.status())) {
                            OfflineDownloadTask task = completedTask(mediaKey).orElse(null);
                            return AcquireResult.completed(identity, scored.candidate, scored.score.value(),
                                    task, result.taskName());
                        }
                        if (cn.har01d.alist_tvbox.model.MagnetSubmitResult.SUBMITTED.equals(result.status())) {
                            return AcquireResult.pending(identity, scored.candidate, scored.score.value(),
                                    "正在获取资源");
                        }
                        log.info("media acquire candidate rejected: provider={}, score={}, reasons={}",
                                scored.candidate.provider(), scored.score.value(), scored.score.reasons());
                    } catch (Exception e) {
                        log.info("media acquire candidate submission failed: provider={}, reason={}",
                                scored.candidate.provider(), e.getMessage());
                    }
                }
                return AcquireResult.failed(identity, "候选资源提交失败");
            } finally {
                locks.remove(mediaKey, lock);
            }
        }
    }

    public Object response(AcquireResult result, String ac, String title) {
        if (!COMPLETED.equals(result.status())) {
            return Map.of("msg", StringUtils.defaultIfBlank(result.message(), "正在获取资源"));
        }
        String path = result.targetPath();
        if (StringUtils.isBlank(path)) {
            return Map.of("msg", "资源已获取，播放路径待刷新");
        }
        if (result.folder()) {
            path += "/~playlist";
        }
        return tvBoxService.getDetail(StringUtils.defaultString(ac), "1$" + path, title);
    }

    public Object acquireMovieResponse(int uid, MediaIdentity identity, String title, String ac) {
        return response(acquireMovie(uid, identity, title), ac, title);
    }

    private AcquireResult existing(String mediaKey, MediaIdentity identity) {
        OfflineDownloadTask completed = offlineDownloadService.findMediaTask(mediaKey, COMPLETED).orElse(null);
        if (usable(completed)) {
            return AcquireResult.completed(identity, null, 0, completed, completed.getTaskName());
        }
        OfflineDownloadTask pending = offlineDownloadService.findMediaTask(mediaKey, PENDING).orElse(null);
        if (pending != null && !OfflineDownloadService.CLEANUP_DONE.equals(pending.getCleanupState())) {
            OfflineDownloadService.MediaTaskReconcile reconciled = offlineDownloadService.reconcileMediaTask(pending);
            if (reconciled != null && OfflineDownloadService.MediaTaskState.COMPLETED.equals(reconciled.state())
                    && usable(reconciled.task())) {
                OfflineDownloadTask task = reconciled.task();
                return AcquireResult.completed(identity, null, 0, task, task.getTaskName());
            }
            if (reconciled != null && OfflineDownloadService.MediaTaskState.FAILED.equals(reconciled.state())) {
                return null; // The next sorted candidate may still be usable.
            }
            return AcquireResult.pending(identity, null, 0, "正在获取资源");
        }
        return null;
    }

    private List<ScoredCandidate> candidates(MediaIdentity identity, String title) {
        List<ScoredCandidate> result = new ArrayList<>();
        for (MediaAcquireProvider provider : providers) {
            if (!provider.supports(identity)) {
                continue;
            }
            try {
                List<MediaAcquireCandidate> found = provider.search(identity, title);
                if (found == null) {
                    continue;
                }
                for (MediaAcquireCandidate candidate : found) {
                    if (candidate == null || StringUtils.isBlank(candidate.url())) {
                        continue;
                    }
                    MediaAcquireCandidate parsed = resolve(candidate);
                    MediaAcquirePolicy.Score score = policy.score(identity, title, parsed);
                    if (score.accepted()) {
                        result.add(new ScoredCandidate(parsed, score));
                    }
                    log.info("media acquire candidate score: provider={}, score={}, accepted={}, reasons={}",
                            provider.name(), score.value(), score.accepted(), score.reasons());
                }
            } catch (Exception e) {
                log.warn("media acquire provider {} failed: {}", provider.name(), e.getMessage());
            }
        }
        result.sort(Comparator.comparing((ScoredCandidate value) -> value.candidate.metadataResolved()).reversed()
                .thenComparing(Comparator.comparingInt((ScoredCandidate value) -> value.score.value()).reversed())
                .thenComparing(value -> value.candidate.provider()));
        return result;
    }

    private MediaAcquireCandidate resolve(MediaAcquireCandidate candidate) {
        if (candidate.metadataResolved() || magnetResolver == null) {
            return candidate;
        }
        try {
            Optional<MagnetResolver.MagnetInfo> metadata = magnetResolver.resolve(candidate.url());
            return metadata.map(candidate::withMetadata).orElse(candidate);
        } catch (Exception e) {
            log.debug("media acquire metadata unavailable for provider {}: {}", candidate.provider(), e.getMessage());
            return candidate;
        }
    }

    private Optional<OfflineDownloadTask> completedTask(String mediaKey) {
        return offlineDownloadService.findMediaTask(mediaKey, COMPLETED)
                .filter(this::usable);
    }

    private boolean usable(OfflineDownloadTask task) {
        return task != null && !OfflineDownloadService.CLEANUP_DONE.equals(task.getCleanupState())
                && StringUtils.isNotBlank(task.getTargetPath());
    }

    private record ScoredCandidate(MediaAcquireCandidate candidate, MediaAcquirePolicy.Score score) {
    }

    public record AcquireResult(String status, MediaIdentity identity, String message,
                                String targetPath, boolean folder, MediaAcquireCandidate candidate,
                                int score, OfflineDownloadTask task) {
        public static AcquireResult completed(MediaIdentity identity, MediaAcquireCandidate candidate,
                                              int score, OfflineDownloadTask task, String taskName) {
            String path = task == null ? null : task.getTargetPath();
            return new AcquireResult(COMPLETED, identity, null, path, task != null && task.isFolder(),
                    candidate, score, task);
        }

        public static AcquireResult pending(MediaIdentity identity, MediaAcquireCandidate candidate,
                                            int score, String message) {
            return new AcquireResult(PENDING, identity, message, null, false, candidate, score, null);
        }

        public static AcquireResult pending(MediaIdentity identity, MediaAcquireCandidate candidate,
                                            int score, OfflineDownloadTask task) {
            return new AcquireResult(PENDING, identity, "正在获取资源", null, false, candidate, score, task);
        }

        public static AcquireResult failed(MediaIdentity identity, String message) {
            return new AcquireResult(FAILED, identity, message, null, false, null, 0, null);
        }
    }
}
