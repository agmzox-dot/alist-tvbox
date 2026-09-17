package cn.har01d.alist_tvbox.service.acquire;

import cn.har01d.alist_tvbox.service.magnet.MagnetResolver;

import java.util.List;

/** Provider result. Providers only return a candidate; parsing and scoring stay in the core. */
public final class MediaAcquireCandidate {
    private final String provider;
    private final String title;
    private final String url;
    private final MagnetResolver.MagnetInfo metadata;

    public MediaAcquireCandidate(String provider, String title, String url) {
        this(provider, title, url, null);
    }

    public MediaAcquireCandidate(String provider, String title, String url,
                                 MagnetResolver.MagnetInfo metadata) {
        this.provider = provider == null ? "" : provider.trim();
        this.title = title == null ? "" : title.trim();
        this.url = url == null ? "" : url.trim();
        this.metadata = metadata;
    }

    public String provider() {
        return provider;
    }

    public String title() {
        return title;
    }

    public String url() {
        return url;
    }

    public MagnetResolver.MagnetInfo metadata() {
        return metadata;
    }

    public boolean metadataResolved() {
        return metadata != null;
    }

    public String torrentName() {
        return metadata == null ? "" : metadata.name();
    }

    public List<MagnetResolver.MagnetFile> files() {
        return metadata == null || metadata.files() == null ? List.of() : metadata.files();
    }

    public long totalSize() {
        return metadata == null ? 0 : metadata.totalSize();
    }

    public MediaAcquireCandidate withMetadata(MagnetResolver.MagnetInfo value) {
        return new MediaAcquireCandidate(provider, title, url, value);
    }
}
