package com.apimarketplace.datasource.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceClient")
class DataSourceClientTest {

    @Mock
    private RestTemplate restTemplate;

    private DataSourceClient dataSourceClient;

    private static final String BASE_URL = "http://localhost:8088";
    private static final String TENANT_ID = "auth0|tenant-test";

    @BeforeEach
    void setUp() {
        dataSourceClient = new DataSourceClient(restTemplate, BASE_URL);
    }

    @Nested
    @DisplayName("findByIdAndTenantId")
    class FindByIdAndTenantId {

        @Test
        @DisplayName("sets explicit organization header for scoped fetch")
        void findByIdAndTenantIdSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenReturn(ResponseEntity.ok(new DataSourceDto(dataSourceId, TENANT_ID, "Table", null,
                            null, null, null, null, null, null, null, null, null, null, null, organizationId)));

            dataSourceClient.findByIdAndTenantId(dataSourceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/" + dataSourceId + "/by-tenant"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(DataSourceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("getItemsPage - LC-066: whether a loaded row is RESTRICTED")
    class GetItemsPage {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void answer(ResponseEntity response) {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class))).thenReturn(response);
        }

        private List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> oneRow() {
            return List.of(new com.apimarketplace.datasource.client.dto.DataSourceItemDto(
                    7L, 42L, TENANT_ID, java.util.Map.of("subject", "Wire approved"), 1, null));
        }

        @Test
        @DisplayName("regression: the RESTRICTED header flags the page")
        void restrictedHeaderFlagsThePage() {
            answer(ResponseEntity.ok().header(DataSourceClient.DATA_SENSITIVITY_HEADER, "RESTRICTED").body(oneRow()));

            var page = dataSourceClient.getItemsPage(42L, TENANT_ID, 0, 50);

            assertThat(page.restricted()).isTrue();
            assertThat(page.items()).hasSize(1);
        }

        @Test
        @DisplayName("no header: an ordinary page; getItems returns the same rows")
        void noHeaderIsAnOrdinaryPage() {
            answer(ResponseEntity.ok(oneRow()));

            assertThat(dataSourceClient.getItemsPage(42L, TENANT_ID, 0, 50).restricted()).isFalse();
            assertThat(dataSourceClient.getItems(42L, TENANT_ID, 0, 50)).hasSize(1);
        }

        @Test
        @DisplayName("a failed read is an empty, unflagged page (as getItems always answered)")
        void failedReadIsAnEmptyPage() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenThrow(new ResourceAccessException("down"));

            var page = dataSourceClient.getItemsPage(42L, TENANT_ID, 0, 50);

            assertThat(page.items()).isEmpty();
            assertThat(page.restricted()).isFalse();
        }
    }

    @Nested
    @DisplayName("bulkFind")
    class BulkFind {

        @Test
        @DisplayName("sets explicit organization header for scoped bulk fetch")
        void bulkFindSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of(new DataSourceDto(dataSourceId, TENANT_ID, "Table", null,
                            null, null, null, null, null, null, null, null, null, null, null, organizationId))));

            dataSourceClient.bulkFind(List.of(dataSourceId), TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/bulk-find"),
                    eq(HttpMethod.POST), entityCaptor.capture(), any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("getAllItems")
    class GetAllItems {

        @Test
        @DisplayName("sets explicit organization header for scoped item snapshot")
        void getAllItemsSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of()));

            dataSourceClient.getAllItems(dataSourceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/" + dataSourceId
                            + "/items?offset=0&limit=500&excludeRestricted=true"),
                    eq(HttpMethod.GET), entityCaptor.capture(), any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("regression (LC-066): every publication copy asks datasource-service to leave RESTRICTED rows out")
        void getAllItemsAsksToExcludeRestrictedRows() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of()));

            dataSourceClient.getAllItems(42L, TENANT_ID);

            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(url.getValue()).contains("excludeRestricted=true");
        }

        @Test
        @DisplayName("a failed copy read answers an empty list: nothing is copied rather than an unfiltered page")
        void getAllItemsFailureCopiesNothing() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("regression (LC-066 rollout): a read that confirms the RESTRICTED filter returns its rows")
        void getAllItemsReturnsRowsWhenTheFilterIsConfirmed() {
            com.apimarketplace.datasource.client.dto.DataSourceItemDto row = new com.apimarketplace.datasource.client.dto.DataSourceItemDto(
                    7L, 42L, TENANT_ID, java.util.Map.of("subject", "Hi"), 1, null);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok()
                            .header(DataSourceClient.RESTRICTED_EXCLUDED_HEADER, "true")
                            .header(DataSourceClient.COPY_KEYSET_HEADER, "true")
                            .body(List.of(row)));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).containsExactly(row);
        }

        @Test
        @DisplayName("a table that IS empty is an empty copy, not a failure")
        void emptyTableIsAnEmptyCopy() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok()
                            .header(DataSourceClient.RESTRICTED_EXCLUDED_HEADER, "true")
                            .header(DataSourceClient.COPY_KEYSET_HEADER, "true")
                            .body(List.of()));

            assertThat(dataSourceClient.copyAllItems(42L, TENANT_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("regression (silent empty publish): a failed page makes copyAllItems THROW, so a publish cannot ship an empty table")
        void failedCopyThrowsForAPublish() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dataSourceClient.copyAllItems(42L, TENANT_ID, null))
                    .isInstanceOf(TableCopyException.class)
                    .hasMessageContaining("table 42");
        }

        @Test
        @DisplayName("regression (rolling update): an older datasource-service (no keyset confirmation) fails the copy on its first page")
        void olderServiceWithoutKeysetConfirmationFailsTheCopy() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok()
                            .header(DataSourceClient.RESTRICTED_EXCLUDED_HEADER, "true")
                            .body(List.of()));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dataSourceClient.copyAllItems(42L, TENANT_ID, null))
                    .isInstanceOf(TableCopyException.class).hasMessageContaining("keyset");
            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).isEmpty();
            verify(restTemplate, org.mockito.Mockito.times(2)).exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class));
        }

        @Test
        @DisplayName("regression (LC-066 rollout): a datasource-service that ignores excludeRestricted (no confirmation) gets nothing copied")
        void getAllItemsCopiesNothingWithoutTheFilterConfirmation() {
            com.apimarketplace.datasource.client.dto.DataSourceItemDto row = new com.apimarketplace.datasource.client.dto.DataSourceItemDto(
                    7L, 42L, TENANT_ID, java.util.Map.of("subject", "Hi"), 1, null);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of(row)));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).isEmpty();
        }

        // ---- Paging: the copy used to send page/size, which the endpoint ignores (offset/limit,
        // default 50), so every publication copy held at most the first 50 rows. ----

        private static final String ITEMS_URL = BASE_URL + "/api/internal/datasource/42/items";
        private static final int PAGE = DataSourceClient.COPY_PAGE_SIZE;

        /** {@code count} rows, ids from {@code fromId}, all at priority 0 (the order is then by id). */
        private List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> rows(int fromId, int count) {
            List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> rows = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                rows.add(new com.apimarketplace.datasource.client.dto.DataSourceItemDto(
                        (long) (fromId + i), 42L, TENANT_ID, java.util.Map.of("n", fromId + i), 0, null));
            }
            return rows;
        }

        private ResponseEntity<Object> confirmedPage(List<?> body) {
            return ResponseEntity.ok().header(DataSourceClient.RESTRICTED_EXCLUDED_HEADER, "true")
                    .header(DataSourceClient.COPY_KEYSET_HEADER, "true").body(body);
        }

        private String firstPageUrl(int limit) {
            return ITEMS_URL + "?offset=0&limit=" + limit + "&excludeRestricted=true";
        }

        /** A next page: the offset (for an older datasource-service) plus the keyset cursor. */
        private String nextPageUrl(int offset, int limit, long afterId) {
            return ITEMS_URL + "?offset=" + offset + "&limit=" + limit + "&excludeRestricted=true"
                    + "&afterPriority=0&afterId=" + afterId;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void stubPage(String url, ResponseEntity<?> response) {
            when(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn((ResponseEntity) response);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void stubPageFailure(String url) {
            when(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        /** Answers every request with the next rows after the cursor, out of a table of {@code total}. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void stubTableOf(int total) {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenAnswer(inv -> {
                        String url = inv.getArgument(0);
                        int after = url.contains("afterId=")
                                ? Integer.parseInt(url.substring(url.indexOf("afterId=") + 8)) : 0;
                        int limit = Integer.parseInt(url.replaceAll(".*[?&]limit=(\\d+).*", "$1"));
                        int count = Math.max(0, Math.min(limit, total - after));
                        return (ResponseEntity) confirmedPage(rows(after + 1, count));
                    });
        }

        private List<String> requestedUrls(int times) {
            ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
            verify(restTemplate, org.mockito.Mockito.times(times)).exchange(urls.capture(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class));
            return urls.getAllValues();
        }

        @Test
        @DisplayName("regression (50-row copy): a table of more than one page is copied whole, in order, by keyset pages")
        void copiesEveryPageUntilAShortOne() {
            stubPage(firstPageUrl(PAGE), confirmedPage(rows(1, PAGE)));
            stubPage(nextPageUrl(PAGE, PAGE, PAGE), confirmedPage(rows(1 + PAGE, PAGE)));
            stubPage(nextPageUrl(2 * PAGE, PAGE, 2L * PAGE), confirmedPage(rows(1 + 2 * PAGE, 7)));

            List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> copy =
                    dataSourceClient.getAllItems(42L, TENANT_ID, "org-1");

            assertThat(copy).extracting(com.apimarketplace.datasource.client.dto.DataSourceItemDto::id)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 2L * PAGE + 7).boxed().toList());
            assertThat(requestedUrls(3)).allSatisfy(u -> assertThat(u)
                    .contains("excludeRestricted=true").doesNotContain("page=").doesNotContain("size="));
        }

        @Test
        @DisplayName("a short first page is the whole table: one request, no cursor")
        void shortFirstPageStopsAtOnce() {
            stubPage(firstPageUrl(PAGE), confirmedPage(rows(1, 3)));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).hasSize(3);
            assertThat(requestedUrls(1)).containsExactly(firstPageUrl(PAGE));
        }

        @Test
        @DisplayName("a row served twice (an older datasource-service paging by offset) is copied once")
        void duplicateRowsAreCopiedOnce() {
            stubPage(firstPageUrl(PAGE), confirmedPage(rows(1, PAGE)));
            List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> overlap = new java.util.ArrayList<>(rows(PAGE, 1));
            overlap.addAll(rows(PAGE + 1, 2));
            stubPage(nextPageUrl(PAGE, PAGE, PAGE), confirmedPage(overlap));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null))
                    .extracting(com.apimarketplace.datasource.client.dto.DataSourceItemDto::id)
                    .doesNotHaveDuplicates().hasSize(PAGE + 2);
        }

        @Test
        @DisplayName("regression (LC-066): page 2 without the RESTRICTED-excluded confirmation copies NOTHING, not page 1")
        void missingConfirmationOnALaterPageCopiesNothing() {
            stubPage(firstPageUrl(PAGE), confirmedPage(rows(1, PAGE)));
            stubPage(nextPageUrl(PAGE, PAGE, PAGE), ResponseEntity.ok(rows(1 + PAGE, 4)));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("an error on page 2 copies NOTHING: never a partial table")
        void errorOnALaterPageCopiesNothing() {
            stubPage(firstPageUrl(PAGE), confirmedPage(rows(1, PAGE)));
            stubPageFailure(nextPageUrl(PAGE, PAGE, PAGE));

            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).isEmpty();
        }

        @Test
        @DisplayName("a table of exactly MAX_COPY_ROWS rows is copied whole: the probe for one more row comes back empty")
        void tableExactlyAtTheCapIsCopiedWhole() {
            stubTableOf(DataSourceClient.MAX_COPY_ROWS);

            List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> copy =
                    dataSourceClient.getAllItems(42L, TENANT_ID, null);

            assertThat(copy).hasSize(DataSourceClient.MAX_COPY_ROWS);
            List<String> urls = requestedUrls(DataSourceClient.MAX_COPY_ROWS / PAGE + 1);
            // The last request asks for exactly the one row that would mark a larger table.
            assertThat(urls.get(urls.size() - 1)).contains("limit=1&");
        }

        @Test
        @DisplayName("regression (silent cap): a table past MAX_COPY_ROWS comes back with MAX_COPY_ROWS + 1 rows, so the budget refuses it")
        void tablePastTheCapReturnsOneRowMore() {
            stubTableOf(DataSourceClient.MAX_COPY_ROWS + 300);

            List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> copy =
                    dataSourceClient.getAllItems(42L, TENANT_ID, null);

            assertThat(copy).hasSize(DataSourceClient.MAX_COPY_ROWS + 1);
            requestedUrls(DataSourceClient.MAX_COPY_ROWS / PAGE + 1);
        }

        // ---- Termination never depends on the server ----

        @Test
        @DisplayName("regression (endless copy): a server that ignores the cursor and repeats the first full page fails the copy on page 2")
        void repeatedFirstPageFailsInsteadOfLooping() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenAnswer(inv -> confirmedPage(rows(1, PAGE)));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dataSourceClient.copyAllItems(42L, TENANT_ID, null))
                    .isInstanceOf(TableCopyException.class).hasMessageContaining("added no new row");
            requestedUrls(2);
        }

        @Test
        @DisplayName("regression (endless copy): pages that add a single new row each are cut off at the request cap")
        void slowProgressHitsTheRequestCap() {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenAnswer(inv -> {
                        String url = inv.getArgument(0);
                        int limit = Integer.parseInt(url.replaceAll(".*[?&]limit=(\\d+).*", "$1"));
                        // Every page is full but overlaps the previous one: one new id per page.
                        return confirmedPage(rows(1 + calls.getAndIncrement(), limit));
                    });
            int maxRequests = (DataSourceClient.MAX_COPY_ROWS + 1 + PAGE - 1) / PAGE + 1;

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dataSourceClient.copyAllItems(42L, TENANT_ID, null))
                    .isInstanceOf(TableCopyException.class).hasMessageContaining("still paging after " + maxRequests);
            requestedUrls(maxRequests);
        }

        @Test
        @DisplayName("a full page whose last row has no priority cannot be resumed after: the copy fails, never guesses")
        void rowWithoutPriorityFailsTheCopy() {
            List<com.apimarketplace.datasource.client.dto.DataSourceItemDto> page = new java.util.ArrayList<>(rows(1, PAGE - 1));
            page.add(new com.apimarketplace.datasource.client.dto.DataSourceItemDto(
                    (long) PAGE, 42L, TENANT_ID, java.util.Map.of(), null, null));
            stubPage(firstPageUrl(PAGE), confirmedPage(page));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> dataSourceClient.copyAllItems(42L, TENANT_ID, null))
                    .isInstanceOf(TableCopyException.class).hasMessageContaining("no id or priority");
            assertThat(dataSourceClient.getAllItems(42L, TENANT_ID, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteDataSource")
    class DeleteDataSource {

        @Test
        @DisplayName("sets explicit organization header for scoped cleanup")
        void deleteDataSourceSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE),
                    any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(ResponseEntity.ok().build());

            dataSourceClient.deleteDataSource(dataSourceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/" + dataSourceId + "/delete"),
                    eq(HttpMethod.DELETE), entityCaptor.capture(), eq(Void.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("single-table lookups: log level by failure kind")
    class LookupLogLevel {

        private ListAppender<ILoggingEvent> logs;
        private ch.qos.logback.classic.Logger clientLogger;

        @BeforeEach
        void capture() {
            clientLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DataSourceClient.class);
            logs = new ListAppender<>();
            logs.start();
            clientLogger.addAppender(logs);
        }

        @org.junit.jupiter.api.AfterEach
        void release() {
            clientLogger.detachAppender(logs);
            logs.stop();
        }

        private List<Level> levels() {
            return logs.list.stream().map(ILoggingEvent::getLevel).toList();
        }

        private void respond(Exception failure) {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenThrow(failure);
        }

        @Test
        @DisplayName("findByIdAndTenantId: 404 is a normal not-found answer, logged at WARN, returns null")
        void findByIdNotFoundIsWarn() {
            respond(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            assertThat(dataSourceClient.findByIdAndTenantId(42L, TENANT_ID, "org-1")).isNull();
            assertThat(levels()).containsExactly(Level.WARN);
        }

        @Test
        @DisplayName("getDataSource: 404 is logged at WARN, returns null")
        void getDataSourceNotFoundIsWarn() {
            respond(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            assertThat(dataSourceClient.getDataSource(42L, TENANT_ID)).isNull();
            assertThat(levels()).containsExactly(Level.WARN);
        }

        @Test
        @DisplayName("a 5xx stays ERROR")
        void serverErrorStaysError() {
            respond(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null));

            assertThat(dataSourceClient.findByIdAndTenantId(42L, TENANT_ID, "org-1")).isNull();
            assertThat(levels()).containsExactly(Level.ERROR);
        }

        @Test
        @DisplayName("another 4xx (403) stays ERROR: it is not a not-found answer")
        void forbiddenStaysError() {
            respond(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

            assertThat(dataSourceClient.getDataSource(42L, TENANT_ID, "org-1")).isNull();
            assertThat(levels()).containsExactly(Level.ERROR);
        }

        @Test
        @DisplayName("an I/O failure stays ERROR")
        void ioFailureStaysError() {
            respond(new ResourceAccessException("Connection refused"));

            assertThat(dataSourceClient.getDataSource(42L, TENANT_ID)).isNull();
            assertThat(levels()).containsExactly(Level.ERROR);
        }
    }

    @Nested
    @DisplayName("getDataSource")
    class GetDataSource {

        @Test
        @DisplayName("the org-aware overload sets X-Organization-ID explicitly (no request to forward it from)")
        void orgAwareOverloadSetsOrganizationHeader() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenReturn(ResponseEntity.ok(null));

            dataSourceClient.getDataSource(7L, TENANT_ID, "org-1");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/7/get"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(DataSourceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        }

        @Test
        @DisplayName("the 2-arg overload sends no org header off a request thread (unchanged)")
        void legacyOverloadSendsNoOrganizationHeader() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenReturn(ResponseEntity.ok(null));

            dataSourceClient.getDataSource(7L, TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/7/get"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(DataSourceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
        }
    }
}
