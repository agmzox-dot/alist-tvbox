package cn.har01d.alist_tvbox.service.acquire;

import cn.har01d.alist_tvbox.service.magnet.MagnetResolver;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared pre-submit gate and deterministic quality ranking. */
public class MediaAcquirePolicy {
    public static final long GIB = 1024L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 20 * GIB;
    private static final long PREFERRED_MIN_BYTES = 8 * GIB;
    private static final long PREFERRED_MAX_BYTES = 18 * GIB;
    private static final long VERY_SMALL_4K_BYTES = 2 * GIB;
    private static final Pattern VIDEO = Pattern.compile("(?i)\\.(mp4|mkv|m4v|avi|ts|m2ts|webm|mov|wmv|flv)$");
    private static final Pattern EPISODE = Pattern.compile("(?i)(?:s\\d{1,2}e|e|ep|第)\\s*0*(\\d{1,4})");

    public Score score(MediaIdentity identity, String expectedTitle, MediaAcquireCandidate candidate) {
        return identity.isMovie()
                ? scoreMovie(expectedTitle, candidate)
                : scoreTv(expectedTitle, null, candidate);
    }

    public Score scoreMovie(String expectedTitle, MediaAcquireCandidate candidate) {
        List<String> reasons = new ArrayList<>();
        if (!titleMatches(expectedTitle, candidate)) {
            return reject("媒体标题明显不匹配", reasons);
        }
        List<MagnetResolver.MagnetFile> videos = videoFiles(candidate.files());
        MagnetResolver.MagnetFile main = videos.stream()
                .max(Comparator.comparingLong(MagnetResolver.MagnetFile::size)).orElse(null);
        if (candidate.metadataResolved() && main == null) {
            return reject("没有视频文件", reasons);
        }
        int score = qualityScore(candidateText(candidate), reasons);
        if (!candidate.metadataResolved()) {
            score -= 100;
            reasons.add("文件清单无法解析，降权");
        } else if (main != null) {
            if (main.size() > MAX_FILE_BYTES) {
                return reject("电影主视频超过20GiB", reasons);
            }
            score += movieSizeScore(main.size(), candidateText(candidate), reasons);
        }
        score += codecScore(candidateText(candidate), reasons);
        return accept(score, reasons, main == null ? 0 : main.size());
    }

    public Score scoreTv(String expectedTitle, Integer targetEpisode, MediaAcquireCandidate candidate) {
        List<String> reasons = new ArrayList<>();
        if (!titleMatches(expectedTitle, candidate)) {
            return reject("媒体标题明显不匹配", reasons);
        }
        List<MagnetResolver.MagnetFile> videos = videoFiles(candidate.files());
        if (candidate.metadataResolved() && videos.isEmpty()) {
            return reject("没有视频文件", reasons);
        }
        MagnetResolver.MagnetFile target = null;
        if (targetEpisode != null && !videos.isEmpty()) {
            target = videos.stream()
                    .filter(file -> episodeOf(file.path()) == targetEpisode)
                    .max(Comparator.comparingLong(MagnetResolver.MagnetFile::size)).orElse(null);
            if (target == null) {
                reasons.add("目标单集未在文件清单中验证");
            }
        }
        MagnetResolver.MagnetFile main = target != null ? target
                : videos.stream().max(Comparator.comparingLong(MagnetResolver.MagnetFile::size)).orElse(null);
        if (!candidate.metadataResolved()) {
            reasons.add("文件清单无法解析，降权");
        } else if (target != null && target.size() > MAX_FILE_BYTES) {
            return reject("TV目标单集超过20GiB", reasons);
        }
        int score = qualityScore(candidateText(candidate), reasons) + codecScore(candidateText(candidate), reasons);
        if (!candidate.metadataResolved()) {
            score -= 100;
        }
        return accept(score, reasons, main == null ? 0 : main.size());
    }

    public static boolean isVideoFile(String path) {
        return StringUtils.isNotBlank(path) && VIDEO.matcher(path.trim()).find();
    }

    private static List<MagnetResolver.MagnetFile> videoFiles(List<MagnetResolver.MagnetFile> files) {
        return files.stream().filter(file -> file != null && isVideoFile(file.path())).toList();
    }

    private static int qualityScore(String text, List<String> reasons) {
        String value = text.toLowerCase(Locale.ROOT);
        if (contains(value, "dolby vision", "dolbyvision") || word(value, "dv")) {
            reasons.add("4K Dolby Vision");
            return value.matches(".*(2160p|4k|uhd).*" ) ? 400 : 260;
        }
        if (contains(value, "hdr10", "hdr 10")) {
            reasons.add("4K HDR10");
            return value.matches(".*(2160p|4k|uhd).*" ) ? 300 : 220;
        }
        if (value.matches(".*(2160p|4k|uhd).*")) {
            reasons.add("优质4K SDR");
            return 200;
        }
        if (value.contains("1080p")) {
            reasons.add("高质量1080P");
            return 100;
        }
        if (value.contains("720p")) {
            reasons.add("720P");
            return 50;
        }
        reasons.add("清晰度未标注");
        return 0;
    }

    private static int codecScore(String text, List<String> reasons) {
        String value = text.toLowerCase(Locale.ROOT);
        int score = 0;
        if (contains(value, "hevc", "h.265", "h265", "x265")) {
            score += 20;
            reasons.add("HEVC/H.265");
        }
        if (contains(value, "10bit", "10-bit")) {
            score += 10;
            reasons.add("10bit");
        }
        if (contains(value, "atmos")) {
            reasons.add("Atmos不加分");
        }
        if (contains(value, "truehd", "dts-hd", "dts:x", "pcm", "flac")) {
            score -= 5;
            reasons.add("大型无损音轨轻微降权");
        }
        return score;
    }

    private static int movieSizeScore(long size, String text, List<String> reasons) {
        if (size >= PREFERRED_MIN_BYTES && size <= PREFERRED_MAX_BYTES) {
            reasons.add("电影体积处于8~18GiB优先区间");
            return 25;
        }
        if (size > PREFERRED_MAX_BYTES && size <= MAX_FILE_BYTES) {
            reasons.add("电影体积18~20GiB，允许");
            return 0;
        }
        if (size < VERY_SMALL_4K_BYTES && text.matches("(?is).*\\b(?:4k|2160p|uhd)\\b.*")) {
            reasons.add("4K体积偏小，降权");
            return -125;
        }
        return 0;
    }

    private static boolean titleMatches(String expectedTitle, MediaAcquireCandidate candidate) {
        if (StringUtils.isBlank(expectedTitle)) {
            return true;
        }
        String expected = normalize(expectedTitle);
        String actual = normalize(candidate.title() + " " + candidate.torrentName());
        if (expected.isEmpty() || actual.isEmpty()) {
            return true;
        }
        return actual.contains(expected) || expected.contains(actual)
                || expected.chars().filter(Character::isLetterOrDigit).count() <= 3
                && actual.contains(expected);
    }

    private static String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\[\\]【】()（）._-]+", "")
                .replaceAll("(?i)(2160p|1080p|720p|4k|uhd|hdr10|hdr|dv|hevc|h265|x265|remux|web-dl|bluray)", "")
                .replaceAll("\\s+", "");
    }

    private static String candidateText(MediaAcquireCandidate candidate) {
        StringBuilder value = new StringBuilder(candidate.title()).append(' ').append(candidate.torrentName());
        for (MagnetResolver.MagnetFile file : candidate.files()) {
            if (file != null) {
                value.append(' ').append(file.path());
            }
        }
        return value.toString();
    }

    private static Integer episodeOf(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        var matcher = EPISODE.matcher(path);
        Integer result = null;
        while (matcher.find()) {
            result = Integer.valueOf(matcher.group(1));
        }
        return result;
    }

    private static boolean contains(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean word(String value, String term) {
        return Pattern.compile("(?i)(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])").matcher(value).find();
    }

    private static Score reject(String reason, List<String> reasons) {
        reasons.add(reason);
        return new Score(false, Integer.MIN_VALUE, List.copyOf(reasons), 0);
    }

    private static Score accept(int score, List<String> reasons, long mainVideoBytes) {
        return new Score(true, score, List.copyOf(reasons), mainVideoBytes);
    }

    public record Score(boolean accepted, int value, List<String> reasons, long mainVideoBytes) {
        public boolean rejected() {
            return !accepted;
        }
    }
}
