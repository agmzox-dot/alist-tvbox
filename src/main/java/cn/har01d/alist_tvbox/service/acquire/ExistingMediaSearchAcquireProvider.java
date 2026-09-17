package cn.har01d.alist_tvbox.service.acquire;

import cn.har01d.alist_tvbox.service.MediaSubscriptionCheckService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Bridge to the repository's existing configured offline search capability. */
@Component
public class ExistingMediaSearchAcquireProvider implements MediaAcquireProvider {
    private final MediaSubscriptionCheckService checkService;

    public ExistingMediaSearchAcquireProvider(MediaSubscriptionCheckService checkService) {
        this.checkService = checkService;
    }

    @Override
    public String name() {
        return "existing-offline-search";
    }

    @Override
    public List<MediaAcquireCandidate> search(MediaIdentity identity, String title) {
        return checkService.searchMediaAcquireCandidates(title).stream()
                .map(item -> new MediaAcquireCandidate(
                        String.valueOf(item.getOrDefault("source", name())),
                        String.valueOf(item.getOrDefault("title", title)),
                        String.valueOf(item.getOrDefault("link", ""))))
                .toList();
    }
}
