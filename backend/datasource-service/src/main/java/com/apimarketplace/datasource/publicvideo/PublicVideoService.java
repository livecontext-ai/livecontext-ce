package com.apimarketplace.datasource.publicvideo;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The product-film library as the public website reads it.
 *
 * <p>It exists so a new film can go live with no deploy: the editorial copy,
 * the chapters and the transcript live in ONE data source the team edits, and
 * the site reads it through {@link PublicVideoController}.
 *
 * <p><strong>Exactly one table, named in configuration.</strong> This is the
 * whole security argument and it must stay that way: the id and the tenant come
 * from properties, never from the request, so this is not a "read any table"
 * endpoint that happens to be called with one id today. Leave either property
 * unset (every self-hosted install, and any cloud environment that has not
 * opted in) and it serves an empty library rather than guessing.
 *
 * <p>Rows are filtered to {@code status = 'published'} in SQL, and every row is
 * projected into {@link PublicVideoDto}: a column the site does not know about
 * cannot reach it, and neither can a draft.
 */
@Service
public class PublicVideoService {

    private static final Logger log = LoggerFactory.getLogger(PublicVideoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * How many rows the library read will look at.
     *
     * <p>Films are published a few times a month, so this is a ceiling that will
     * not be reached, not a page size. It exists so a table that somehow grows
     * unbounded cannot turn a marketing page into an unbounded query.
     */
    private static final int MAX_ROWS = 200;

    /**
     * Hosts a poster may be served from.
     *
     * <p>The value is written by whoever edits the library, and it lands in an
     * {@code og:image}, a {@code thumbnailUrl} and an {@code <img src>} on a
     * public page. Anything else is dropped rather than rendered, so a wrong or
     * hostile value costs the poster and nothing else.
     */
    private static final Set<String> POSTER_HOSTS = Set.of(
            "livecontext.ai", "www.livecontext.ai", "i.ytimg.com", "img.youtube.com");

    private final CrudRepository crudRepository;
    private final Long dataSourceId;
    private final String tenantId;

    public PublicVideoService(
            CrudRepository crudRepository,
            @Value("${public.video-library.datasource-id:}") String dataSourceId,
            @Value("${public.video-library.tenant-id:}") String tenantId) {
        this.crudRepository = crudRepository;
        this.dataSourceId = parseId(dataSourceId);
        this.tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // Loud, because the symptom otherwise is a video section that is
            // simply empty, which looks exactly like "no films published yet".
            log.error("public.video-library.datasource-id is not a number ({}); the video library "
                    + "will serve nothing", raw);
            return null;
        }
    }

    /** True when this deployment has a library configured at all. */
    public boolean isConfigured() {
        return dataSourceId != null && tenantId != null;
    }

    /**
     * Every published film, newest first.
     *
     * <p>Never throws: this feeds a marketing page, so a bad row costs that row
     * and a failed read costs the section, not the page.
     */
    public List<PublicVideoDto> published() {
        if (!isConfigured()) return List.of();
        List<Map<String, Object>> rows;
        try {
            rows = crudRepository.readRows(
                    dataSourceId, tenantId,
                    new WhereCondition("status", "=", "published"),
                    MAX_ROWS, 0);
        } catch (Exception e) {
            log.warn("Public video library read failed, serving an empty library: {}", e.getMessage(), e);
            return List.of();
        }

        List<PublicVideoDto> films = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            PublicVideoDto film = toFilm(flatten(row));
            if (film != null) films.add(film);
        }
        // Newest first, on the film's own publication date rather than on the
        // row's: a film is often written days before its long cut goes up.
        films.sort(Comparator.comparing(PublicVideoDto::publishedAt, Comparator.reverseOrder()));
        return films;
    }

    /**
     * One published film, or null.
     *
     * <p>Filtered from the same list rather than queried by slug so there is one
     * definition of what "published" means and a page can never render a film
     * the library page does not list.
     */
    public PublicVideoDto bySlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        return published().stream().filter(f -> slug.equals(f.slug())).findFirst().orElse(null);
    }

    /**
     * A row is dropped, not half-rendered, when it is missing anything the page
     * cannot do without.
     *
     * <p>The film IS the page, so a row with no watchable id, no title or no
     * slug has nothing to show; publishing it would put an empty player at a URL
     * the sitemap then advertises.
     */
    private PublicVideoDto toFilm(Map<String, Object> row) {
        String slug = text(row.get("slug"));
        String title = text(row.get("title"));
        String youtubeId = text(row.get("youtube_id"));
        if (slug.isBlank() || title.isBlank() || youtubeId.isBlank()) {
            log.warn("Skipping a video library row with no slug, title or youtube id (slug={})", slug);
            return null;
        }
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            log.warn("Skipping video library row {}: the slug is not url-safe", slug);
            return null;
        }
        return new PublicVideoDto(
                slug,
                text(row.get("series")),
                title,
                text(row.get("tagline")),
                youtubeId,
                (int) Math.round(number(row.get("duration_seconds"))),
                text(row.get("published_at")),
                posterUrl(text(row.get("poster_url")), youtubeId),
                shareImageUrl(text(row.get("share_image_url")),
                        text(row.get("poster_url")), youtubeId),
                text(row.get("poster_alt")),
                paragraphs(text(row.get("problem"))),
                paragraphs(text(row.get("answer"))),
                lines(text(row.get("highlights"))),
                chapters(text(row.get("chapters"))),
                transcript(text(row.get("transcript"))),
                text(row.get("marketplace_slug")),
                text(row.get("marketplace_title")));
    }

    /**
     * The poster, or the film's own YouTube thumbnail.
     *
     * <p>The fallback is what makes "add a film without a deploy" true: a row
     * that names no poster still renders, with the plate the film already has on
     * YouTube, instead of an empty frame.
     */
    private String posterUrl(String configured, String youtubeId) {
        String allowed = allowedPoster(configured);
        return allowed != null ? allowed : youtubeThumbnail(youtubeId);
    }

    /** The URL if the site may show it, else null. Never throws on a bad value. */
    private String allowedPoster(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("https".equalsIgnoreCase(uri.getScheme()) && POSTER_HOSTS.contains(host)) {
                return url;
            }
            log.warn("Ignoring an image on a host the site does not serve images from: {}", host);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring an unparseable image url: {}", e.getMessage());
        }
        return null;
    }

    private static String youtubeThumbnail(String youtubeId) {
        return "https://i.ytimg.com/vi/" + youtubeId + "/maxresdefault.jpg";
    }

    /**
     * The frame a share card shows, always a format every scraper reads.
     *
     * <p>Separate from the poster because the two want different things: the
     * page wants the lightest image it can get (WebP), and LinkedIn, which the
     * footer of every page here links to, does not reliably render one. A share
     * card that fails is invisible rather than merely wrong, so this falls back
     * through the poster (only if it is already a JPEG) to the film's own
     * YouTube thumbnail, which always is one.
     */
    private String shareImageUrl(String configured, String poster, String youtubeId) {
        String share = allowedPoster(configured);
        if (share != null && isJpeg(share)) return share;
        String fallbackPoster = allowedPoster(poster);
        if (fallbackPoster != null && isJpeg(fallbackPoster)) return fallbackPoster;
        return youtubeThumbnail(youtubeId);
    }

    private static boolean isJpeg(String url) {
        String path = url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return path.endsWith(".jpg") || path.endsWith(".jpeg");
    }

    private List<PublicVideoDto.Chapter> chapters(String raw) {
        List<Map<String, Object>> parsed = parseList(raw, "chapters");
        List<PublicVideoDto.Chapter> chapters = new ArrayList<>(parsed.size());
        for (Map<String, Object> entry : parsed) {
            String title = text(entry.get("title"));
            if (title.isBlank()) continue;
            chapters.add(new PublicVideoDto.Chapter(
                    number(entry.get("start")), number(entry.get("end")), title));
        }
        chapters.sort(Comparator.comparingDouble(PublicVideoDto.Chapter::start));
        return chapters;
    }

    private List<PublicVideoDto.TranscriptLine> transcript(String raw) {
        List<Map<String, Object>> parsed = parseList(raw, "transcript");
        List<PublicVideoDto.TranscriptLine> lines = new ArrayList<>(parsed.size());
        for (Map<String, Object> entry : parsed) {
            String text = text(entry.get("text"));
            if (text.isBlank()) continue;
            lines.add(new PublicVideoDto.TranscriptLine(number(entry.get("t")), text));
        }
        lines.sort(Comparator.comparingDouble(PublicVideoDto.TranscriptLine::t));
        return lines;
    }

    /** A malformed cell costs that cell, never the film. */
    private List<Map<String, Object>> parseList(String raw, String what) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Ignoring an unparseable {} cell: {}", what, e.getMessage());
            return List.of();
        }
    }

    /** Paragraphs are separated by a blank line, the way the cell is written. */
    private static List<String> paragraphs(String raw) {
        return split(raw, "\\n\\s*\\n");
    }

    /** One entry per line. */
    private static List<String> lines(String raw) {
        return split(raw, "\\r?\\n");
    }

    private static List<String> split(String raw, String pattern) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(pattern))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(text(value));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** Merge the JSONB `data` column up into the row, like every other reader here. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> row) {
        Map<String, Object> flat = new LinkedHashMap<>();
        Object data = row.get("data");
        if (data instanceof Map<?, ?> map) {
            flat.putAll((Map<String, Object>) map);
        } else if (data != null) {
            try {
                flat.putAll(MAPPER.readValue(data.toString(), new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warn("Failed to parse a video library row: {}", e.getMessage());
            }
        }
        return flat;
    }
}
