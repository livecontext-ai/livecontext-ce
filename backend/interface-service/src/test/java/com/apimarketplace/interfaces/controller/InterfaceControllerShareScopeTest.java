package com.apimarketplace.interfaces.controller;

import com.apimarketplace.common.web.SharedApplicationScopeClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.service.InterfaceDtoMapper;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A share token authenticates its holder AS THE OWNER. Before this binding, GET
 * /api/interfaces/{id} under a share link returned ANY interface of the owner's workspace.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceController - share-link binding on GET /{id}")
class InterfaceControllerShareScopeTest {

    private static final String OWNER = "owner-1";
    private static final String ORG = "org-1";

    @Mock private InterfaceService interfaceService;
    @Mock private InterfaceSnapshotService snapshotService;
    @Mock private SharedApplicationScopeClient scopeClient;

    private MockMvc mockMvc;
    private final UUID interfaceId = UUID.randomUUID();
    private final UUID sharedPublication = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        InterfaceController controller = new InterfaceController(
                interfaceService, snapshotService, new InterfaceDtoMapper(), new TenantResolver(), scopeClient);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private InterfaceEntity ownerInterface() {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(interfaceId);
        entity.setTenantId(OWNER);
        entity.setOrganizationId(ORG);
        entity.setName("Owner page");
        entity.setHtmlTemplate("<div>private</div>");
        return entity;
    }

    @Test
    @DisplayName("share link: an owner interface outside the shared application is a 404 and is never loaded")
    void shareForeignInterfaceIs404() throws Exception {
        when(scopeClient.interfaceBelongsToApplication(sharedPublication, ORG, interfaceId))
                .thenReturn(false);

        mockMvc.perform(get("/api/interfaces/" + interfaceId)
                        .header("X-User-ID", OWNER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isNotFound());
        verify(interfaceService, never()).getInterface(any(), any(), any());
    }

    @Test
    @DisplayName("share link: an interface of the shared application is served")
    void shareApplicationInterfaceIsServed() throws Exception {
        when(scopeClient.interfaceBelongsToApplication(sharedPublication, ORG, interfaceId))
                .thenReturn(true);
        when(interfaceService.getInterface(interfaceId, OWNER, ORG)).thenReturn(Optional.of(ownerInterface()));

        mockMvc.perform(get("/api/interfaces/" + interfaceId)
                        .header("X-User-ID", OWNER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Owner page"));
    }

    @Test
    @DisplayName("share link of another type (or no resource token) is a 404 without asking orchestrator")
    void nonApplicationShareIs404() throws Exception {
        mockMvc.perform(get("/api/interfaces/" + interfaceId)
                        .header("X-User-ID", OWNER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "CONVERSATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(scopeClient);
    }

    @Test
    @DisplayName("no share context: the owner still reads their interface without any scope call")
    void ownerReadUnchanged() throws Exception {
        when(interfaceService.getInterface(interfaceId, OWNER, ORG)).thenReturn(Optional.of(ownerInterface()));

        mockMvc.perform(get("/api/interfaces/" + interfaceId)
                        .header("X-User-ID", OWNER)
                        .header("X-Organization-ID", ORG))
                .andExpect(status().isOk());
        verifyNoInteractions(scopeClient);
    }
}
