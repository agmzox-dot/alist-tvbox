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
}
