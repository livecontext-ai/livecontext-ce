package com.apimarketplace.datasource.publicvideo;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicVideoServiceTest {

    private static final long LIBRARY_ID = 264L;
    private static final String TENANT = "1";

    private final CrudRepository crudRepository = mock(CrudRepository.class);

    private PublicVideoService service(String dataSourceId, String tenantId) {
        return new PublicVideoService(crudRepository, dataSourceId, tenantId);
    }

    private PublicVideoService configured() {
        return service(String.valueOf(LIBRARY_ID), TENANT);
    }

    /** A library row as the repository hands it back: system columns, data as JSON text. */
    private Map<String, Object> row(Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 4639L);
        row.put("data", toJson(data));
        return row;
    }

    private static String toJson(Map<String, Object> data) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> film() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slug", "automate-x");
        data.put("status", "published");
        data.put("series", "LiveContext product films");
        data.put("title", "Automate X, end to end");
        data.put("tagline", "A five minute film about X.");
        data.put("youtube_id", "lG-wfKo2NOo");
        data.put("duration_seconds", 318);
        data.put("published_at", "2026-09-10T15:57:19Z");
        data.put("poster_url", "https://livecontext.ai/videos/automate-x.webp");
        data.put("share_image_url", "https://livecontext.ai/videos/automate-x.jpg");
        data.put("poster_alt", "The X screen");
        data.put("problem", "First paragraph.\n\nSecond paragraph.");
        data.put("answer", "The answer.");
        data.put("highlights", "One claim\nAnother claim");
        data.put("chapters", "[{\"start\":0,\"end\":15.4,\"title\":\"The problem\"}]");
        data.put("transcript", "[{\"t\":0.15,\"text\":\"You posted the role.\"}]");
        data.put("marketplace_slug", "x-app");
        data.put("marketplace_title", "X App");
        return data;
    }

    private void returning(Map<String, Object>... films) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> film : films) rows.add(row(film));
        when(crudRepository.readRows(anyLong(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(rows);
    }

    @Nested
    @DisplayName("what leaves the service")
    class Projection {

        @Test
        @DisplayName("maps a library row into the shape the public pages read")
        void mapsARow() {
            returning(film());

            PublicVideoDto video = configured().published().get(0);

            assertThat(video.slug()).isEqualTo("automate-x");
            assertThat(video.title()).isEqualTo("Automate X, end to end");
            assertThat(video.youtubeId()).isEqualTo("lG-wfKo2NOo");
            assertThat(video.durationSeconds()).isEqualTo(318);
            assertThat(video.publishedAt()).isEqualTo("2026-09-10T15:57:19Z");
            assertThat(video.marketplaceSlug()).isEqualTo("x-app");
            assertThat(video.marketplaceTitle()).isEqualTo("X App");
        }

        @Test
        @DisplayName("splits the problem on blank lines and the highlights on newlines")
        void splitsProse() {
            returning(film());

            PublicVideoDto video = configured().published().get(0);

            assertThat(video.problem()).containsExactly("First paragraph.", "Second paragraph.");
            assertThat(video.answer()).containsExactly("The answer.");
            assertThat(video.highlights()).containsExactly("One claim", "Another claim");
        }

        @Test
        @DisplayName("parses the chapters and the transcript out of their cells")
        void parsesJsonCells() {
            returning(film());

            PublicVideoDto video = configured().published().get(0);

            assertThat(video.chapters())
                    .containsExactly(new PublicVideoDto.Chapter(0d, 15.4d, "The problem"));
            assertThat(video.transcript())
                    .containsExactly(new PublicVideoDto.TranscriptLine(0.15d, "You posted the role."));
        }

        @Test
        @DisplayName("orders chapters and transcript by time, whatever order the cell was written in")
        void ordersByTime() {
            Map<String, Object> film = film();
            film.put("chapters", "[{\"start\":20,\"end\":30,\"title\":\"Second\"},"
                    + "{\"start\":0,\"end\":20,\"title\":\"First\"}]");
            film.put("transcript", "[{\"t\":9,\"text\":\"Later.\"},{\"t\":1,\"text\":\"Earlier.\"}]");
            returning(film);

            PublicVideoDto video = configured().published().get(0);

            assertThat(video.chapters()).extracting(PublicVideoDto.Chapter::title)
                    .containsExactly("First", "Second");
            assertThat(video.transcript()).extracting(PublicVideoDto.TranscriptLine::text)
                    .containsExactly("Earlier.", "Later.");
        }

        @Test
        @DisplayName("serves the newest film first, on the film's date and not the row's")
        void newestFirst() {
            Map<String, Object> older = film();
            older.put("slug", "older");
            older.put("published_at", "2026-01-01T00:00:00Z");
            Map<String, Object> newer = film();
            newer.put("slug", "newer");
            newer.put("published_at", "2026-09-10T00:00:00Z");
            returning(older, newer);

            assertThat(configured().published()).extracting(PublicVideoDto::slug)
                    .containsExactly("newer", "older");
        }
    }

    @Nested
    @DisplayName("what never leaves the service")
    class Gating {

        @Test
        @DisplayName("asks the database for published rows only, rather than filtering after")
        void filtersInSql() {
            returning(film());

            configured().published();

            ArgumentCaptor<WhereCondition> where = ArgumentCaptor.forClass(WhereCondition.class);
            verify(crudRepository).readRows(anyLong(), anyString(), where.capture(), anyInt(), anyInt());
            assertThat(where.getValue().column()).isEqualTo("status");
            assertThat(where.getValue().operator()).isEqualTo("=");
            assertThat(where.getValue().value()).isEqualTo("published");
        }

        @Test
        @DisplayName("reads the ONE data source configured, never one the caller names")
        void readsTheConfiguredLibraryOnly() {
            returning(film());

            configured().published();

            verify(crudRepository).readRows(
                    org.mockito.ArgumentMatchers.eq(LIBRARY_ID),
                    org.mockito.ArgumentMatchers.eq(TENANT),
                    any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("serves nothing, and asks nothing, when no library is configured")
        void unconfiguredServesNothing() {
            // Every self-hosted install. An empty answer, not a guess at a table id.
            assertThat(service("", "").isConfigured()).isFalse();
            assertThat(service("", "").published()).isEmpty();
            assertThat(service(String.valueOf(LIBRARY_ID), "").published()).isEmpty();
            assertThat(service("", TENANT).published()).isEmpty();
            verify(crudRepository, never()).readRows(anyLong(), anyString(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("serves nothing when the configured id is not a number")
        void unparseableIdServesNothing() {
            assertThat(service("not-a-number", TENANT).isConfigured()).isFalse();
            assertThat(service("not-a-number", TENANT).published()).isEmpty();
        }

        @Test
        @DisplayName("drops a row with no film to show instead of publishing an empty player")
        void dropsRowsWithNothingToShow() {
            Map<String, Object> noId = film();
            noId.put("slug", "no-id");
            noId.put("youtube_id", "");
            Map<String, Object> noTitle = film();
            noTitle.put("slug", "no-title");
            noTitle.put("title", "");
            Map<String, Object> noSlug = film();
            noSlug.put("slug", "");
            returning(noId, noTitle, noSlug, film());

            assertThat(configured().published()).extracting(PublicVideoDto::slug)
                    .containsExactly("automate-x");
        }

        @Test
        @DisplayName("drops a row whose slug would not be a url")
        void dropsUnsafeSlugs() {
            Map<String, Object> unsafe = film();
            unsafe.put("slug", "../../etc/passwd");
            returning(unsafe);

            assertThat(configured().published()).isEmpty();
        }

        @Test
        @DisplayName("answers an empty library rather than failing when the read blows up")
        void degradesOnReadFailure() {
            // This feeds a marketing page: the section disappears, the page does not.
            when(crudRepository.readRows(anyLong(), anyString(), any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalStateException("database is down"));

            assertThat(configured().published()).isEmpty();
        }

        @Test
        @DisplayName("keeps the film when one of its cells is malformed")
        void malformedCellCostsTheCellOnly() {
            Map<String, Object> film = film();
            film.put("chapters", "{not json at all");
            returning(film);

            PublicVideoDto video = configured().published().get(0);

            assertThat(video.chapters()).isEmpty();
            assertThat(video.transcript()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("the poster")
    class Poster {

        @Test
        @DisplayName("keeps a poster served from a host the site serves images from")
        void keepsAllowedHost() {
            returning(film());

            assertThat(configured().published().get(0).posterUrl())
                    .isEqualTo("https://livecontext.ai/videos/automate-x.webp");
        }

        @Test
        @DisplayName("falls back to the film's own YouTube thumbnail when none is named")
        void fallsBackToYouTube() {
            // This is what makes "add a film with no deploy" true: a row that
            // names no poster still renders, with the plate the film already has.
            Map<String, Object> film = film();
            film.put("poster_url", "");
            returning(film);

            assertThat(configured().published().get(0).posterUrl())
                    .isEqualTo("https://i.ytimg.com/vi/lG-wfKo2NOo/maxresdefault.jpg");
        }

        @Test
        @DisplayName("shares the JPEG, which every scraper reads, not the page's WebP")
        void shareImageIsTheJpeg() {
            returning(film());

            PublicVideoDto video = configured().published().get(0);

            assertThat(video.posterUrl()).endsWith(".webp");
            assertThat(video.shareImageUrl()).isEqualTo("https://livecontext.ai/videos/automate-x.jpg");
        }

        @Test
        @DisplayName("falls back past a WebP poster to the YouTube thumbnail for the share card")
        void shareImageNeverEndsUpWebp() {
            // A share card that fails renders as nothing at all, so it must never
            // be handed a format a scraper might not read.
            Map<String, Object> film = film();
            film.put("share_image_url", "");
            returning(film);

            assertThat(configured().published().get(0).shareImageUrl())
                    .isEqualTo("https://i.ytimg.com/vi/lG-wfKo2NOo/maxresdefault.jpg");
        }

        @Test
        @DisplayName("uses a JPEG poster for the share card when no share image is named")
        void shareImageFallsBackToAJpegPoster() {
            Map<String, Object> film = film();
            film.put("share_image_url", "");
            film.put("poster_url", "https://livecontext.ai/videos/automate-x.jpg");
            returning(film);

            assertThat(configured().published().get(0).shareImageUrl())
                    .isEqualTo("https://livecontext.ai/videos/automate-x.jpg");
        }

        @Test
        @DisplayName("refuses a share image from a foreign host, like the poster")
        void shareImageHostIsChecked() {
            Map<String, Object> film = film();
            film.put("share_image_url", "https://evil.example/tracker.jpg");
            returning(film);

            assertThat(configured().published().get(0).shareImageUrl())
                    .isEqualTo("https://i.ytimg.com/vi/lG-wfKo2NOo/maxresdefault.jpg");
        }

        @Test
        @DisplayName("refuses a poster from anywhere else, so a row cannot put a stranger's image on the site")
        void refusesForeignHosts() {
            for (String hostile : List.of(
                    "https://evil.example/tracker.gif",
                    "http://livecontext.ai/videos/x.webp",
                    "javascript:alert(1)",
                    "//evil.example/x.png")) {
                Map<String, Object> film = film();
                film.put("poster_url", hostile);
                returning(film);

                assertThat(configured().published().get(0).posterUrl())
                        .as("poster_url %s", hostile)
                        .isEqualTo("https://i.ytimg.com/vi/lG-wfKo2NOo/maxresdefault.jpg");
            }
        }
    }

    @Nested
    @DisplayName("one film")
    class BySlug {

        @Test
        @DisplayName("returns the published film at that slug")
        void returnsPublished() {
            returning(film());

            assertThat(configured().bySlug("automate-x")).isNotNull();
        }

        @Test
        @DisplayName("returns nothing for a slug the library does not publish")
        void returnsNothingOtherwise() {
            returning(film());

            assertThat(configured().bySlug("no-such-film")).isNull();
            assertThat(configured().bySlug("")).isNull();
            assertThat(configured().bySlug(null)).isNull();
        }
    }
}
