package cn.har01d.alist_tvbox.service.acquire;

import cn.har01d.alist_tvbox.service.magnet.MagnetResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaAcquirePolicyTest {
    private final MediaAcquirePolicy policy = new MediaAcquirePolicy();

    @Test
    void fourteenGib4kDolbyVisionHevcRanksAbove1080p() {
        var dv = movie("Dune 4K DV HEVC 10bit", 14, "Dune.2160p.DV.HEVC.mkv");
        var hd = movie("Dune 1080P", 10, "Dune.1080p.mkv");

        var dvScore = policy.scoreMovie("Dune", dv);
        var hdScore = policy.scoreMovie("Dune", hd);

        assertTrue(dvScore.accepted());
        assertTrue(dvScore.value() > hdScore.value());
        assertTrue(dvScore.reasons().contains("4K Dolby Vision"));
    }

    @Test
    void movieMainVideoOverTwentyGibIsRejected() {
        var result = policy.scoreMovie("Dune", movie("Dune 4K", 21, "Dune.2160p.mkv"));

        assertTrue(result.rejected());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("超过20GiB")));
    }

    @Test
    void highCompressionEncodeRemainsEligible() {
        var result = policy.scoreMovie("Dune", movie("Dune 4K HEVC encode", 3, "Dune.2160p.x265.mkv"));

        assertTrue(result.accepted());
    }

    @Test
    void verySmall4kCanBeDowngraded() {
        var tiny4k = policy.scoreMovie("Dune", movie("Dune 4K", 1, "Dune.2160p.mkv"));
        var ordinary1080p = policy.scoreMovie("Dune", movie("Dune 1080P", 4, "Dune.1080p.mkv"));

        assertTrue(tiny4k.accepted());
        assertTrue(tiny4k.value() < ordinary1080p.value());
        assertTrue(tiny4k.reasons().contains("4K体积偏小，降权"));
    }

    @Test
    void atmosDoesNotAddScoreAndTrueHdIsSlightlyLower() {
        var base = policy.scoreMovie("Dune", movie("Dune 4K HEVC", 14, "Dune.2160p.HEVC.mkv"));
        var atmos = policy.scoreMovie("Dune", movie("Dune 4K HEVC Atmos", 14, "Dune.2160p.HEVC.mkv"));
        var trueHd = policy.scoreMovie("Dune", movie("Dune 4K HEVC TrueHD", 14, "Dune.2160p.HEVC.mkv"));

        assertEquals(base.value(), atmos.value());
        assertTrue(trueHd.value() < base.value());
        assertTrue(atmos.reasons().contains("Atmos不加分"));
        assertTrue(trueHd.reasons().contains("大型无损音轨轻微降权"));
    }

    @Test
    void tvSeasonTotalOverTwentyGibIsAllowedWhenTargetEpisodeFits() {
        var candidate = candidate("Show S01 complete", new MagnetResolver.MagnetInfo(
                "hash", "Show S01 complete", 24 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.S01E01.mkv", 12 * MediaAcquirePolicy.GIB),
                        new MagnetResolver.MagnetFile("Show.S01E02.mkv", 12 * MediaAcquirePolicy.GIB))));

        var result = policy.scoreTv("Show", 1, candidate);

        assertTrue(result.accepted());
    }

    @Test
    void eightyGibSeasonIsAllowedWhenTargetEpisodeFits() {
        var candidate = candidate("Show S01", new MagnetResolver.MagnetInfo(
                "hash", "Show S01", 80 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.S01E03.mkv", 12 * MediaAcquirePolicy.GIB))));

        assertTrue(policy.scoreTv("Show", 1, 3, candidate).accepted());
    }

    @Test
    void tvTargetEpisodeOverTwentyGibIsRejected() {
        var candidate = candidate("Show S01", new MagnetResolver.MagnetInfo(
                "hash", "Show S01", 21 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.S01E01.mkv", 21 * MediaAcquirePolicy.GIB))));

        var result = policy.scoreTv("Show", 1, candidate);

        assertTrue(result.rejected());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("目标单集超过20GiB")));
    }

    @Test
    void tvTorrentWithOtherRecognizedEpisodesButNoTargetIsRejected() {
        var candidate = candidate("Show S01", new MagnetResolver.MagnetInfo(
                "hash", "Show S01", 24 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.S01E01.mkv", 12 * MediaAcquirePolicy.GIB),
                        new MagnetResolver.MagnetFile("Show.S01E02.mkv", 12 * MediaAcquirePolicy.GIB))));

        assertTrue(policy.scoreTv("Show", 1, 3, candidate).rejected());
    }

    @Test
    void tvTorrentWithoutRecognizableEpisodeNumberIsAcceptedButDowngraded() {
        var candidate = candidate("Show season pack", new MagnetResolver.MagnetInfo(
                "hash", "Show season pack", 80 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.video.mkv", 12 * MediaAcquirePolicy.GIB))));

        var result = policy.scoreTv("Show", 1, 3, candidate);

        assertTrue(result.accepted());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("无法识别集号")));
        var resolved = candidate.withMetadata(new MagnetResolver.MagnetInfo("hash", "Show S01E03 1080P",
                12 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.S01E03.1080p.mkv", 12 * MediaAcquirePolicy.GIB))));
        assertTrue(result.value() < policy.scoreTv("Show", 1, 3, resolved).value());
    }

    @Test
    void tvTorrentFromAnotherRecognizedSeasonIsRejected() {
        var candidate = candidate("Show S02", new MagnetResolver.MagnetInfo(
                "hash", "Show S02", 12 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Show.S02E03.mkv", 12 * MediaAcquirePolicy.GIB))));

        var result = policy.scoreTv("Show", 1, 3, candidate);

        assertTrue(result.rejected());
        assertTrue(result.reasons().contains("明显错误季"));
    }

    @Test
    void candidateWithoutVideoIsRejectedAndUnresolvedMetadataIsLowerRanked() {
        var noVideo = candidate("Dune 4K", new MagnetResolver.MagnetInfo(
                "hash", "Dune 4K", 2 * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile("Dune.nfo", 1000))));
        var unresolved = new MediaAcquireCandidate("source", "Dune 1080P", "magnet:?xt=urn:btih:abc");
        var resolved = movie("Dune 720P", 2, "Dune.720p.mkv");

        var noVideoScore = policy.scoreMovie("Dune", noVideo);
        var unresolvedScore = policy.scoreMovie("Dune", unresolved);
        var resolvedScore = policy.scoreMovie("Dune", resolved);

        assertTrue(noVideoScore.rejected());
        assertTrue(unresolvedScore.accepted());
        assertTrue(unresolvedScore.value() < resolvedScore.value());
    }

    private static MediaAcquireCandidate movie(String title, int gib, String file) {
        return candidate(title, new MagnetResolver.MagnetInfo(
                "hash", title, gib * MediaAcquirePolicy.GIB,
                List.of(new MagnetResolver.MagnetFile(file, gib * MediaAcquirePolicy.GIB))));
    }

    private static MediaAcquireCandidate candidate(String title, MagnetResolver.MagnetInfo info) {
        return new MediaAcquireCandidate("source", title, "magnet:?xt=urn:btih:abc", info);
    }
}
