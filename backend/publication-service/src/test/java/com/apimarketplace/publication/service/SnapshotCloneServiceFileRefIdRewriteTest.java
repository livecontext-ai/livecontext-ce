package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Acquire-clone FileRef id-rewrite (opaque-URL cutover).
 *
 * <p>The opaque by-id file URL is built from the FileRef's {@code id}, not its
 * {@code path}. When a publication is acquired, every embedded FileRef is
 * re-uploaded into the acquirer's tenant - a brand-new storage row with a NEW
 * id. The clone MUST adopt that new id; a FileRef left with the source row's id
 * renders 403/404 cross-tenant for the acquirer. These tests pin both clone
 * sites ({@code scanAndCloneFileRefs} for interface/agent data, and
 * {@code copyDataInputFiles} for Data Input nodes) via reflection.
 */
@DisplayName("SnapshotCloneService - acquire-clone FileRef id rewrite")
class SnapshotCloneServiceFileRefIdRewriteTest {

    private static final String TENANT = "acquirer";
    private static final String ACQ_ORG = "acquirer-org-uuid";
    private static final UUID PUBLICATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private OrchestratorInternalClient orchestratorClient;
    private SnapshotCloneService service;
    private Method scanAndCloneFileRefs;
    private Method copyDataInputFiles;

    @BeforeEach
    void setUp() throws Exception {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                mock(AgentClient.class),
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
        scanAndCloneFileRefs = SnapshotCloneService.class.getDeclaredMethod(
                "scanAndCloneFileRefs", Object.class, String.class, String.class, String.class, UUID.class, String.class);
        scanAndCloneFileRefs.setAccessible(true);
        copyDataInputFiles = SnapshotCloneService.class.getDeclaredMethod(
                "copyDataInputFiles", Map.class, String.class, String.class, String.class, String.class);
        copyDataInputFiles.setAccessible(true);
    }

    private Map<String, Object> namespacedFileRef(String name, String id) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        // Path must be under the publication namespace or the clone skips it.
        ref.put("path", "_publications/" + PUBLICATION_ID + "/snapshot/" + name);
        ref.put("name", name);
        ref.put("mimeType", "image/png");
        if (id != null) ref.put("id", id);
        return ref;
    }

    // ========================================================================
    // scanAndCloneFileRefs - interface / agent embedded FileRefs
    // ========================================================================

    @Test
    @DisplayName("scanAndCloneFileRefs adopts the new storage-row id returned by copyFile")
    void scanAndCloneFileRefsAdoptsNewStorageId() throws Exception {
        Map<String, Object> ref = namespacedFileRef("photo.png", "stale-source-id");
        ArgumentCaptor<String> orgCaptor = ArgumentCaptor.forClass(String.class);
        doReturn(Map.of("newPath", "acquirer/clone/photo.png", "newId", "fresh-id-123"))
                .when(orchestratorClient).copyFile(any(), any());

        scanAndCloneFileRefs.invoke(service, ref, TENANT, "iface-clone", "publication-clone", PUBLICATION_ID, ACQ_ORG);

        assertThat(ref.get("path")).isEqualTo("acquirer/clone/photo.png");
        assertThat(ref.get("id")).isEqualTo("fresh-id-123");
        // The acquirer's org MUST be forwarded so the new storage row is org-scoped (and gets an id).
        verify(orchestratorClient).copyFile(any(), orgCaptor.capture());
        assertThat(orgCaptor.getValue()).isEqualTo(ACQ_ORG);
    }

    @Test
    @DisplayName("scanAndCloneFileRefs drops a stale source id when copyFile returns no newId")
    void scanAndCloneFileRefsDropsStaleIdWhenNoNewId() throws Exception {
        Map<String, Object> ref = namespacedFileRef("photo.png", "stale-source-id");
        doReturn(Map.of("newPath", "acquirer/clone/photo.png"))
                .when(orchestratorClient).copyFile(any(), any());

        scanAndCloneFileRefs.invoke(service, ref, TENANT, "iface-clone", "publication-clone", PUBLICATION_ID, ACQ_ORG);

        assertThat(ref.get("path")).isEqualTo("acquirer/clone/photo.png");
        assertThat(ref).doesNotContainKey("id");
    }

    @Test
    @DisplayName("scanAndCloneFileRefs does NOT copy a FileRef whose path is outside the publication namespace - path and source id are left untouched")
    void scanAndCloneFileRefsSkipsOutOfNamespacePath() throws Exception {
        // A path that survived from the publisher's own tenant (or a DIFFERENT
        // publication) is outside `_publications/{thisPublicationId}/` and must
        // not be copied: the allowlist (audit C3-Hole#6) stops a corrupted
        // snapshot from leaking a foreign bucket key into the acquirer's namespace.
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", "_publications/00000000-0000-0000-0000-000000000000/snapshot/photo.png");
        ref.put("name", "photo.png");
        ref.put("mimeType", "image/png");
        ref.put("id", "source-row-id");

        Object copies = scanAndCloneFileRefs.invoke(
                service, ref, TENANT, "iface-clone", "publication-clone", PUBLICATION_ID, ACQ_ORG);

        // No copy attempted at all, and the ref is left exactly as-is (stale id KEPT,
        // so the acquirer 401s at render time - the intended defense-in-depth failure).
        assertThat(copies).isEqualTo(0);
        verify(orchestratorClient, never()).copyFile(any(), any());
        assertThat(ref.get("path"))
                .isEqualTo("_publications/00000000-0000-0000-0000-000000000000/snapshot/photo.png");
        assertThat(ref.get("id")).isEqualTo("source-row-id");
    }

    @Test
    @DisplayName("scanAndCloneFileRefs swallows a copyFile exception: the FileRef keeps its stale path/id and the clone continues (count 0)")
    void scanAndCloneFileRefsSwallowsCopyExceptionAndKeepsStalePath() throws Exception {
        // A copyFile failure during an interface/agent FileRef copy must NOT abort the
        // acquire (unlike the Data Input path, which rethrows). The FileRef is left with
        // its stale namespace path/id - the acquirer 401s at render time, the documented
        // degradation - while the rest of the clone proceeds.
        Map<String, Object> ref = namespacedFileRef("photo.png", "stale-source-id");
        org.mockito.Mockito.doThrow(new RuntimeException("storage unavailable"))
                .when(orchestratorClient).copyFile(any(), any());

        Object copies = scanAndCloneFileRefs.invoke(
                service, ref, TENANT, "iface-clone", "publication-clone", PUBLICATION_ID, ACQ_ORG);

        // Copy was attempted (namespace allowlist passed) but threw - swallowed, nothing rewritten.
        assertThat(copies).isEqualTo(0);
        verify(orchestratorClient).copyFile(any(), eq(ACQ_ORG));
        assertThat(ref.get("path"))
                .isEqualTo("_publications/" + PUBLICATION_ID + "/snapshot/photo.png");
        assertThat(ref.get("id")).isEqualTo("stale-source-id");
    }

    @Test
    @DisplayName("scanAndCloneFileRefs copies a namespaced ref but skips an out-of-namespace sibling in the same structure")
    void scanAndCloneFileRefsCopiesOnlyNamespacedRefsInMixedStructure() throws Exception {
        Map<String, Object> good = namespacedFileRef("good.png", "good-src-id");
        Map<String, Object> foreign = new LinkedHashMap<>();
        foreign.put("_type", "file");
        foreign.put("path", "publisher-tenant/raw/foreign.png");
        foreign.put("name", "foreign.png");
        foreign.put("id", "foreign-src-id");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("good", good);
        data.put("foreign", foreign);

        doReturn(Map.of("newPath", "acquirer/clone/good.png", "newId", "fresh-good-id"))
                .when(orchestratorClient).copyFile(any(), any());

        Object copies = scanAndCloneFileRefs.invoke(
                service, data, TENANT, "iface-clone", "publication-clone", PUBLICATION_ID, ACQ_ORG);

        // Exactly one copy, and it is the NAMESPACED ref: capture the request and assert the copied
        // sourcePath is the good ref's path, proving the foreign sibling was filtered out (not merely
        // that some single copy happened).
        assertThat(copies).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> reqCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(reqCaptor.capture(), eq(ACQ_ORG));
        assertThat(reqCaptor.getValue().get("sourcePath"))
                .isEqualTo("_publications/" + PUBLICATION_ID + "/snapshot/good.png");
        assertThat(good.get("path")).isEqualTo("acquirer/clone/good.png");
        assertThat(good.get("id")).isEqualTo("fresh-good-id");
        assertThat(foreign.get("path")).isEqualTo("publisher-tenant/raw/foreign.png");
        assertThat(foreign.get("id")).isEqualTo("foreign-src-id");
    }

    // ========================================================================
    // copyDataInputFiles - Data Input node files
    // ========================================================================

    @Test
    @DisplayName("copyDataInputFiles adopts the new storage-row id on the Data Input file map")
    void copyDataInputFilesAdoptsNewStorageId() throws Exception {
        Map<String, Object> fileMap = new LinkedHashMap<>();
        fileMap.put("path", "1/general/data-input/source.csv");
        fileMap.put("name", "source.csv");
        fileMap.put("id", "stale-source-id");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "file");
        item.put("file", fileMap);

        Map<String, Object> dataInput = new LinkedHashMap<>();
        dataInput.put("items", new ArrayList<>(List.of(item)));

        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "core:data_input");
        core.put("dataInput", dataInput);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new ArrayList<>(List.of(core)));

        doReturn(Map.of("newPath", "acquirer/clone/source.csv", "newId", "fresh-id-456"))
                .when(orchestratorClient).copyFile(any(), any());

        copyDataInputFiles.invoke(service, plan, TENANT, "wf-clone", "publication", ACQ_ORG);

        assertThat(fileMap.get("path")).isEqualTo("acquirer/clone/source.csv");
        assertThat(fileMap.get("id")).isEqualTo("fresh-id-456");
        // Data Input clone forwards the acquirer's org too.
        verify(orchestratorClient).copyFile(any(), eq(ACQ_ORG));
    }

    @Test
    @DisplayName("copyDataInputFiles drops a stale source id on the Data Input file map when copyFile returns no newId")
    void copyDataInputFilesDropsStaleIdWhenNoNewId() throws Exception {
        Map<String, Object> fileMap = new LinkedHashMap<>();
        fileMap.put("path", "1/general/data-input/source.csv");
        fileMap.put("name", "source.csv");
        fileMap.put("id", "stale-source-id");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "file");
        item.put("file", fileMap);

        Map<String, Object> dataInput = new LinkedHashMap<>();
        dataInput.put("items", new ArrayList<>(List.of(item)));

        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "core:data_input");
        core.put("dataInput", dataInput);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new ArrayList<>(List.of(core)));

        doReturn(Map.of("newPath", "acquirer/clone/source.csv"))
                .when(orchestratorClient).copyFile(any(), any());

        copyDataInputFiles.invoke(service, plan, TENANT, "wf-clone", "publication", ACQ_ORG);

        assertThat(fileMap.get("path")).isEqualTo("acquirer/clone/source.csv");
        assertThat(fileMap).doesNotContainKey("id");
    }
}
