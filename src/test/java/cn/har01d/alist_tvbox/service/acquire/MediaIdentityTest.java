package cn.har01d.alist_tvbox.service.acquire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MediaIdentityTest {
    @Test
    void movieAndTvWithSameTmdbIdRemainDifferentKeys() {
        MediaIdentity movie = MediaIdentity.parse("tmdb:movie:27205");
        MediaIdentity tv = MediaIdentity.parse("tmdb:tv:27205");

        assertEquals("tmdb:movie:27205", movie.key());
        assertEquals("tmdb:tv:27205", tv.key());
        assertNotEquals(movie.key(), tv.key());
        assertEquals("movie", movie.mediaType());
        assertEquals("tv", tv.mediaType());
    }

    @Test
    void episodeKeyUsesStableTmdbTvFormatWithoutChangingBaseIdentity() {
        assertEquals("tmdb:tv:1399:s2:e7", MediaIdentity.episodeKey("tmdb", "tv", "1399", 2, 7));
        assertEquals("tmdb:tv:1399", MediaIdentity.tv(1399).key());
        assertEquals(null, MediaIdentity.episodeKey("douban", "tv", "1399", 2, 7));
        assertEquals(null, MediaIdentity.episodeKey("tmdb", "tv", "", 2, 7));
    }
}
