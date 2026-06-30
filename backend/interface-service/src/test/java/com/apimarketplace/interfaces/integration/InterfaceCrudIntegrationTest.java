package com.apimarketplace.interfaces.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class InterfaceCrudIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static final String TENANT = "integration-tenant";
    private static final String PUBLIC_API = "/api/interfaces";

    @Test
    void shouldCreateAndReadInterface() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Integration Test",
                "htmlTemplate", "<div>{{greeting}}</div>",
                "cssTemplate", ".c { color: blue; }",
                "jsTemplate", "console.log('test');"));

        MvcResult createResult = mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Integration Test"))
                .andExpect(jsonPath("$.htmlTemplate").value("<div>{{greeting}}</div>"))
                .andReturn();

        String json = createResult.getResponse().getContentAsString();
        String id = objectMapper.readTree(json).get("id").asText();

        // Read back
        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Integration Test"))
                .andExpect(jsonPath("$.templateVariables").isArray())
                .andExpect(jsonPath("$.templateVariables[0]").value("greeting"));
    }

    /**
     * REGRESSION: Verify the HTTP response JSON uses camelCase, not snake_case.
     */
    @Test
    void shouldCreateInterface_andReturnCamelCaseJson() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "CamelCase Test",
                "htmlTemplate", "<div>hi</div>",
                "cssTemplate", ".x{}",
                "jsTemplate", "alert(1);"));

        MvcResult result = mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.htmlTemplate").exists())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();

        // Must be camelCase
        assertThat(responseJson).contains("\"htmlTemplate\"");
        assertThat(responseJson).contains("\"cssTemplate\"");
        assertThat(responseJson).contains("\"jsTemplate\"");
        assertThat(responseJson).contains("\"interfaceType\"");

        // Must NOT be snake_case
        assertThat(responseJson).doesNotContain("\"html_template\"");
        assertThat(responseJson).doesNotContain("\"css_template\"");
        assertThat(responseJson).doesNotContain("\"js_template\"");
        assertThat(responseJson).doesNotContain("\"interface_type\"");
    }

    @Test
    void shouldAcceptSnakeCaseCreateBody() throws Exception {
        String body = "{\"name\":\"Snake Test\",\"html_template\":\"<p>hi</p>\"}";

        mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Snake Test"));
    }

    @Test
    void shouldUpdateInterface() throws Exception {
        // Create
        String id = createInterface("Before Update");

        // Update
        String updateBody = objectMapper.writeValueAsString(Map.of("name", "After Update"));
        mockMvc.perform(put(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After Update"));
    }

    @Test
    void shouldCloneInterface() throws Exception {
        String id = createInterface("Original");

        mockMvc.perform(post(PUBLIC_API + "/{id}/clone", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Original (Copy)"))
                .andExpect(jsonPath("$.id").value(not(id)));
    }

    @Test
    void shouldDeleteInterface() throws Exception {
        String id = createInterface("To Delete");

        mockMvc.perform(delete(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldEnforceTenantIsolation() throws Exception {
        String id = createInterface("Tenant Isolated");

        // Different tenant should not see it
        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", "other-tenant")
                        .header("X-Organization-ID", "other-tenant"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListInterfaces() throws Exception {
        createInterface("List Test 1");
        createInterface("List Test 2");

        mockMvc.perform(get(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void shouldExtractTemplateVariables() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Var Test",
                "htmlTemplate", "<div>{{title}} {{content}}</div>",
                "cssTemplate", "color: {{color}};"));

        MvcResult result = mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        String id = objectMapper.readTree(json).get("id").asText();

        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateVariables").isArray())
                .andExpect(jsonPath("$.templateVariables", hasItem("title")))
                .andExpect(jsonPath("$.templateVariables", hasItem("content")))
                .andExpect(jsonPath("$.templateVariables", hasItem("color")));
    }

    @Test
    void shouldReturnFormFieldsOnGet() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Form Test",
                "htmlTemplate", "<input name=\"email\" /><textarea name=\"message\"></textarea>"));

        MvcResult result = mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formFields", hasItem("email")))
                .andExpect(jsonPath("$.formFields", hasItem("message")));
    }

    @Test
    void shouldUpdateTemplateVariablesOnUpdate() throws Exception {
        String id = createInterface("Var Update");

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "htmlTemplate", "<div>{{newVar1}} {{newVar2}}</div>"));

        mockMvc.perform(put(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());

        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateVariables", hasItem("newVar1")))
                .andExpect(jsonPath("$.templateVariables", hasItem("newVar2")));
    }

    @Test
    void shouldSetDefaultsOnCreate() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", "Default Test"));

        mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(false))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.interfaceType").value("html"));
    }

    @Test
    void shouldClearDataSourceOnUpdate() throws Exception {
        // Create with dataSourceId
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "DS Test",
                "dataSourceId", 42,
                "targetTable", "my_table"));
        MvcResult result = mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        // Clear dataSourceId (use raw JSON since Map.of() doesn't allow nulls)
        mockMvc.perform(put(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataSourceId\": null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(PUBLIC_API + "/{id}", id)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSourceId").doesNotExist())
                .andExpect(jsonPath("$.targetTable").doesNotExist());
    }

    private String createInterface(String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name,
                "htmlTemplate", "<div>{{title}}</div>"));

        MvcResult result = mockMvc.perform(post(PUBLIC_API)
                        .header("X-User-ID", TENANT)
                        .header("X-Organization-ID", TENANT)
                        .header("X-Organization-ID", TENANT)  // V263 OrgScopedEntity
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
