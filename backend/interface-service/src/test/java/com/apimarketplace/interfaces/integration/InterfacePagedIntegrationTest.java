package com.apimarketplace.interfaces.integration;

import com.apimarketplace.publication.client.PublicationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack HTTP integration test for the server-paged interfaces list. Boots the real Spring MVC
 * context (DispatcherServlet routing + @RequestParam binding + JSON envelope serialization) over a
 * real JPA repository (H2 in PostgreSQL mode), with the cross-service PublicationClient stubbed to
 * mark every interface shared. Proves end-to-end that {@code GET /api/interfaces/paged}:
 *  - server-sorts (sort=name), paginates (page/size slice + full total), and
 *  - inlines the per-row publication badge under {@code publicationStatuses}, and honours the
 *    {@code visibility} filter - none of which the old client-side fetch-all + sweep did.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({IntegrationTestConfig.class, InterfacePagedIntegrationTest.PubConfig.class})
class InterfacePagedIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String API = "/api/interfaces";
    // The integration base is NOT @Transactional, so rows persist across tests; each test uses its
    // own tenant so its paged totals see only its own interfaces.

    /** Stub the publication client (not running in the harness) to report EVERY interface as shared. */
    @TestConfiguration
    static class PubConfig {
        // Distinct bean name (not "publicationClient") so it is an ADDITIONAL @Primary bean rather
        // than an override of PublicationClientConfig's bean (Boot disables override by default).
        @Bean
        @Primary
        PublicationClient stubPublicationClient() {
            PublicationClient m = mock(PublicationClient.class);
            when(m.findResourcePublicationStatuses(any(), any(), any())).thenAnswer(inv -> {
                Collection<String> ids = inv.getArgument(1);
                Map<String, PublicationClient.ResourcePublicationStatusRef> out = new HashMap<>();
                for (String id : ids) out.put(id, new PublicationClient.ResourcePublicationStatusRef("ACTIVE", null));
                return out;
            });
            return m;
        }
    }

    private void createInterface(String tenant, String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name, "htmlTemplate", "<div>" + name + "</div>"));
        mockMvc.perform(post(API)
                        .header("X-User-ID", tenant).header("X-Organization-ID", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void pagedSortsByNameAndInlinesPublicationStatuses() throws Exception {
        String tenant = "paged-sort-tenant";
        createInterface(tenant, "Zed");
        createInterface(tenant, "Ann");
        createInterface(tenant, "Mid");

        mockMvc.perform(get(API + "/paged")
                        .header("X-User-ID", tenant).header("X-Organization-ID", tenant)
                        .param("sort", "name").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                // Server-sorted A->Z.
                .andExpect(jsonPath("$.items[0].name").value("Ann"))
                .andExpect(jsonPath("$.items[1].name").value("Mid"))
                .andExpect(jsonPath("$.items[2].name").value("Zed"))
                // Publication badge inlined for the page (every id shared via the stub).
                .andExpect(jsonPath("$.publicationStatuses").isMap())
                .andExpect(jsonPath("$.publicationStatuses[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("ACTIVE"))));
    }

    @Test
    void pagedSlicesTheRequestedPageAndReportsFullTotal() throws Exception {
        String tenant = "paged-slice-tenant";
        createInterface(tenant, "Zed");
        createInterface(tenant, "Ann");
        createInterface(tenant, "Mid");

        // size=1, page=1, sorted by name -> the SECOND interface (Mid), total still 3.
        mockMvc.perform(get(API + "/paged")
                        .header("X-User-ID", tenant).header("X-Organization-ID", tenant)
                        .param("sort", "name").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Mid"));
    }

    @Test
    void visibilityFilterIsHonouredServerSide() throws Exception {
        String tenant = "paged-vis-tenant";
        createInterface(tenant, "One");
        createInterface(tenant, "Two");

        // Every interface is shared (stub) -> public keeps both, private keeps none.
        mockMvc.perform(get(API + "/paged")
                        .header("X-User-ID", tenant).header("X-Organization-ID", tenant)
                        .param("visibility", "public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));

        mockMvc.perform(get(API + "/paged")
                        .header("X-User-ID", tenant).header("X-Organization-ID", tenant)
                        .param("visibility", "private"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
