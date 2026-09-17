package cn.har01d.alist_tvbox.service.acquire;

import java.util.List;

/** Small SPI for configured/authorized candidate sources. */
@FunctionalInterface
public interface MediaAcquireProvider {
    List<MediaAcquireCandidate> search(MediaIdentity identity, String title);

    default boolean supports(MediaIdentity identity) {
        return true;
    }

    default String name() {
        return getClass().getSimpleName();
    }
}
