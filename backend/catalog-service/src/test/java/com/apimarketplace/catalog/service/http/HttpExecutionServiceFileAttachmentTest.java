package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.execution.FileAttachmentException;
import com.apimarketplace.catalog.service.execution.FileAttachmentResolver;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The wiring between a parameter that declares {@code fileAttachments}, the
 * resolver that reads the bytes, and the body that goes on the wire.
 *
 * <p>Each piece is unit-tested on its own; what these pin is that they are
 * actually connected, in the shape the failure needed. A Gmail send used to have
 * no attachment parameter at all, and every other mail endpoint asked for base64
 * that no caller could produce.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - file attachments on a JSON body")
class HttpExecutionServiceFileAttachmentTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;
    @Mock private StorageClient storageClient;

    private ObjectMapper objectMapper;
    private HttpExecutionService service;

    private static final String SENDGRID_EXTRAS =
            "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\",\"mimeField\":\"type\"}}";
    /**
     * The ceiling READ OUT of the shipped Gmail seed, not a copy of it.
     *
     * <p>A constant duplicated here would have let the seed declare any value at
     * all while this file went on proving the arithmetic of a number nobody ships.
     * Read from the seed, a wrong ceiling fails
     * {@link #gmailCeilingLeavesRoomForTheSecondEncoding} instead.
     */
    private static final int GMAIL_CEILING = shippedGmailCeiling();
    private static final String GMAIL_EXTRAS = gmailExtras();

    /**
     * Every Gmail endpoint that rebuilds the message declares the same ceiling.
     *
     * <p>Read as a set rather than one value: the arithmetic below is proved for
     * whatever number this returns, and if create_draft or update_draft drifted to
     * a different one, nothing would prove it for THAT number.
     */
    private static int shippedGmailCeiling() {
        java.util.Set<Integer> ceilings = new java.util.LinkedHashSet<>();
        for (String endpoint : List.of("send_message", "create_draft", "update_draft")) {
            ceilings.add(shippedGmailCeiling(endpoint));
        }
        if (ceilings.size() != 1) {
            throw new IllegalStateException("the Gmail endpoints that rebuild the message declare "
                    + "different attachment ceilings " + ceilings + ", so this test proves the "
                    + "arithmetic for only one of them");
        }
        return ceilings.iterator().next();
    }

    private static int shippedGmailCeiling(String endpointName) {
        for (java.nio.file.Path candidate : List.of(
                java.nio.file.Paths.get("..", "..", "scripts", "api-migrations", "gmail.json"),
                java.nio.file.Paths.get("..", "scripts", "api-migrations", "gmail.json"),
                java.nio.file.Paths.get("scripts", "api-migrations", "gmail.json"))) {
            if (!java.nio.file.Files.isRegularFile(candidate)) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode gmail =
                        new ObjectMapper().readTree(candidate.toFile());
                for (com.fasterxml.jackson.databind.JsonNode endpoint : gmail.path("endpoints")) {
                    if (!endpointName.equals(endpoint.path("name").asText())) continue;
                    for (com.fasterxml.jackson.databind.JsonNode param : endpoint.path("params")) {
                        if ("attachments".equals(param.path("name").asText())) {
                            return param.path("fileAttachments").path("maxTotalBytes").asInt();
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read the shipped gmail seed", e);
            }
        }
        throw new IllegalStateException("could not locate scripts/api-migrations/gmail.json");
    }

    private static String gmailExtras() {
        return "{\"fileAttachments\":{\"nameField\":\"filename\",\"contentField\":\"content\","
                + "\"mimeField\":\"type\",\"maxTotalBytes\":" + GMAIL_CEILING + "}}";
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine());
        ReflectionTestUtils.setField(service, "fileAttachmentResolver",
                new FileAttachmentResolver(storageClient, objectMapper, 20L * 1024 * 1024));
    }

    private ApiToolEntity tool(String endpoint, String runtimeMetadata) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setEndpoint(endpoint);
        tool.setMethod("POST");
        tool.setRuntimeMetadata(runtimeMetadata);
        return tool;
    }

    private ApiToolParameterEntity param(String name, String dataType, String extras) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setName(name);
        p.setParameterType("body");
        p.setDataType(dataType);
        p.setExtras(extras);
        return p;
    }

    private ObjectNode fileRefNode(String path, String name, String mimeType) {
        return objectMapper.createObjectNode()
                .put("_type", "file")
                .put("path", path)
                .put("name", name)
                .put("mimeType", mimeType);
    }

    /** {@code [{"to": ...}, {"attachments": [fileRef]}]} - the shape the engine passes in. */
    private ArrayNode params(Map<String, com.fasterxml.jackson.databind.JsonNode> values) {
        ArrayNode parameters = objectMapper.createArrayNode();
        values.forEach((k, v) -> parameters.add(objectMapper.createObjectNode().set(k, v)));
        return parameters;
    }

    @Test
    @DisplayName("a declared attachment param turns the caller's file into the provider's object")
    void resolvesFileRefIntoProviderObject() {
        ApiToolEntity tool = tool("/mail/send", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(
                param("subject", "string", null),
                param("attachments", "array", SENDGRID_EXTRAS)));
        when(storageClient.download("tenant-9", "tenant-9/report.pdf")).thenReturn("PDF".getBytes());

        ArrayNode parameters = objectMapper.createArrayNode();
        parameters.add(objectMapper.createObjectNode().put("subject", "Hello"));
        parameters.add(objectMapper.createObjectNode().set("attachments",
                objectMapper.createArrayNode().add(fileRefNode("tenant-9/report.pdf", "report.pdf", "application/pdf"))));

        Object body = service.prepareRequestBody(tool, parameters, "tenant-9");

        assertInstanceOf(Map.class, body);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        assertEquals("Hello", map.get("subject"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) map.get("attachments");
        assertEquals(1, attachments.size());
        assertEquals("report.pdf", attachments.get(0).get("filename"));
        assertEquals("application/pdf", attachments.get(0).get("type"));
        assertEquals(Base64.getEncoder().encodeToString("PDF".getBytes()), attachments.get(0).get("content"));
    }

    @Test
    @DisplayName("the bytes are read under the tenant the call runs for, not a default one")
    void readsBytesUnderCallingTenant() {
        ApiToolEntity tool = tool("/mail/send", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(param("attachments", "array", SENDGRID_EXTRAS)));
        when(storageClient.download(any(), any())).thenReturn("X".getBytes());

        service.prepareRequestBody(tool,
                params(Map.of("attachments", fileRefNode("t/a.txt", "a.txt", "text/plain"))),
                "tenant-77");

        verify(storageClient).download(eq("tenant-77"), eq("t/a.txt"));
    }

    @Test
    @DisplayName("a parameter with no attachment descriptor is left exactly as it was")
    void leavesOrdinaryParamsUntouched() {
        ApiToolEntity tool = tool("/mail/send", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(param("payload", "object", null)));

        Object body = service.prepareRequestBody(tool,
                params(Map.of("payload", objectMapper.createObjectNode().put("path", "t/looks-like-a-file"))),
                "tenant-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        assertInstanceOf(Map.class, map.get("payload"));
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("an unreadable file fails the call instead of sending a mail without it")
    void unreadableFileEscapesTheBodyCatch() {
        ApiToolEntity tool = tool("/mail/send", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(param("attachments", "array", SENDGRID_EXTRAS)));
        when(storageClient.download(any(), any())).thenReturn(null);

        FileAttachmentException thrown = assertThrows(FileAttachmentException.class, () ->
                service.prepareRequestBody(tool,
                        params(Map.of("attachments", fileRefNode("t/gone.pdf", "gone.pdf", "application/pdf"))),
                        "tenant-1"));

        assertTrue(thrown.getMessage().contains("could not be read"),
                "prepareRequestBody swallows every other exception into a null body; this one must escape");
    }

    @Test
    @DisplayName("Gmail: the resolved file becomes a real MIME part inside the base64url raw message")
    void gmailBuildsMultipartMixedFromResolvedFile() {
        ApiToolEntity tool = tool("/users/me/messages/send", "{\"bodyTransform\":\"rfc2822\"}");
        when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(
                param("to", "string", null),
                param("subject", "string", null),
                param("body", "string", null),
                param("attachments", "array", GMAIL_EXTRAS)));
        when(storageClient.download(any(), any())).thenReturn("INVOICE-BYTES".getBytes());

        ArrayNode parameters = objectMapper.createArrayNode();
        parameters.add(objectMapper.createObjectNode().put("to", "someone@example.com"));
        parameters.add(objectMapper.createObjectNode().put("subject", "Invoice"));
        parameters.add(objectMapper.createObjectNode().put("body", "See attached"));
        parameters.add(objectMapper.createObjectNode().set("attachments",
                objectMapper.createArrayNode().add(fileRefNode("t/invoice.pdf", "invoice.pdf", "application/pdf"))));

        Object body = service.prepareRequestBody(tool, parameters, "tenant-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) body;
        String mime = new String(Base64.getUrlDecoder().decode((String) message.get("raw")), StandardCharsets.UTF_8);

        assertTrue(mime.contains("Content-Type: multipart/mixed; boundary=\""),
                "pre-fix: Gmail had no attachment parameter, so this was always a single-part message");
        assertTrue(mime.contains("Content-Disposition: attachment; filename=\"invoice.pdf\""));
        assertTrue(mime.contains(Base64.getEncoder().encodeToString("INVOICE-BYTES".getBytes())));
        assertTrue(mime.contains("To: someone@example.com"));
        assertTrue(mime.contains("See attached"));
        // The file must not also be left in the JSON body next to raw.
        assertEquals(Map.of("raw", message.get("raw")), message);
    }

    @Test
    @DisplayName("Gmail: a batch on the declared ceiling still fits the 5 MB request Gmail accepts")
    void gmailCeilingLeavesRoomForTheSecondEncoding() {
        // The ceiling is counted on the FIRST encoding, and the transform then
        // base64url-encodes the whole message a second time, over base64 lines the
        // writer has folded every 76 characters. A ceiling picked as 5 MB, or even
        // as 5 MB times 3/4, puts a batch that passed it over the request limit,
        // and nothing here would have said so.
        ApiToolEntity tool = tool("/users/me/messages/send", "{\"bodyTransform\":\"rfc2822\"}");
        when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(
                param("body", "string", null),
                param("attachments", "array", gmailExtras())));
        // Raw bytes whose base64 lands just under the shipped ceiling.
        when(storageClient.download(any(), any())).thenReturn(new byte[GMAIL_CEILING / 4 * 3 - 16]);

        ArrayNode parameters = objectMapper.createArrayNode();
        parameters.add(objectMapper.createObjectNode().put("body", "See attached"));
        parameters.add(objectMapper.createObjectNode().set("attachments",
                objectMapper.createArrayNode().add(fileRefNode("t/big.bin", "big.bin", "application/octet-stream"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) service.prepareRequestBody(tool, parameters, "t");
        int rawLength = ((String) message.get("raw")).length();

        assertTrue(rawLength < 5_000_000,
                "the raw field is what Gmail's 5 MB request limit applies to, and it came to " + rawLength);
    }

    @Test
    @DisplayName("a hand-built attachment counts against the ceiling under any field name the writer accepts")
    void handBuiltAttachmentCountsUnderAnyContentAlias() {
        // The ceiling read only the descriptor's own spelling while the MIME writer
        // accepted four, so an attachment sent as "data" rather than "content" was
        // written to the wire and counted as zero.
        ApiToolEntity tool = tool("/mail/send", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(param("attachments", "array", gmailExtras())));

        ObjectNode handBuilt = objectMapper.createObjectNode()
                .put("filename", "huge.bin")
                .put("data", "A".repeat(GMAIL_CEILING + 1000));

        assertThrows(FileAttachmentException.class, () -> service.prepareRequestBody(tool,
                params(Map.of("attachments", objectMapper.createArrayNode().add(handBuilt))), "t"));
    }

    @Test
    @DisplayName("a blank content field does not hide a full one under another name")
    void blankDescriptorFieldDoesNotHideTheRealContent() {
        // The ceiling stopped at the first NON-NULL field while the MIME writer
        // skipped BLANK ones, so an object carrying content:"" beside a full data:
        // counted as nothing and was written in full. Sharing the field names was
        // not enough; the two halves have to share the choosing.
        ApiToolEntity tool = tool("/mail/send", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId()))
                .thenReturn(List.of(param("attachments", "array", gmailExtras())));

        ObjectNode handBuilt = objectMapper.createObjectNode()
                .put("filename", "huge.bin")
                .put("content", "")
                .put("data", "A".repeat(GMAIL_CEILING + 1000));

        assertThrows(FileAttachmentException.class, () -> service.prepareRequestBody(tool,
                params(Map.of("attachments", objectMapper.createArrayNode().add(handBuilt))), "t"));
    }

    @Test
    @DisplayName("Gmail: threadId reaches the message resource so a reply joins the conversation")
    void gmailCarriesThreadIdThroughTheTransform() {
        ApiToolEntity tool = tool("/users/me/messages/send", "{\"bodyTransform\":\"rfc2822\"}");
        when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(
                param("to", "string", null),
                param("body", "string", null),
                param("threadId", "string", null)));

        ArrayNode parameters = objectMapper.createArrayNode();
        parameters.add(objectMapper.createObjectNode().put("to", "someone@example.com"));
        parameters.add(objectMapper.createObjectNode().put("body", "Replying"));
        parameters.add(objectMapper.createObjectNode().put("threadId", "thread-123"));

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) service.prepareRequestBody(tool, parameters, "tenant-1");

        assertEquals("thread-123", message.get("threadId"),
                "pre-fix: threadId was declared on the endpoint and dropped by the transform, "
                        + "so every reply silently started a new conversation");
    }

    @Test
    @DisplayName("with no resolver bean at all, Gmail refuses rather than sending an empty multipart")
    void withoutResolverGmailStillRefuses() {
        // The resolver is @Autowired(required = false), so a context without it
        // leaves every FileRef unresolved. Pre-fix those unresolved entries were
        // counted and then dropped, and the mail went out with no file on it.
        ReflectionTestUtils.setField(service, "fileAttachmentResolver", null);
        ApiToolEntity tool = tool("/users/me/messages/send", "{\"bodyTransform\":\"rfc2822\"}");
        when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(
                param("body", "string", null),
                param("attachments", "array", GMAIL_EXTRAS)));

        ArrayNode parameters = objectMapper.createArrayNode();
        parameters.add(objectMapper.createObjectNode().put("body", "See attached"));
        parameters.add(objectMapper.createObjectNode().set("attachments",
                objectMapper.createArrayNode().add(fileRefNode("t/a.pdf", "a.pdf", "application/pdf"))));

        assertThrows(FileAttachmentException.class,
                () -> service.prepareRequestBody(tool, parameters, "tenant-1"));
    }

    @Test
    @DisplayName("Microsoft Graph: the attachment lands under its bodyPath carrying the odata discriminator")
    void graphNestsAttachmentUnderBodyPath() {
        ApiToolEntity tool = tool("/me/sendMail", null);
        when(apiToolParameterRepository.findByApiToolId(tool.getId())).thenReturn(List.of(
                param("attachments", "array",
                        "{\"bodyPath\":\"message.attachments\",\"fileAttachments\":{"
                                + "\"nameField\":\"name\",\"contentField\":\"contentBytes\",\"mimeField\":\"contentType\","
                                + "\"constantFields\":{\"@odata.type\":\"#microsoft.graph.fileAttachment\"}}}")));
        when(storageClient.download(any(), any())).thenReturn("DOC".getBytes());

        Object body = service.prepareRequestBody(tool,
                params(Map.of("attachments",
                        objectMapper.createArrayNode().add(fileRefNode("t/doc.docx", "doc.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))),
                "tenant-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) map.get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) message.get("attachments");
        assertEquals("#microsoft.graph.fileAttachment", attachments.get(0).get("@odata.type"));
        assertEquals("doc.docx", attachments.get(0).get("name"));
        assertEquals(Base64.getEncoder().encodeToString("DOC".getBytes()), attachments.get(0).get("contentBytes"));
    }
}
