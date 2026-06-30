package com.apimarketplace.catalog.service.http.bodypath;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyPathExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String json(Map<String, Object> body) throws JsonProcessingException {
        return mapper.writeValueAsString(body);
    }

    @Nested
    @DisplayName("Literal paths")
    class Literals {
        @Test
        void singleKeyWritesTopLevel() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "title", "Hello");
            assertThat(json(body)).isEqualTo("{\"title\":\"Hello\"}");
        }

        @Test
        void twoLevelNesting() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "info.title", "Hello");
            assertThat(json(body)).isEqualTo("{\"info\":{\"title\":\"Hello\"}}");
        }

        @Test
        void multipleParamsFuseUnderSameKey() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "info.title", "Hello");
            BodyPathExecutor.apply(body, "info.documentTitle", "Doc");
            assertThat(json(body))
                    .isEqualTo("{\"info\":{\"title\":\"Hello\",\"documentTitle\":\"Doc\"}}");
        }

        @Test
        void deepNestingFourLevels() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "configuration.query.destinationTable.tableId", "T1");
            assertThat(json(body)).isEqualTo(
                    "{\"configuration\":{\"query\":{\"destinationTable\":{\"tableId\":\"T1\"}}}}");
        }

        @Test
        void declarationOrderIsPreserved() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "z", 1);
            BodyPathExecutor.apply(body, "a", 2);
            BodyPathExecutor.apply(body, "m", 3);
            assertThat(json(body)).isEqualTo("{\"z\":1,\"a\":2,\"m\":3}");
        }

        @Test
        void typePreservationIntStaysInt() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "n.count", 42);
            assertThat(json(body)).isEqualTo("{\"n\":{\"count\":42}}");
        }

        @Test
        void typePreservationBooleanStaysBoolean() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "flag", true);
            assertThat(json(body)).isEqualTo("{\"flag\":true}");
        }
    }

    @Nested
    @DisplayName("IndexedArray paths [N]")
    class IndexedArrays {
        @Test
        void singleParamCreatesArrayWithMap() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.title", "Q1");
            assertThat(json(body)).isEqualTo(
                    "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":\"Q1\"}}}]}");
        }

        @Test
        void multipleParamsFuseAtSameIndex() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.title", "Q1");
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.gridProperties.rowCount", 100);
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.gridProperties.columnCount", 20);
            assertThat(json(body)).isEqualTo(
                    "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":\"Q1\",\"gridProperties\":{\"rowCount\":100,\"columnCount\":20}}}}]}");
        }

        @Test
        void terminalIndexedArrayWritesScalar() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "tags[0]", "first");
            assertThat(json(body)).isEqualTo("{\"tags\":[\"first\"]}");
        }

        @Test
        void sparseIndexAfterDenseExtends() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "items[0].a", "x");
            BodyPathExecutor.apply(body, "items[1].b", "y");
            assertThat(json(body)).isEqualTo("{\"items\":[{\"a\":\"x\"},{\"b\":\"y\"}]}");
        }

        @Test
        void trueSparseIndexRejected() {
            Map<String, Object> body = new LinkedHashMap<>();
            assertThatThrownBy(() -> BodyPathExecutor.apply(body, "items[2].a", "x"))
                    .isInstanceOf(BodyPathException.Grammar.class)
                    .hasMessageContaining("sparse");
        }
    }

    @Nested
    @DisplayName("MappedArray paths []")
    class MappedArrays {
        @Test
        void msGraphRecipientsWrap() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body,
                    "message.toRecipients[].emailAddress.address",
                    List.of("alice@example.com", "bob@example.com"));
            assertThat(json(body)).isEqualTo(
                    "{\"message\":{\"toRecipients\":["
                            + "{\"emailAddress\":{\"address\":\"alice@example.com\"}},"
                            + "{\"emailAddress\":{\"address\":\"bob@example.com\"}}"
                            + "]}}");
        }

        @Test
        void singleElementListProducesSingleEntry() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "items[].id", List.of("x"));
            assertThat(json(body)).isEqualTo("{\"items\":[{\"id\":\"x\"}]}");
        }

        @Test
        void emptyListProducesEmptyArray() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "items[].id", new ArrayList<>());
            assertThat(json(body)).isEqualTo("{\"items\":[]}");
        }

        @Test
        void nullValueSkipsSilently() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "items[].id", null);
            assertThat(body).isEmpty();
        }

        @Test
        void scalarAtArrayMappingThrowsArity() {
            Map<String, Object> body = new LinkedHashMap<>();
            assertThatThrownBy(() -> BodyPathExecutor.apply(body, "items[].id", "x"))
                    .isInstanceOf(BodyPathException.Arity.class)
                    .hasMessageContaining("items[]");
        }

        @Test
        void terminalArrayMapWritesListDirectly() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "tags[]", List.of("a", "b", "c"));
            assertThat(json(body)).isEqualTo("{\"tags\":[\"a\",\"b\",\"c\"]}");
        }

        @Test
        void multipleArrayMapsOnSameArrayFuseByIndex() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "users[].id", List.of("u1", "u2"));
            BodyPathExecutor.apply(body, "users[].name", List.of("alice", "bob"));
            assertThat(json(body)).isEqualTo(
                    "{\"users\":[{\"id\":\"u1\",\"name\":\"alice\"},{\"id\":\"u2\",\"name\":\"bob\"}]}");
        }

        @Test
        void sizeMismatchOnSameArrayThrows() {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "users[].id", List.of("u1", "u2"));
            assertThatThrownBy(() ->
                    BodyPathExecutor.apply(body, "users[].name", List.of("alice")))
                    .isInstanceOf(BodyPathException.SizeMismatch.class);
        }

        @Test
        void differentArraysCoexistUnderSameParent() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "message.toRecipients[].emailAddress.address",
                    List.of("a@b.c"));
            BodyPathExecutor.apply(body, "message.ccRecipients[].emailAddress.address",
                    List.of("c@d.e"));
            BodyPathExecutor.apply(body, "saveToSentItems", true);
            assertThat(json(body)).isEqualTo(
                    "{\"message\":{"
                            + "\"toRecipients\":[{\"emailAddress\":{\"address\":\"a@b.c\"}}],"
                            + "\"ccRecipients\":[{\"emailAddress\":{\"address\":\"c@d.e\"}}]"
                            + "},\"saveToSentItems\":true}");
        }
    }

    @Nested
    @DisplayName("Conflict detection")
    class Conflicts {
        @Test
        void mapWhereScalarWasThrowsConflict() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("info", "already a string");
            assertThatThrownBy(() -> BodyPathExecutor.apply(body, "info.title", "X"))
                    .isInstanceOf(BodyPathException.Conflict.class)
                    .hasMessageContaining("info");
        }

        @Test
        void listWhereMapWasThrowsConflict() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("info", new LinkedHashMap<>());
            assertThatThrownBy(() -> BodyPathExecutor.apply(body, "info[0].x", "X"))
                    .isInstanceOf(BodyPathException.Conflict.class);
        }
    }

    @Nested
    @DisplayName("Real-world endpoint shapes")
    class RealWorld {
        @Test
        void formsCreateFormShape() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "info.title", "Customer Feedback");
            BodyPathExecutor.apply(body, "info.documentTitle", "Q1 2024 Feedback");
            assertThat(json(body)).isEqualTo(
                    "{\"info\":{\"title\":\"Customer Feedback\",\"documentTitle\":\"Q1 2024 Feedback\"}}");
        }

        @Test
        void formsCreateWatchShape() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> target = new LinkedHashMap<>();
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("topicName", "projects/p/topics/t");
            target.put("topic", topic);
            BodyPathExecutor.apply(body, "watch.eventType", "RESPONSES");
            BodyPathExecutor.apply(body, "watch.target", target);
            BodyPathExecutor.apply(body, "watchId", "watch-001");
            assertThat(json(body)).isEqualTo(
                    "{\"watch\":{\"eventType\":\"RESPONSES\","
                            + "\"target\":{\"topic\":{\"topicName\":\"projects/p/topics/t\"}}},"
                            + "\"watchId\":\"watch-001\"}");
        }

        @Test
        void msGraphSendMailFullShape() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "message.subject", "Hello");
            BodyPathExecutor.apply(body, "message.body.content", "World");
            BodyPathExecutor.apply(body, "message.body.contentType", "Text");
            BodyPathExecutor.apply(body, "message.toRecipients[].emailAddress.address",
                    List.of("a@b.c"));
            BodyPathExecutor.apply(body, "message.importance", "high");
            BodyPathExecutor.apply(body, "saveToSentItems", true);
            assertThat(json(body)).isEqualTo(
                    "{\"message\":{"
                            + "\"subject\":\"Hello\","
                            + "\"body\":{\"content\":\"World\",\"contentType\":\"Text\"},"
                            + "\"toRecipients\":[{\"emailAddress\":{\"address\":\"a@b.c\"}}],"
                            + "\"importance\":\"high\""
                            + "},\"saveToSentItems\":true}");
        }

        @Test
        void msGraphCreateDraftFlatShape() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "subject", "Draft");
            BodyPathExecutor.apply(body, "body.content", "Body");
            BodyPathExecutor.apply(body, "body.contentType", "HTML");
            BodyPathExecutor.apply(body, "toRecipients[].emailAddress.address",
                    List.of("x@y.z"));
            assertThat(json(body)).isEqualTo(
                    "{\"subject\":\"Draft\","
                            + "\"body\":{\"content\":\"Body\",\"contentType\":\"HTML\"},"
                            + "\"toRecipients\":[{\"emailAddress\":{\"address\":\"x@y.z\"}}]}");
        }

        @Test
        void sheetsAddSheetFullShape() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.title", "Q1 Data");
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.gridProperties.rowCount", 100);
            BodyPathExecutor.apply(body, "requests[0].addSheet.properties.gridProperties.columnCount", 20);
            assertThat(json(body)).isEqualTo(
                    "{\"requests\":[{\"addSheet\":{\"properties\":{"
                            + "\"title\":\"Q1 Data\","
                            + "\"gridProperties\":{\"rowCount\":100,\"columnCount\":20}"
                            + "}}}]}");
        }
    }
}
