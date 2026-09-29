package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.web.SharedApplicationScope;
import com.apimarketplace.common.web.SharedApplicationScopeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rule both file doors (cloud FileController, CE MonolithFileController) apply to a share-link
 * read: only files of the shared application, never another file of the owner's workspace.
 */
@DisplayName("SharedApplicationFileScope - which files a share link may read")
class SharedApplicationFileScopeTest {

    private static final UUID PUB = UUID.randomUUID();
    private final SharedApplicationScopeClient client = mock(SharedApplicationScopeClient.class);

    private static SharedApplicationScope share() {
        return SharedApplicationScope.of("true", "APPLICATION", PUB.toString());
    }

    private static final UUID FILE_ID = UUID.randomUUID();

    private static StorageEntity file(String runId, String workflowId, UUID sourcePublicationId) {
        StorageEntity e = new StorageEntity();
        e.setId(FILE_ID);
        e.setRunId(runId);
        e.setWorkflowId(workflowId);
        e.setSourcePublicationId(sourcePublicationId);
        return e;
    }

    @Test
    @DisplayName("no share context: every file permitted, no remote check")
    void noShare_permitted() {
        assertThat(SharedApplicationFileScope.permits(SharedApplicationScope.of(null, null, null),
                file("r", "w", null), "t", "o", client)).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("file tagged with the shared publication: permitted locally")
    void publicationTagged_permitted() {
        assertThat(SharedApplicationFileScope.permits(share(), file(null, null, PUB), "t", "o", client)).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("other owner file: asks orchestrator and follows its answer")
    void otherFile_followsOrchestrator() {
        when(client.fileBelongsToApplication(PUB, "t", "o", "run_x", "wf_x", FILE_ID)).thenReturn(false);
        assertThat(SharedApplicationFileScope.permits(share(), file("run_x", "wf_x", null), "t", "o", client)).isFalse();

        when(client.fileBelongsToApplication(PUB, "t", "o", "run_app", null, FILE_ID)).thenReturn(true);
        assertThat(SharedApplicationFileScope.permits(share(), file("run_app", null, null), "t", "o", client)).isTrue();
    }

    @Test
    @DisplayName("untagged file (catalog binary upload): its id is sent so orchestrator can find the referencing output")
    void untaggedFile_sendsItsId() {
        when(client.fileBelongsToApplication(PUB, "t", "o", null, null, FILE_ID)).thenReturn(true);
        assertThat(SharedApplicationFileScope.permits(share(), file(null, null, null), "t", "o", client)).isTrue();
    }

    @Test
    @DisplayName("share context without a client, or of a non-APPLICATION type: refused")
    void failClosed() {
        assertThat(SharedApplicationFileScope.permits(share(), file("run_app", null, null), "t", "o", null)).isFalse();
        assertThat(SharedApplicationFileScope.permits(SharedApplicationScope.of("true", "CHAT", PUB.toString()),
                file(null, null, PUB), "t", "o", client)).isFalse();
        assertThat(SharedApplicationFileScope.permits(SharedApplicationScope.of("true", "APPLICATION", "not-a-uuid"),
                file(null, null, PUB), "t", "o", client)).isFalse();
    }
}
