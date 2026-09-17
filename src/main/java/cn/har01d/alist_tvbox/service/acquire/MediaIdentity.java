package cn.har01d.alist_tvbox.service.acquire;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

/** Stable media identity. The media type is part of the key by design. */
public record MediaIdentity(String provider, String mediaType, String id) {
    public MediaIdentity {
        provider = normalize(provider, "provider").toLowerCase(java.util.Locale.ROOT);
        mediaType = normalize(mediaType, "mediaType").toLowerCase(java.util.Locale.ROOT);
        id = normalize(id, "id");
        if ("tmdb".equals(provider) && !"movie".equals(mediaType) && !"tv".equals(mediaType)) {
            throw new IllegalArgumentException("Unsupported TMDB media type: " + mediaType);
        }
        if ("tmdb".equals(provider) && !id.matches("\\d+")) {
            throw new IllegalArgumentException("TMDB id must be numeric");
        }
    }

    public static MediaIdentity tmdb(String mediaType, int id) {
        if (id < 1) {
            throw new IllegalArgumentException("TMDB id must be positive");
        }
        return new MediaIdentity("tmdb", mediaType, String.valueOf(id));
    }

    public static MediaIdentity movie(int id) {
        return tmdb("movie", id);
    }

    public static MediaIdentity tv(int id) {
        return tmdb("tv", id);
    }

    /** Episode identity is deliberately separate from the three-part media identity parser. */
    public static String episodeKey(String provider, String mediaType, String id, Integer season, Integer episode) {
        if (!"tmdb".equalsIgnoreCase(provider) || !"tv".equalsIgnoreCase(mediaType)
                || StringUtils.isBlank(id) || !id.trim().matches("\\d+")
                || season == null || season < 1 || episode == null || episode < 1) {
            return null;
        }
        return "tmdb:tv:" + id.trim() + ":s" + season + ":e" + episode;
    }

    public static MediaIdentity parse(String key) {
        String[] parts = Objects.requireNonNull(key, "key").trim().split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid media identity: " + key);
        }
        return new MediaIdentity(parts[0], parts[1], parts[2]);
    }

    public String key() {
        return provider + ":" + mediaType + ":" + id;
    }

    public boolean isMovie() {
        return "movie".equals(mediaType);
    }

    public boolean isTv() {
        return "tv".equals(mediaType);
    }

    @Override
    public String toString() {
        return key();
    }

    private static String normalize(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.contains(":") && "id".equals(field)) {
            throw new IllegalArgumentException("Invalid media identity " + field);
        }
        return normalized;
    }
}
