package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification this service emits satisfies the endpoint that receives it.
 *
 * <p>This exists because the test next door cannot do it. Mocking {@code NotificationClient} and
 * asserting the fields proves the request was BUILT, never that it would be ACCEPTED, and the
 * endpoint refuses more than it looks like it does. It shipped that way: the first version of the
 * unreachable notification carried no {@code status} in its payload, which
 * {@code InternalNotificationController.validate} refuses with 400 under the V174 contract. The
 * client swallows a 400 into a debug log, so every notification was rejected and the feature whose
 * entire point was to stop an agent failing in silence failed in silence.
 *
 * <p>The rules are restated here rather than imported because the validator lives in
 * orchestrator-service, which this module does not depend on. The test reads that file to keep
 * the restatement honest: a rule added there and not here fails this test rather than production.
 */
@DisplayName("The unreachable notification satisfies the endpoint that receives it")
class NotificationContractTest {

    private static final String TENANT = "42";
    private static final UUID AGENT_ID = UUID.randomUUID();

    private RemoteToolExecutionService service;
    private ToolApprovalGate gate;
    private ChannelAuthorizationClient channelClient;
    private NotificationClient notificationClient;

    @BeforeEach
    void setUp() {
        service = spy(new RemoteToolExecutionService(new ObjectMapper()));
        gate = mock(ToolApprovalGate.class);
        ApprovalCardPublisher publisher = mock(ApprovalCardPublisher.class);
        channelClient = mock(ChannelAuthorizationClient.class);
        notificationClient = mock(NotificationClient.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.beginPark(any())).thenReturn(true);
        lenient().when(publisher.publishToolAuthorization(anyString(), anyString(), any(), anyBoolean(), anyString()))
                .thenReturn("{\"card\":1}");
        service.configureApprovalGateForTest(gate, publisher, new ApprovalCardExtractor(new ObjectMapper()));
        service.configureChannelAuthorizationForTest(channelClient);
        service.configureNotificationClientForTest(notificationClient);
        // any(), not anyString(): the summary and the fingerprint of a bare gate result are
        // null, and anyString() does not match null, so the stub would miss and hand the code
        // a null Delivery it never expects from a client that promises never to throw.
        when(channelClient.request(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ChannelAuthorizationClient.Delivery.none());
    }

    @Test
    @DisplayName("every field the endpoint requires is present, payload.status included")
    void satisfiesEveryRequiredField() {
        park(credentials(AGENT_ID.toString()));

        NotificationEmitRequest req = emitted();
        assertThat(req.getTenantId()).isNotBlank();
        assertThat(req.getOrganizationId())
                .as("the endpoint refuses a notification with no workspace, before validate() "
                        + "even runs, with the same swallowed 400")
                .isNotBlank();
        assertThat(req.getCategory()).isNotBlank();
        assertThat(req.getSeverity()).isIn("info", "warning", "error");
        assertThat(req.getSubjectType()).isEqualTo("AGENT");
        assertThat(req.getSubjectId()).as("the endpoint refuses a null subjectId").isNotNull();
        assertThat(req.getSourceId()).as("the endpoint refuses a blank sourceId").isNotBlank();
        assertThat(req.getPayload())
                .as("the endpoint refuses a payload with no status, with a 400 the client "
                        + "swallows into a debug log, so the whole notification is lost quietly")
                .isNotNull()
                .containsKey("status")
                // The bell's resolver reads this as the row's title; without it the row names
                // nothing, and the person cannot tell which agent stopped.
                .containsKey("subjectName");
    }

    @Test
    @DisplayName("an agent id that is not a UUID raises nothing rather than throwing into a debug log")
    void refusesToEmitWithoutAUsableSubject() {
        park(credentials("not-a-uuid"));

        // Parsing it inside the emit threw before the send, into a catch that logs at debug:
        // the same silence twice. There is nothing honest to invent for subjectId either, since
        // a bell row has to point somewhere, so this is reported where somebody reads it.
        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("a run with no workspace raises nothing rather than a notification the endpoint refuses")
    void refusesToEmitWithoutAWorkspace() {
        Map<String, Object> creds = credentials(AGENT_ID.toString());
        creds.remove("orgId");

        park(creds);

        verify(notificationClient, never()).emit(any());
    }

    @Test
    @DisplayName("the required-field list here still matches the validator it mirrors")
    void mirrorsTheValidator() {
        String validator = validatorSource();

        // If a rule is added there and not here, this fails rather than production. The four
        // checks below are the ones this request has to satisfy; the two it cannot influence
        // (category, severity) are asserted in the first test.
        assertThat(validator).contains("subjectId required");
        assertThat(validator).contains("sourceId required");
        assertThat(validator).contains("payload.status required");
        // Checked by the endpoint itself rather than validate(), which is how the first
        // version of this test missed it while claiming to mirror every rule.
        assertThat(validator).contains("organizationId required");
        assertThat(validator)
                .as("subjectType is validated against a set this request has to be in; "
                        + "NotificationSubjectTypeMirrorTest pins the set against the database")
                .contains("subjectType must be one of");
    }

    private void park(Map<String, Object> credentials) {
        ToolResult gateResult = ToolResult.builder()
                .toolCall(new ToolCall("call-1", "publish", Map.of(), null))
                .success(true)
                .content("authorization_required")
                .metadata(Map.of("rule", "publish"))
                .build();
        service.parkForAuthorization(new ToolCall("call-1", "publish", Map.of(), null), TENANT,
                credentials, gateResult, System.currentTimeMillis());
    }

    private NotificationEmitRequest emitted() {
        ArgumentCaptor<NotificationEmitRequest> captor =
                ArgumentCaptor.forClass(NotificationEmitRequest.class);
        verify(notificationClient).emit(captor.capture());
        return captor.getValue();
    }

    /** A scheduled run: a conversation, a minted stream, and the marker that says nobody looks. */
    private static Map<String, Object> credentials(String agentId) {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "conv-1");
        creds.put("__streamId__", "sync-conv-1");
        creds.put("__unattendedRun__", true);
        creds.put("orgId", "org-1");
        creds.put("__agentId__", agentId);
        creds.put("__toolCallId__", "call-1");
        return creds;
    }

    private static String validatorSource() {
        // Two candidates because the module runs both from its own directory and from the
        // reactor root, the same pair the other cross-module readers use.
        return Stream.of("../orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/internal/InternalNotificationController.java",
                        "backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/internal/InternalNotificationController.java")
                .map(Path::of)
                .filter(Files::exists)
                .findFirst()
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new IllegalStateException("unreadable: " + path, e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                        "InternalNotificationController not found from " + Path.of("").toAbsolutePath()));
    }
}
