package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.config.InterfaceAgentDefaultsConfig;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceTemplatePatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceCrudModule")
class InterfaceCrudModuleTest {

    @Mock private InterfaceService interfaceService;
    private InterfaceCrudModule module;
    private InterfaceAgentDefaultsConfig agentDefaults;

    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        agentDefaults = new InterfaceAgentDefaultsConfig();
        module = new InterfaceCrudModule(interfaceService, agentDefaults);
    }

    private ToolExecutionContext ctx() {
        return ToolExecutionContext.of(TENANT);
    }

    private ToolExecutionContext ctxWithVariables(Map<String, Object> variables) {
        return new ToolExecutionContext(TENANT, Map.of(), variables, Set.of(), null, null, null, null);
    }

    private ToolExecutionContext ctxWithOrg(String orgId, String orgRole) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, orgId, orgRole);
    }

    private InterfaceEntity fakeEntity(UUID id, String name) {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setName(name);
        entity.setDescription("Test description");
        entity.setHtmlTemplate("<div>{{title|Hello}}</div>");
        entity.setCssTemplate("body { margin: 0; }");
        entity.setJsTemplate("// js");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity.setInterfaceType("html");
        entity.setTemplateVariables(List.of("title"));
        return entity;
    }

    private InterfaceEntity fakeSlideEntity(UUID id, String name, int slideCount) {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setName(name);
        entity.setDescription("Slide deck");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity.setInterfaceType("slide");
        List<Map<String, Object>> slides = new ArrayList<>();
        for (int i = 0; i < slideCount; i++) {
            slides.add(Map.of("title", "Slide " + (i + 1)));
        }
        entity.setData(Map.of("slides", slides));
        return entity;
    }

    // ==================== canHandle ====================

    @Test
    @DisplayName("canHandle should accept CRUD actions")
    void canHandle() {
        assertThat(module.canHandle("create")).isTrue();
        assertThat(module.canHandle("get")).isTrue();
        assertThat(module.canHandle("list")).isTrue();
        assertThat(module.canHandle("update")).isTrue();
        assertThat(module.canHandle("patch")).isTrue();
        assertThat(module.canHandle("delete")).isTrue();
        assertThat(module.canHandle("help")).isFalse();
    }

    @Test
    @DisplayName("Should return empty for unhandled action")
    void shouldReturnEmptyForUnhandled() {
        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), TENANT, ctx());
        assertThat(result).isEmpty();
    }

    // ==================== create ====================

    @Nested
    @DisplayName("access mode enforcement (interfaceAccessMode)")
    class AccessModeTests {

        private ToolExecutionContext ctxReadMode() {
            return new ToolExecutionContext(TENANT, Map.of("interfaceAccessMode", "read"),
                    Map.of(), Set.of(), null, null, null, null);
        }

        @Test
        @DisplayName("read-only mode denies every interface WRITE action (create/update/patch/delete) before the service")
        void readModeDeniesWrites() {
            for (String write : List.of("create", "update", "patch", "delete")) {
                Optional<ToolExecutionResult> res = module.execute(write,
                        Map.of("interface_id", UUID.randomUUID().toString(), "name", "X"), TENANT, ctxReadMode());
                assertThat(res).as("write '%s' must be denied in read-mode", write).isPresent();
                assertThat(res.get().success()).as(write).isFalse();
                assertThat(res.get().errorCode()).as(write).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
                assertThat(res.get().error()).as(write).contains("read-only");
            }
            verifyNoInteractions(interfaceService);
        }

        @Test
        @DisplayName("read-only mode still ALLOWS a read action (list passes the access-mode gate)")
        void readModeAllowsList() {
            when(interfaceService.listInterfaces(any(), any(), any(), any())).thenReturn(List.of());
            Optional<ToolExecutionResult> res = module.execute("list", Map.of(), TENANT, ctxReadMode());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }
    }

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("Should create interface with all templates")
        void shouldCreateWithAllTemplates() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "Dashboard");
            when(interfaceService.createInterface(
                eq(TENANT), eq("Dashboard"), any(), eq("<h1>Hello</h1>"), eq("h1 { color: blue; }"), eq("// js"),
                isNull(), isNull(), isNull(), isNull(), eq(false), isNull(), isNull()
            )).thenReturn(entity);

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Dashboard");
            params.put("html_template", "<h1>Hello</h1>");
            params.put("css_template", "h1 { color: blue; }");
            params.put("js_template", "// js");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("id")).isEqualTo(id.toString());
            assertThat(data.get("name")).isEqualTo("Dashboard");
            assertThat(data.get("status")).isEqualTo("CREATED");
        }

        @Test
        @DisplayName("Should include visualization metadata on create")
        void shouldIncludeMetadataOnCreate() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "Card");
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(entity);

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Card");
            params.put("html_template", "<div>Card</div>");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().metadata()).isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) res.get().metadata().get("visualization");
            assertThat(viz.get("type")).isEqualTo("interface");
            assertThat(viz.get("id")).isEqualTo(id.toString());
        }

        @Test
        @DisplayName("Should include template variables and NEXT_STEP guidance")
        void shouldIncludeTemplateVariables() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "Product");
            entity.setTemplateVariables(List.of("title", "price"));
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(entity);

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Product");
            params.put("html_template", "<div>{{title|Product}} - {{price|0}}</div>");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("template_variables")).isEqualTo(List.of("title", "price"));
            assertThat((String) data.get("NEXT_STEP")).contains("variable_mapping");
        }

        @Test
        @DisplayName("Should fail without name")
        void shouldFailWithoutName() {
            Map<String, Object> params = new HashMap<>();
            params.put("html_template", "<div>Hello</div>");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("name is required");
        }

        @Test
        @DisplayName("Should fail without html_template")
        void shouldFailWithoutHtmlTemplate() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "Test");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("html_template is REQUIRED");
        }

        @Test
        @DisplayName("Should create slide interface when type=slide")
        void shouldCreateSlideInterface() {
            UUID id = UUID.randomUUID();
            InterfaceEntity slideEntity = fakeSlideEntity(id, "My Slides", 3);
            when(interfaceService.createSlideInterface(eq(TENANT), eq("My Slides"), any(), any(), isNull()))
                .thenReturn(slideEntity);

            Map<String, Object> params = new HashMap<>();
            params.put("name", "My Slides");
            params.put("type", "slide");
            params.put("slide_data", Map.of("slides", List.of(
                Map.of("title", "Slide 1"),
                Map.of("title", "Slide 2"),
                Map.of("title", "Slide 3")
            )));

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("type")).isEqualTo("slide");
            assertThat(data.get("slide_count")).isEqualTo(3);

            verify(interfaceService).createSlideInterface(eq(TENANT), eq("My Slides"), any(), any(), isNull());
        }

        @Test
        @DisplayName("Should propagate service exception on create")
        void shouldPropagateExceptionOnCreate() {
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "Test");
            params.put("html_template", "<div>Test</div>");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("DB error");
        }

        @Test
        @DisplayName("Service IllegalArgumentException surfaces verbatim, no 'Failed to…' prefix")
        void shouldSurfaceIllegalArgumentVerbatimOnCreate() {
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new IllegalArgumentException("name cannot exceed 255 characters"));

            Map<String, Object> params = new HashMap<>();
            params.put("name", "ok");
            params.put("html_template", "<div/>");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).isEqualTo("name cannot exceed 255 characters");
            assertThat(res.get().error()).doesNotContain("Failed to create");
        }

        @Test
        @DisplayName("Should enforce creation rate limit per turn")
        void shouldEnforceCreationRateLimit() {
            InterfaceEntity entity = fakeEntity(UUID.randomUUID(), "UI");
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(entity);

            Map<String, Object> variables = new HashMap<>();
            variables.put("turnId", "turn-123");
            ToolExecutionContext ctxWithTurn = ctxWithVariables(variables);

            // Create 5 interfaces (should succeed - unified maxPerResourcePerTurn default)
            for (int i = 0; i < 5; i++) {
                entity = fakeEntity(UUID.randomUUID(), "UI" + i);
                when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(entity);

                Map<String, Object> params = new HashMap<>();
                params.put("name", "UI" + i);
                params.put("html_template", "<div>Hello</div>");

                Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctxWithTurn);
                assertThat(res).isPresent();
                assertThat(res.get().success()).isTrue();
            }

            // 6th create should be rate limited
            Map<String, Object> params = new HashMap<>();
            params.put("name", "UI_extra");
            params.put("html_template", "<div>Hello</div>");

            Optional<ToolExecutionResult> res = module.execute("create", params, TENANT, ctxWithTurn);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("LIMIT REACHED");
        }
    }

    // ==================== get ====================

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("Should get interface by UUID")
        void shouldGetByUuid() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "My Interface");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("id")).isEqualTo(id.toString());
            assertThat(data.get("name")).isEqualTo("My Interface");
            assertThat(data.get("type")).isEqualTo("html");
            assertThat(data.get("htmlTemplate")).isEqualTo("<div>{{title|Hello}}</div>");
        }

        @Test
        @DisplayName("Should resolve interface by name fallback")
        void shouldResolveByNameFallback() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "Dashboard");
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(entity));
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

            Map<String, Object> params = Map.of("interface_id", "Dashboard");
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("name")).isEqualTo("Dashboard");
        }

        @Test
        @DisplayName("Should resolve name with underscore to space")
        void shouldResolveNameWithUnderscore() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "My Dashboard");
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(entity));
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

            Map<String, Object> params = Map.of("interface_id", "My_Dashboard");
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("Should get slide interface")
        void shouldGetSlideInterface() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeSlideEntity(id, "My Slides", 5);
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("type")).isEqualTo("slide");
            assertThat(data.get("slide_count")).isEqualTo(5);
            assertThat(data.get("slide_data")).isNotNull();
        }

        @Test
        @DisplayName("Should fail without interface_id")
        void shouldFailWithoutId() {
            Optional<ToolExecutionResult> res = module.execute("get", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("interface_id is required");
        }

        @Test
        @DisplayName("Non-UUID, non-matching name returns actionable hint (not 'is required')")
        void shouldHintWhenValueDoesNotResolve() {
            when(interfaceService.listInterfaces(eq(TENANT), any(), any(), any()))
                .thenReturn(List.of());

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "nonexistent_name");

            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("No interface matches 'nonexistent_name'");
            assertThat(res.get().error()).doesNotContain("interface_id is required");
        }

        @Test
        @DisplayName("Should fail for non-existent interface")
        void shouldFailForNonExistent() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.empty());

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not found");
        }

        @Test
        @DisplayName("Should enforce allowedInterfaceIds restriction")
        void shouldEnforceAllowedInterfaceIds() {
            UUID allowedId = UUID.randomUUID();
            UUID blockedId = UUID.randomUUID();

            Map<String, Object> variables = new HashMap<>();
            variables.put("allowedInterfaceIds", List.of(allowedId.toString()));
            ToolExecutionContext restrictedCtx = ctxWithVariables(variables);

            Map<String, Object> params = Map.of("interface_id", blockedId.toString());
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, restrictedCtx);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not in your approved interface list");
        }

        @Test
        @DisplayName("Should allow get when id is in allowedInterfaceIds")
        void shouldAllowWhenIdIsAllowed() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "Allowed UI");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

            Map<String, Object> variables = new HashMap<>();
            variables.put("allowedInterfaceIds", List.of(id.toString()));
            ToolExecutionContext restrictedCtx = ctxWithVariables(variables);

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("get", params, TENANT, restrictedCtx);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }
    }

    // ==================== list ====================

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("Should strip system-owned auto-created rows (agent_browse, image_generation, legacy web_search) from agent.list")
        @SuppressWarnings("unchecked")
        void shouldStripSystemOwnedTypes() {
            UUID htmlId = UUID.randomUUID();
            UUID htmlId2 = UUID.randomUUID();
            UUID webSearchId = UUID.randomUUID();
            UUID imageGenId = UUID.randomUUID();
            UUID agentBrowseId = UUID.randomUUID();

            InterfaceEntity html1 = fakeEntity(htmlId, "Real UI");
            InterfaceEntity html2 = fakeEntity(htmlId2, "Real UI 2");
            InterfaceEntity ws = fakeEntity(webSearchId, "search for X");
            ws.setInterfaceType("web_search");
            InterfaceEntity ig = fakeEntity(imageGenId, "Generated image");
            ig.setInterfaceType("image_generation");
            InterfaceEntity ab = fakeEntity(agentBrowseId, "Browser session");
            ab.setInterfaceType("agent_browse");

            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(html1, ws, ig, html2, ab));

            Optional<ToolExecutionResult> res = module.execute("list", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            Map<String, Object> data = (Map<String, Object>) res.get().data();
            // 5 rows in, but only the 2 html ones survive the strip-system-rows filter.
            assertThat(data.get("count")).isEqualTo(2);
            assertThat(data.get("total")).isEqualTo(2L);
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("interfaces");
            assertThat(items).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder(htmlId.toString(), htmlId2.toString());
        }

        @Test
        @DisplayName("Should list all interfaces - canonical envelope (kind/offset/limit/hasMore)")
        @SuppressWarnings("unchecked")
        void shouldListAll() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(fakeEntity(id1, "UI1"), fakeEntity(id2, "UI2")));

            Optional<ToolExecutionResult> res = module.execute("list", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            Map<String, Object> data = (Map<String, Object>) res.get().data();
            // PR3 - canonical envelope keys via AgentListEnvelope.
            assertThat(data).containsKeys("status", "kind", "interfaces",
                    "count", "total", "offset", "limit", "hasMore");
            assertThat(data.get("kind")).isEqualTo("interfaces");
            assertThat(data.get("count")).isEqualTo(2);
            // Helper emits total as long, not int.
            assertThat(data.get("total")).isEqualTo(2L);
            assertThat(((List<?>) data.get("interfaces"))).hasSize(2);
        }

        @Test
        @DisplayName("Should apply pagination with limit and offset")
        @SuppressWarnings("unchecked")
        void shouldApplyPagination() {
            List<InterfaceEntity> entities = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                entities.add(fakeEntity(UUID.randomUUID(), "UI" + i));
            }
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(entities);

            Map<String, Object> params = new HashMap<>();
            params.put("limit", 3);
            params.put("offset", 2);

            Optional<ToolExecutionResult> res = module.execute("list", params, TENANT, ctx());
            assertThat(res).isPresent();

            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("count")).isEqualTo(3);
            assertThat(data.get("total")).isEqualTo(10L);
            assertThat(data.get("hasMore")).isEqualTo(true);
            assertThat(data.get("offset")).isEqualTo(2);
            assertThat(data.get("limit")).isEqualTo(3);
            // next_page hint with the next offset baked in (PR1+PR2+PR3 contract).
            Map<String, Object> hint = (Map<String, Object>) data.get("hint");
            assertThat(hint.get("action")).isEqualTo("next_page");
            assertThat(hint.get("nextOffset")).isEqualTo(5);
        }

        @Test
        @DisplayName("Should filter by allowedInterfaceIds")
        @SuppressWarnings("unchecked")
        void shouldFilterByAllowedIds() {
            UUID allowedId = UUID.randomUUID();
            UUID blockedId = UUID.randomUUID();
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(fakeEntity(allowedId, "Allowed"), fakeEntity(blockedId, "Blocked")));

            Map<String, Object> variables = new HashMap<>();
            variables.put("allowedInterfaceIds", List.of(allowedId.toString()));

            Optional<ToolExecutionResult> res = module.execute("list", Map.of(), TENANT, ctxWithVariables(variables));
            assertThat(res).isPresent();

            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("count")).isEqualTo(1);
            assertThat(data.get("total")).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should pass orgId and orgRole to service")
        void shouldPassOrgContext() {
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), eq("org-1"), eq("admin")))
                .thenReturn(List.of());

            module.execute("list", Map.of(), TENANT, ctxWithOrg("org-1", "admin"));
            verify(interfaceService).listInterfaces(TENANT, null, "org-1", "admin");
        }

        @Test
        @DisplayName("Should return empty list gracefully - hint.action=broaden")
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyList() {
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

            Optional<ToolExecutionResult> res = module.execute("list", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();

            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("count")).isEqualTo(0);
            assertThat(data.get("total")).isEqualTo(0L);
            assertThat(data.get("hasMore")).isEqualTo(false);
            // Empty result at offset=0 → broaden hint (PR1+PR2+PR3 contract).
            Map<String, Object> hint = (Map<String, Object>) data.get("hint");
            assertThat(hint.get("action")).isEqualTo("broaden");
        }

        @Test
        @DisplayName("Should SILENTLY CLAMP limit=0 to 1 - v0.3 contract favors clamp over 400s")
        @SuppressWarnings("unchecked")
        void shouldClampZeroLimit() {
            // Old behavior: explicit 400 with "limit must be >= 1".
            // New (v0.3): helper clamps limit=0 to 1 silently. The agent gets a useful
            // page (size 1) instead of an error path it has to recover from.
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(fakeEntity(UUID.randomUUID(), "UI")));
            Map<String, Object> params = new HashMap<>();
            params.put("limit", 0);

            Optional<ToolExecutionResult> res = module.execute("list", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("limit")).isEqualTo(1);  // clamped from 0
        }

        @Test
        @DisplayName("Should SILENTLY CLAMP negative limit to 1")
        @SuppressWarnings("unchecked")
        void shouldClampNegativeLimit() {
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(fakeEntity(UUID.randomUUID(), "UI")));
            Map<String, Object> params = new HashMap<>();
            params.put("limit", -5);

            Optional<ToolExecutionResult> res = module.execute("list", params, TENANT, ctx());
            assertThat(res.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("limit")).isEqualTo(1);
        }

        @Test
        @DisplayName("Should SILENTLY CLAMP negative offset to 0")
        @SuppressWarnings("unchecked")
        void shouldClampNegativeOffset() {
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
            Map<String, Object> params = new HashMap<>();
            params.put("offset", -1);

            Optional<ToolExecutionResult> res = module.execute("list", params, TENANT, ctx());
            assertThat(res.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("offset")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should SILENTLY CLAMP limit above STANDARD.maxLimit (50) - was hard-rejected pre-PR3")
        @SuppressWarnings("unchecked")
        void shouldClampLimitAboveCap() {
            // PR3 alignment: interface.list adopts Caps.STANDARD (max=50) - same ceiling
            // as workflow.list and application.my for cross-tool consistency. Callers
            // passing limit>50 are silently clamped (vs the previous 400 with hard cap=100).
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of(fakeEntity(UUID.randomUUID(), "UI")));
            Map<String, Object> params = new HashMap<>();
            params.put("limit", 500);

            Optional<ToolExecutionResult> res = module.execute("list", params, TENANT, ctx());
            assertThat(res.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("limit")).isEqualTo(50);  // STANDARD.maxLimit
        }
    }

    // ==================== update ====================

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should update HTML interface")
        void shouldUpdateHtmlInterface() {
            UUID id = UUID.randomUUID();
            InterfaceEntity existing = fakeEntity(id, "OldName");
            InterfaceEntity updated = fakeEntity(id, "NewName");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.updateInterface(eq(id), eq(TENANT), isNull(), isNull(), eq("NewName"), any(), any(), any(), any(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("name", "NewName");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("status")).isEqualTo("UPDATED");
            assertThat(data.get("name")).isEqualTo("NewName");
        }

        @Test
        @DisplayName("Should update slide interface with slide_data")
        void shouldUpdateSlideInterface() {
            UUID id = UUID.randomUUID();
            InterfaceEntity existing = fakeSlideEntity(id, "Slides", 2);
            InterfaceEntity updated = fakeSlideEntity(id, "Slides Updated", 4);
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.updateSlideData(eq(id), eq(TENANT), any(), any(), any()))
                .thenReturn(updated);

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("name", "Slides Updated");
            params.put("slide_data", Map.of("slides", List.of(
                Map.of("title", "S1"), Map.of("title", "S2"),
                Map.of("title", "S3"), Map.of("title", "S4")
            )));

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("type")).isEqualTo("slide");
            assertThat(data.get("slide_count")).isEqualTo(4);
            verify(interfaceService).updateSlideData(eq(id), eq(TENANT), any(), any(), any());
        }

        @Test
        @DisplayName("Should fail without interface_id")
        void shouldFailWithoutId() {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "NewName");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("interface_id is required");
        }

        @Test
        @DisplayName("Non-UUID value on update returns actionable hint (not 'is required')")
        void shouldHintWhenValueDoesNotResolve() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "not-a-uuid");
            params.put("name", "NewName");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("No interface matches 'not-a-uuid'");
            assertThat(res.get().error()).doesNotContain("interface_id is required");
        }

        @Test
        @DisplayName("Should fail for non-existent interface")
        void shouldFailForNonExistent() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.empty());

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("name", "NewName");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not found");
        }

        @Test
        @DisplayName("Should enforce update rate limit")
        void shouldEnforceUpdateRateLimit() {
            UUID id = UUID.randomUUID();
            InterfaceEntity existing = fakeEntity(id, "UI");
            InterfaceEntity updated = fakeEntity(id, "UI");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.updateInterface(eq(id), eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(updated);

            // 3 updates should succeed
            for (int i = 0; i < 3; i++) {
                Map<String, Object> params = new HashMap<>();
                params.put("interface_id", id.toString());
                params.put("name", "UI" + i);

                Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
                assertThat(res).isPresent();
                assertThat(res.get().success()).isTrue();
            }

            // 4th update should be rate limited
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("name", "UI_extra");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("STOP");
        }

        @Test
        @DisplayName("Should enforce allowedInterfaceIds restriction on update")
        void shouldEnforceAllowedIdsOnUpdate() {
            UUID blockedId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("allowedInterfaceIds", List.of(UUID.randomUUID().toString()));

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", blockedId.toString());
            params.put("name", "Hacked");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctxWithVariables(variables));
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not in your approved interface list");
        }

        @Test
        @DisplayName("Should reject update with no fields to change (does not burn a rate-limit slot)")
        void shouldRejectEmptyUpdate() {
            UUID id = UUID.randomUUID();

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("No fields to update");

            // The service must never be touched when there is nothing to update.
            verifyNoInteractions(interfaceService);

            // And the slot must not be burned - 3 subsequent real updates must succeed.
            InterfaceEntity existing = fakeEntity(id, "UI");
            InterfaceEntity updated = fakeEntity(id, "UI");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.updateInterface(eq(id), eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(updated);

            for (int i = 0; i < 3; i++) {
                Map<String, Object> realUpdate = new HashMap<>();
                realUpdate.put("interface_id", id.toString());
                realUpdate.put("name", "UI_v" + i);
                Optional<ToolExecutionResult> ok = module.execute("update", realUpdate, TENANT, ctx());
                assertThat(ok).isPresent();
                assertThat(ok.get().success()).isTrue();
            }
        }

        @Test
        @DisplayName("Service IllegalArgumentException surfaces verbatim, no 'Failed to…' prefix")
        void shouldSurfaceIllegalArgumentVerbatimOnUpdate() {
            UUID id = UUID.randomUUID();
            InterfaceEntity existing = fakeEntity(id, "UI");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.updateInterface(eq(id), eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("name cannot exceed 255 characters"));

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("name", "ok");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).isEqualTo("name cannot exceed 255 characters");
            assertThat(res.get().error()).doesNotContain("Failed to update");
        }

        @Test
        @DisplayName("Should include visualization metadata on update")
        void shouldIncludeMetadataOnUpdate() {
            UUID id = UUID.randomUUID();
            InterfaceEntity existing = fakeEntity(id, "UI");
            InterfaceEntity updated = fakeEntity(id, "Updated UI");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.updateInterface(eq(id), eq(TENANT), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(updated);

            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("name", "Updated UI");

            Optional<ToolExecutionResult> res = module.execute("update", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().metadata()).isNotNull();

            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) res.get().metadata().get("visualization");
            assertThat(viz.get("type")).isEqualTo("interface");
        }
    }

    // ==================== patch ====================

    @Nested
    @DisplayName("patch")
    class PatchTests {

        private Map<String, Object> patchParams(UUID id, String target, List<Map<String, Object>> edits) {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            if (target != null) params.put("target", target);
            if (edits != null) params.put("edits", edits);
            return params;
        }

        private List<Map<String, Object>> oneEdit(String oldText, String newText) {
            Map<String, Object> e = new HashMap<>();
            e.put("old", oldText);
            e.put("new", newText);
            return List.of(e);
        }

        @Test
        @DisplayName("Should patch HTML and return PATCHED status with target + edit count")
        @SuppressWarnings("unchecked")
        void shouldPatchHtml() {
            UUID id = UUID.randomUUID();
            InterfaceEntity existing = fakeEntity(id, "Card");
            InterfaceEntity patched = fakeEntity(id, "Card");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(existing));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenReturn(patched);

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("Hello", "Welcome")), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("status")).isEqualTo("PATCHED");
            assertThat(data.get("target")).isEqualTo("html");
            assertThat(data.get("edits_applied")).isEqualTo(1);
            assertThat((String) data.get("marker")).contains("[visualize:interface:" + id);

            Map<String, Object> viz = (Map<String, Object>) res.get().metadata().get("visualization");
            assertThat(viz.get("type")).isEqualTo("interface");
            assertThat(viz.get("id")).isEqualTo(id.toString());
        }

        @Test
        @DisplayName("Should attach a red/green diff card built from the applied edits")
        @SuppressWarnings("unchecked")
        void shouldAttachDiffMetadata() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "Card")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenReturn(fakeEntity(id, "Card"));

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("<h1>Hello</h1>", "<h1>Welcome</h1>")), TENANT, ctx());

            assertThat(res).isPresent();
            Map<String, Object> diff = (Map<String, Object>) res.get().metadata().get("diff");
            assertThat(diff).as("patch must attach a diff card for the frontend DiffView").isNotNull();
            List<Map<String, Object>> files = (List<Map<String, Object>>) diff.get("files");
            assertThat(files).hasSize(1);
            Map<String, Object> f = files.get(0);
            assertThat(f.get("path")).isEqualTo("Card.html");
            assertThat(f.get("status")).isEqualTo("modified");
            assertThat(f.get("language")).isEqualTo("html");
            assertThat(f.get("additions")).isEqualTo(1);
            assertThat(f.get("deletions")).isEqualTo(1);
            String unified = (String) f.get("unifiedDiff");
            assertThat(unified).contains("-<h1>Hello</h1>");
            assertThat(unified).contains("+<h1>Welcome</h1>");
        }

        @Test
        @DisplayName("Should build one diff hunk per edit with summed add/del counts")
        @SuppressWarnings("unchecked")
        void shouldBuildMultiEditDiff() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenReturn(fakeEntity(id, "UI"));

            Map<String, Object> e1 = new HashMap<>(); e1.put("old", "A"); e1.put("new", "AA");
            Map<String, Object> e2 = new HashMap<>(); e2.put("old", "B"); e2.put("new", "BB");
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("target", "html");
            params.put("edits", List.of(e1, e2));

            Optional<ToolExecutionResult> res = module.execute("patch", params, TENANT, ctx());
            assertThat(res).isPresent();
            Map<String, Object> diff = (Map<String, Object>) res.get().metadata().get("diff");
            Map<String, Object> f = ((List<Map<String, Object>>) diff.get("files")).get(0);
            String unified = (String) f.get("unifiedDiff");
            assertThat(unified).contains("edit 1").contains("edit 2");
            assertThat(f.get("additions")).isEqualTo(2);
            assertThat(f.get("deletions")).isEqualTo(2);
        }

        @Test
        @DisplayName("Should mask token-shaped secrets in the patch diff card")
        @SuppressWarnings("unchecked")
        void shouldMaskSecretsInPatchDiff() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("js"), anyList(), anyBoolean())).thenReturn(fakeEntity(id, "UI"));

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "js",
                    oneEdit("x", "const k='" + "sk_" + "live_FAKE_interface'; const fn = computeKey(opts);")), TENANT, ctx());

            Map<String, Object> diff = (Map<String, Object>) res.get().metadata().get("diff");
            String unified = (String) ((List<Map<String, Object>>) diff.get("files")).get(0).get("unifiedDiff");
            assertThat(unified).doesNotContain("sk_" + "live_FAKE_interface");  // secret masked
            assertThat(unified).contains("computeKey(opts)");                    // benign code NOT corrupted
        }

        @Test
        @DisplayName("Should normalize target case and pass parsed edits + replace_all to the service")
        void shouldParseAliasesAndPassReplaceAll() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("css"), anyList(), eq(true))).thenReturn(fakeEntity(id, "UI"));

            // Alias keys (old_string/new_string) and uppercase target - both must be accepted.
            Map<String, Object> edit = new HashMap<>();
            edit.put("old_string", "color: red");
            edit.put("new_string", "color: blue");
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("target", "CSS");
            params.put("edits", List.of(edit));
            params.put("replace_all", true);

            Optional<ToolExecutionResult> res = module.execute("patch", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<List<InterfaceTemplatePatcher.Edit>> captor = ArgumentCaptor.forClass(List.class);
            verify(interfaceService).patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("css"), captor.capture(), eq(true));
            List<InterfaceTemplatePatcher.Edit> sent = captor.getValue();
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).oldText()).isEqualTo("color: red");
            assertThat(sent.get(0).newText()).isEqualTo("color: blue");
        }

        @Test
        @DisplayName("Should fail without interface_id")
        void shouldFailWithoutId() {
            Map<String, Object> params = new HashMap<>();
            params.put("target", "html");
            params.put("edits", oneEdit("a", "b"));

            Optional<ToolExecutionResult> res = module.execute("patch", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("interface_id is required");
        }

        @Test
        @DisplayName("Should fail without target")
        void shouldFailWithoutTarget() {
            UUID id = UUID.randomUUID();
            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, null, oneEdit("a", "b")), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("target is required");
            verifyNoInteractions(interfaceService);
        }

        @Test
        @DisplayName("Should fail with invalid target")
        void shouldFailInvalidTarget() {
            UUID id = UUID.randomUUID();
            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "xml", oneEdit("a", "b")), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("Invalid target");
            verifyNoInteractions(interfaceService);
        }

        @Test
        @DisplayName("Should fail without edits")
        void shouldFailWithoutEdits() {
            UUID id = UUID.randomUUID();
            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", null), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("edits is required");
            verifyNoInteractions(interfaceService);
        }

        @Test
        @DisplayName("Should fail with an empty edits list")
        void shouldFailEmptyEdits() {
            UUID id = UUID.randomUUID();
            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", List.of()), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("edits is required");
        }

        @Test
        @DisplayName("Should reject patch on a slide deck with a redirect to update")
        void shouldRejectSlideInterface() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null))
                .thenReturn(Optional.of(fakeSlideEntity(id, "Deck", 3)));

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("a", "b")), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not supported for slide decks");
            verify(interfaceService, never()).patchInterface(any(), any(), any(), any(), any(), anyList(), anyBoolean());
        }

        @Test
        @DisplayName("Should fail for a non-existent interface")
        void shouldFailForNonExistent() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.empty());

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("a", "b")), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not found");
        }

        @Test
        @DisplayName("A non-matching edit surfaces as a recoverable failure (code + get-first next_action)")
        @SuppressWarnings("unchecked")
        void shouldSurfacePatchExceptionAsRecoverable() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));

            // Build a REAL PatchException via the public patcher API (its ctor is package-private).
            InterfaceTemplatePatcher.PatchException realEx = null;
            try {
                InterfaceTemplatePatcher.apply("abc",
                    List.of(new InterfaceTemplatePatcher.Edit("zzz", "q")), false);
            } catch (InterfaceTemplatePatcher.PatchException ex) {
                realEx = ex;
            }
            assertThat(realEx).isNotNull();
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenThrow(realEx);

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("zzz", "q")), TENANT, ctx());

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not found");
            Map<String, Object> meta = res.get().metadata();
            assertThat(meta.get("code")).isEqualTo("PATCH_NOT_FOUND");
            assertThat((String) meta.get("next_action")).contains("interface(action='get'");
        }

        @Test
        @DisplayName("Ambiguous match: next_action prefers adding context and gates replace_all on changing ALL matches")
        @SuppressWarnings("unchecked")
        void ambiguousFailureHintGuardsReplaceAll() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));

            InterfaceTemplatePatcher.PatchException ambiguous = null;
            try {
                InterfaceTemplatePatcher.apply("a a", List.of(new InterfaceTemplatePatcher.Edit("a", "b")), false);
            } catch (InterfaceTemplatePatcher.PatchException ex) {
                ambiguous = ex;
            }
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenThrow(ambiguous);

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("a", "b")), TENANT, ctx());

            Map<String, Object> meta = res.get().metadata();
            assertThat(meta.get("code")).isEqualTo("PATCH_AMBIGUOUS");
            assertThat(meta.get("match_count")).isEqualTo(2);
            String hint = (String) meta.get("next_action");
            assertThat(hint).contains("add surrounding lines");  // safer option presented first
            assertThat(hint).contains("replace_all=true ONLY");  // guarded, not a blanket fix
            assertThat(hint).contains("ALL 2 occurrences");
        }

        @Test
        @DisplayName("No-op edit (old == new): failure carries PATCH_NO_OP code and a corrective next_action")
        @SuppressWarnings("unchecked")
        void noOpFailureCarriesNextAction() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));

            InterfaceTemplatePatcher.PatchException noop = null;
            try {
                InterfaceTemplatePatcher.apply("xy", List.of(new InterfaceTemplatePatcher.Edit("x", "x")), false);
            } catch (InterfaceTemplatePatcher.PatchException ex) {
                noop = ex;
            }
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenThrow(noop);

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(id, "html", oneEdit("x", "x")), TENANT, ctx());

            Map<String, Object> meta = res.get().metadata();
            assertThat(meta.get("code")).isEqualTo("PATCH_NO_OP");
            assertThat((String) meta.get("next_action")).contains("identical");
        }

        @Test
        @DisplayName("A patch on a non-existent interface never reaches the rate-limit counter (no slot burned)")
        void notFoundPatchNeverConsumesRateLimitSlot() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.empty());

            // Far more calls than the cap - every one must be RESOURCE_NOT_FOUND, never "STOP".
            for (int i = 0; i < 12; i++) {
                Optional<ToolExecutionResult> res = module.execute(
                    "patch", patchParams(id, "html", oneEdit("a", "b")), TENANT, ctx());
                assertThat(res).isPresent();
                assertThat(res.get().success()).isFalse();
                assertThat(res.get().error()).contains("not found");
                assertThat(res.get().error()).doesNotContain("STOP");
            }
        }

        @Test
        @DisplayName("Should enforce allowedInterfaceIds restriction on patch")
        void shouldEnforceAllowedIds() {
            UUID blockedId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("allowedInterfaceIds", List.of(UUID.randomUUID().toString()));

            Optional<ToolExecutionResult> res = module.execute(
                "patch", patchParams(blockedId, "html", oneEdit("a", "b")), TENANT, ctxWithVariables(variables));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not in your approved interface list");
        }

        @Test
        @DisplayName("Should enforce the patch rate limit (10 ok, 11th stopped)")
        void shouldEnforceRateLimit() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenReturn(fakeEntity(id, "UI"));

            for (int i = 0; i < 10; i++) {
                Optional<ToolExecutionResult> ok = module.execute(
                    "patch", patchParams(id, "html", oneEdit("v" + i, "w" + i)), TENANT, ctx());
                assertThat(ok).isPresent();
                assertThat(ok.get().success()).isTrue();
            }

            Optional<ToolExecutionResult> blocked = module.execute(
                "patch", patchParams(id, "html", oneEdit("more", "x")), TENANT, ctx());
            assertThat(blocked).isPresent();
            assertThat(blocked.get().success()).isFalse();
            assertThat(blocked.get().error()).contains("STOP");
        }

        @Test
        @DisplayName("A non-matching patch does NOT burn a rate-limit slot (refunded - the iterate-freely contract)")
        void failedPatchDoesNotBurnRateLimitSlot() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));

            InterfaceTemplatePatcher.PatchException realEx = null;
            try {
                InterfaceTemplatePatcher.apply("abc",
                    List.of(new InterfaceTemplatePatcher.Edit("zzz", "q")), false);
            } catch (InterfaceTemplatePatcher.PatchException ex) {
                realEx = ex;
            }
            // First call throws (match failure → refunded); every later call succeeds.
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean()))
                .thenThrow(realEx)
                .thenReturn(fakeEntity(id, "UI"));

            // One failed patch - must not consume a slot.
            Optional<ToolExecutionResult> failed = module.execute(
                "patch", patchParams(id, "html", oneEdit("zzz", "q")), TENANT, ctx());
            assertThat(failed).isPresent();
            assertThat(failed.get().success()).isFalse();

            // The full quota of successful patches (10) must still be available afterwards.
            for (int i = 0; i < 10; i++) {
                Optional<ToolExecutionResult> ok = module.execute(
                    "patch", patchParams(id, "html", oneEdit("v" + i, "w" + i)), TENANT, ctx());
                assertThat(ok).isPresent();
                assertThat(ok.get().success()).isTrue();
            }

            // Only now (after MAX successful patches) is the cap reached.
            Optional<ToolExecutionResult> blocked = module.execute(
                "patch", patchParams(id, "html", oneEdit("late", "x")), TENANT, ctx());
            assertThat(blocked.get().success()).isFalse();
            assertThat(blocked.get().error()).contains("STOP");
        }

        @Test
        @DisplayName("Edit key precedence: 'old'/'new' win over their aliases on the same object")
        void shouldPreferCanonicalKeysOverAliases() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), anyBoolean())).thenReturn(fakeEntity(id, "UI"));

            Map<String, Object> edit = new HashMap<>();
            edit.put("old", "canonical");
            edit.put("old_string", "alias");      // must be ignored in favor of 'old'
            edit.put("new", "winner");
            edit.put("replace", "loser");          // must be ignored in favor of 'new'
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("target", "html");
            params.put("edits", List.of(edit));

            module.execute("patch", params, TENANT, ctx());

            ArgumentCaptor<List<InterfaceTemplatePatcher.Edit>> captor = ArgumentCaptor.forClass(List.class);
            verify(interfaceService).patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), captor.capture(), anyBoolean());
            assertThat(captor.getValue().get(0).oldText()).isEqualTo("canonical");
            assertThat(captor.getValue().get(0).newText()).isEqualTo("winner");
        }

        @Test
        @DisplayName("replace_all accepts the string 'true' (liberal LLM input)")
        void shouldParseReplaceAllAsString() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), eq(true))).thenReturn(fakeEntity(id, "UI"));

            Map<String, Object> params = patchParams(id, "html", oneEdit("a", "b"));
            params.put("replace_all", "true");     // string form, not Boolean

            Optional<ToolExecutionResult> res = module.execute("patch", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            verify(interfaceService).patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("html"), anyList(), eq(true));
        }

        @Test
        @DisplayName("Should accept search/replace alias keys for edits")
        void shouldAcceptSearchReplaceAliasKeys() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            when(interfaceService.patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("js"), anyList(), anyBoolean())).thenReturn(fakeEntity(id, "UI"));

            Map<String, Object> edit = new HashMap<>();
            edit.put("search", "var a = 1");
            edit.put("replace", "var a = 2");
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", id.toString());
            params.put("target", "js");
            params.put("edits", List.of(edit));

            Optional<ToolExecutionResult> res = module.execute("patch", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<List<InterfaceTemplatePatcher.Edit>> captor = ArgumentCaptor.forClass(List.class);
            verify(interfaceService).patchInterface(eq(id), eq(TENANT), isNull(), isNull(),
                eq("js"), captor.capture(), anyBoolean());
            assertThat(captor.getValue().get(0).oldText()).isEqualTo("var a = 1");
            assertThat(captor.getValue().get(0).newText()).isEqualTo("var a = 2");
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should delete interface")
        void shouldDelete() {
            UUID id = UUID.randomUUID();
            InterfaceEntity entity = fakeEntity(id, "ToDelete");
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(entity));

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("delete", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("status")).isEqualTo("DELETED");
            assertThat(data.get("name")).isEqualTo("ToDelete");

            verify(interfaceService).deleteInterface(id, TENANT, null, null);
        }

        @Test
        @DisplayName("Should fail without interface_id")
        void shouldFailWithoutId() {
            Optional<ToolExecutionResult> res = module.execute("delete", Map.of(), TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("interface_id is required");
        }

        @Test
        @DisplayName("Non-UUID value on delete returns actionable hint (not 'is required')")
        void shouldHintWhenValueDoesNotResolve() {
            Map<String, Object> params = new HashMap<>();
            params.put("interface_id", "not-a-uuid");

            Optional<ToolExecutionResult> res = module.execute("delete", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("No interface matches 'not-a-uuid'");
            assertThat(res.get().error()).doesNotContain("interface_id is required");
        }

        @Test
        @DisplayName("Should enforce allowedInterfaceIds restriction on delete")
        void shouldEnforceAllowedIdsOnDelete() {
            UUID blockedId = UUID.randomUUID();
            Map<String, Object> variables = new HashMap<>();
            variables.put("allowedInterfaceIds", List.of(UUID.randomUUID().toString()));

            Map<String, Object> params = Map.of("interface_id", blockedId.toString());
            Optional<ToolExecutionResult> res = module.execute("delete", params, TENANT, ctxWithVariables(variables));
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("not in your approved interface list");
        }

        @Test
        @DisplayName("Should fail with NOT_FOUND when the interface does not exist (no phantom success)")
        void shouldFailWhenDeletingMissing() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.empty());

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("delete", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("Interface not found");

            // The repository must not be asked to delete a row that does not exist.
            verify(interfaceService, never()).deleteInterface(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Should propagate exception on delete")
        void shouldPropagateExceptionOnDelete() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            doThrow(new RuntimeException("Delete failed")).when(interfaceService).deleteInterface(id, TENANT, null, null);

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("delete", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("Delete failed");
        }

        @Test
        @DisplayName("Service IllegalArgumentException surfaces verbatim on delete")
        void shouldSurfaceIllegalArgumentVerbatimOnDelete() {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.of(fakeEntity(id, "UI")));
            doThrow(new IllegalArgumentException("Interface tenant mismatch"))
                .when(interfaceService).deleteInterface(id, TENANT, null, null);

            Map<String, Object> params = Map.of("interface_id", id.toString());
            Optional<ToolExecutionResult> res = module.execute("delete", params, TENANT, ctx());
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).isEqualTo("Interface tenant mismatch");
            assertThat(res.get().error()).doesNotContain("Failed to delete");
        }
    }

    // ==================== getToolDefinitions ====================

    @Test
    @DisplayName("getToolDefinitions should return empty list")
    void getToolDefinitions() {
        assertThat(module.getToolDefinitions()).isEmpty();
    }

    // ==========================================================================
    // V100: unified per-resource per-turn cap resolution for interface creation
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        @Test
        @DisplayName("Returns YAML default (5) when no override credential is present")
        void fallsBackToYamlDefault() {
            assertThat(module.resolveMaxPerResourcePerTurn(ctx())).isEqualTo(5);
        }

        @Test
        @DisplayName("Uses __chatMaxPerResourcePerTurn__ credential override when positive")
        void usesCredentialOverride() {
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__chatMaxPerResourcePerTurn__", 12, "turnId", "turn-x"),
                Map.of(), Set.of(), null, null, null, null);
            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(12);
        }

        @Test
        @DisplayName("Falls back to YAML default when credential override is zero or negative")
        void fallsBackWhenCredentialNonPositive() {
            ToolExecutionContext context = new ToolExecutionContext(
                TENANT,
                Map.of("__chatMaxPerResourcePerTurn__", 0, "turnId", "turn-x"),
                Map.of(), Set.of(), null, null, null, null);
            assertThat(module.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns YAML default when context is null")
        void handlesNullContext() {
            assertThat(module.resolveMaxPerResourcePerTurn(null)).isEqualTo(5);
        }

        @Test
        @DisplayName("Picks up YAML override from interface agent defaults config")
        void honorsYamlOverride() {
            agentDefaults.setMaxPerResourcePerTurn(3);
            assertThat(module.resolveMaxPerResourcePerTurn(ctx())).isEqualTo(3);
        }
    }
}
