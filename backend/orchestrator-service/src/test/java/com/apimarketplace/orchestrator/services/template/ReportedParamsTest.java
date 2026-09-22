package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate every node's parameters pass through before they are persisted.
 *
 * <p>Two rules, and the cost of getting either wrong is asymmetric: mask too little and a
 * credential is published to everyone who can read the run, mask too much and the Params
 * column stops answering the question it exists for. Both directions are asserted here.
 */
@DisplayName("ReportedParams")
class ReportedParamsTest {

    private static final String SECRET = "s3cr3t";

    @Nested
    @DisplayName("what counts as a credential")
    class CredentialKeys {

        @Test
        @DisplayName("a key whose NAME says it holds one is masked, however the author spelled it")
        void masksCredentialNames() {
            // One key, three spellings: the plan, the form and an agent all write it
            // differently, and a leak through the third would be just as public.
            assertThat(ReportedParams.isCredentialKey("jwtSecretKey")).isTrue();
            assertThat(ReportedParams.isCredentialKey("jwt_secret_key")).isTrue();
            assertThat(ReportedParams.isCredentialKey("JWT-SECRET-KEY")).isTrue();
            assertThat(ReportedParams.isCredentialKey("basicPassword")).isTrue();
            assertThat(ReportedParams.isCredentialKey("authHeaderValue")).isTrue();
            assertThat(ReportedParams.isCredentialKey("api_key")).isTrue();
            assertThat(ReportedParams.isCredentialKey("credentials")).isTrue();
            assertThat(ReportedParams.isCredentialKey("privateKey")).isTrue();
            assertThat(ReportedParams.isCredentialKey("session")).isTrue();
            assertThat(ReportedParams.isCredentialKey("token")).isTrue();
        }

        @Test
        @DisplayName("a QUALIFIED token or key is masked, one vendor spelling at a time")
        void masksQualifiedTokensAndKeys() {
            // The branch the word rule added, and the only one with no positive coverage
            // before: delete the qualifier logic and every assertion above still passed,
            // because each of those keys matched on a whole-key or absolute-word rule.
            // `token` is deny-by-default because the credential spellings are open-ended -
            // one per vendor - while the benign ones are a short closed list.
            for (String key : List.of("accessToken", "refreshToken", "sessionToken",
                    "botToken", "slackBotToken", "userToken", "adminToken", "securityToken",
                    "X-Amz-Security-Token", "webhookToken", "cdpToken", "Circle-Token",
                    "PRIVATE-TOKEN", "tokenValue", "apiKey2", "password1")) {
                assertThat(ReportedParams.isCredentialKey(key))
                    .as("%s holds a credential", key)
                    .isTrue();
            }
            // `key` keeps an allow-list, because ITS benign uses are the open-ended side.
            for (String key : List.of("x-api-key", "apiKeyValue", "privateKeyPem",
                    "subscriptionKey", "Ocp-Apim-Subscription-Key", "licenseKey",
                    "masterKey", "accountKey", "deployKey", "hmacKey", "secretAccessKey")) {
                assertThat(ReportedParams.isCredentialKey(key))
                    .as("%s holds a credential", key)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a key that names a STRUCTURE stays readable: masking those would empty the panel")
        void doesNotMaskStructuralKeys() {
            for (String key : List.of("sortKey", "partitionKey", "objectKey", "primaryKey",
                    "cacheKey", "idempotencyKey", "accessKeyId", "clientId")) {
                assertThat(ReportedParams.isCredentialKey(key))
                    .as("%s is structure, not a credential", key)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("an absolute credential word wins over the descriptive suffix")
        void absoluteWordsBeatTheExemption() {
            // `passwordSource` is a password whatever the suffix says. The exemption is
            // there to keep `credentialId` and `apiKeyName` readable, not to unmask a word
            // that never means anything else.
            assertThat(ReportedParams.isCredentialKey("passwordSource")).isTrue();
            assertThat(ReportedParams.isCredentialKey("secretSource")).isTrue();
            assertThat(ReportedParams.isCredentialKey("credentialId")).isFalse();
        }

        @Test
        @DisplayName("a COUNT, a CURSOR or a DURATION that happens to contain a credential word is not masked")
        void doesNotMaskWordsThatOnlyLookLikeCredentials() {
            // The other half of the cost, and the one that shipped: matching `token` as a
            // substring masked `maxTokens` on EVERY agent node - a value the inspector
            // labels "Max Tokens" and a workflow reads as {{core:<agent>.input.maxTokens}}.
            // These are counts, cursors and durations, and each is more common in an API's
            // vocabulary than the credential the word came from.
            assertThat(ReportedParams.isCredentialKey("maxTokens")).isFalse();
            assertThat(ReportedParams.isCredentialKey("max_tokens")).isFalse();
            assertThat(ReportedParams.isCredentialKey("promptTokens")).isFalse();
            assertThat(ReportedParams.isCredentialKey("completionTokens")).isFalse();
            assertThat(ReportedParams.isCredentialKey("cachedTokens")).isFalse();
            assertThat(ReportedParams.isCredentialKey("tokensUsed")).isFalse();
            assertThat(ReportedParams.isCredentialKey("pageToken")).isFalse();
            assertThat(ReportedParams.isCredentialKey("nextPageToken")).isFalse();
            assertThat(ReportedParams.isCredentialKey("sessionTimeout")).isFalse();
            // `key` alone is the name of a map entry, which half the plan vocabulary uses.
            assertThat(ReportedParams.isCredentialKey("key")).isFalse();
        }

        @Test
        @DisplayName("a body that only LOOKS like JSON falls through to the plain bound rather than throwing")
        void reportsAMalformedJsonBodyAsText() {
            // `reportableBody` recognises a body by its first character and parses it. A
            // template that resolved to nothing, a truncated payload, a JSON-ish log line:
            // all start with `{` or `[` and do not parse. The fall-through has to be a bound,
            // not an exception, because this runs on the SUCCESS path of a request that has
            // already been sent.
            assertThat(ReportedParams.value("{not json at all"))
                .isEqualTo("{not json at all");
            assertThat(ReportedParams.value("[1, 2, oops"))
                .isEqualTo("[1, 2, oops");
        }

        @Test
        @DisplayName("`connectionString` is a credential: the DSN carries its password inline")
        void masksAConnectionString() {
            // On the product's OWN list of fields to encrypt at rest
            // (CredentialEncryptionService.SENSITIVE_FIELDS) and invisible to both word
            // rules: `connection` and `string` each mean something innocent, so only the
            // whole key says what it is.
            assertThat(ReportedParams.isCredentialKey("connectionString")).isTrue();
            assertThat(ReportedParams.isCredentialKey("connection_string")).isTrue();
            // And the counterpart, so the rule does not quietly become "anything with
            // `connection` in it": these name a connection, they do not hold one.
            assertThat(ReportedParams.isCredentialKey("connectionTimeout")).isFalse();
            assertThat(ReportedParams.isCredentialKey("connectionName")).isFalse();
        }

        @Test
        @DisplayName("a session id IS a credential, unlike every other id, which is a reference")
        void masksASessionId() {
            assertThat(ReportedParams.isCredentialKey("sessionId")).isTrue();
            assertThat(ReportedParams.isCredentialKey("session_id")).isTrue();
            // QUALIFIED too. The id IS the session, which is why `sessionId` needed a
            // whole-key entry at all - and a qualified one used to reach the `id` suffix rule
            // and come back unmasked. BrowserAgentNode saves exactly this key.
            assertThat(ReportedParams.isCredentialKey("browserSessionId")).isTrue();
            assertThat(ReportedParams.isCredentialKey("user_session_id")).isTrue();
            assertThat(ReportedParams.isCredentialKey("sessionIds")).isTrue();
            // The rest of the ids name WHICH credential was used, which is the one thing
            // that keeps a masked row diagnosable.
            assertThat(ReportedParams.isCredentialKey("credentialId")).isFalse();
            assertThat(ReportedParams.isCredentialKey("apiKeyId")).isFalse();
            assertThat(ReportedParams.isCredentialKey("sessionTimeout")).isFalse();
        }

        @Test
        @DisplayName("a key that NAMES one is not masked, and naming it is what keeps the row diagnosable")
        void keepsTheNamesThatIdentifyAMaskedValue() {
            // A 401 is diagnosed with these: which scheme ran, which parameter carried the
            // key, which account was used. Masking them would leave a row saying nothing.
            assertThat(ReportedParams.isCredentialKey("apiKeyName")).isFalse();
            assertThat(ReportedParams.isCredentialKey("authType")).isFalse();
            assertThat(ReportedParams.isCredentialKey("apiKeyLocation")).isFalse();
            assertThat(ReportedParams.isCredentialKey("jwtAlgorithm")).isFalse();
            assertThat(ReportedParams.isCredentialKey("credentialId")).isFalse();
            assertThat(ReportedParams.isCredentialKey("basicUsername")).isFalse();
            assertThat(ReportedParams.isCredentialKey("tokenType")).isFalse();
            assertThat(ReportedParams.isCredentialKey("url")).isFalse();
            assertThat(ReportedParams.isCredentialKey("prompt")).isFalse();
            assertThat(ReportedParams.isCredentialKey(null)).isFalse();
        }

        @Test
        @DisplayName("masks at every depth: a credential rarely sits at the top of the map")
        void masksNestedCredentials() {
            // The shape that actually leaks: a block named after the provider, with the key
            // inside it. An agent's `llm`, an http node's `authConfig`.
            Map<String, Object> params = Map.of(
                "llm", Map.of("provider", "anthropic", "api_key", SECRET),
                "model", "claude-opus-5");

            Map<String, Object> reported = ReportedParams.forReport(params);

            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) reported.get("llm");
            assertThat(llm).containsEntry("api_key", ReportedParams.WITHHELD_CREDENTIAL);
            assertThat(llm).containsEntry("provider", "anthropic");
            assertThat(reported).containsEntry("model", "claude-opus-5");
        }

        @Test
        @DisplayName("masks inside a list of blocks, which is how headers and rows arrive")
        void masksInsideCollections() {
            Map<String, Object> params = Map.of("rows", List.of(
                Map.of("name", "alice", "password", SECRET)));

            String flattened = ReportedParams.forReport(params).toString();

            assertThat(flattened).doesNotContain(SECRET);
            assertThat(flattened).contains("alice");
        }

        @Test
        @DisplayName("the input map is not mutated: the node still runs on its own values")
        void doesNotMutateTheInput() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("password", SECRET);

            ReportedParams.forReport(params);

            assertThat(params).containsEntry("password", SECRET);
        }
    }

    @Nested
    @DisplayName("what counts as too big")
    class Bounding {

        @Test
        @DisplayName("a small value is reported EXACTLY as it is: showing the value is the point")
        void keepsSmallValuesVerbatim() {
            List<Object> rows = List.of(Map.of("id", 1), Map.of("id", 2));

            assertThat(ReportedParams.value("hello")).isEqualTo("hello");
            assertThat(ReportedParams.value(42)).isEqualTo(42);
            assertThat(ReportedParams.value(true)).isEqualTo(true);
            assertThat(ReportedParams.value(null)).isNull();
            assertThat(ReportedParams.value(rows)).isEqualTo(rows);
        }

        @Test
        @DisplayName("a value with no ceiling is described by its shape, not copied onto every step row")
        void describesOversizedValues() {
            List<Object> manyRows = new ArrayList<>();
            for (int i = 0; i < 5_000; i++) {
                manyRows.add(Map.of("id", i, "payload", "x".repeat(200)));
            }

            Object reported = ReportedParams.value(manyRows);

            assertThat(reported).isInstanceOf(String.class);
            assertThat((String) reported).isEqualTo("List(size=5000)");
        }

        @Test
        @DisplayName("a long text is described too: a request body and a document are both strings")
        void describesOversizedText() {
            Object reported = ReportedParams.value("y".repeat(50_000));

            assertThat(reported).isInstanceOf(String.class);
            assertThat((String) reported).hasSizeLessThan(200).contains("50000 chars");
        }

        @Test
        @DisplayName("an OBJECT is bounded too: a request body and a trigger payload both arrive as one")
        void describesAnOversizedMap() {
            // The one shape that escaped: collections were capped by size and strings by
            // length, and a Map of 5 000 fields walked straight through onto the step row
            // of every item. A JSON request body and the trigger-payload fallback are both
            // exactly this shape.
            Map<String, Object> wide = new LinkedHashMap<>();
            for (int i = 0; i < 5_000; i++) {
                wide.put("field" + i, "x".repeat(100));
            }

            Object reported = ReportedParams.forReport(Map.of("body", wide)).get("body");

            assertThat(reported).isInstanceOf(String.class);
            assertThat((String) reported).startsWith("Map(keys=[");
        }

        @Test
        @DisplayName("the WHOLE reported map is bounded, which no per-value rule can do")
        void boundsTheWholeMap() {
            // The shape nodes actually pass: their own parameter map, at the TOP level.
            // Every value here is individually small - 500 characters, well under the
            // per-value budget - and two thousand of them were a megabyte on a row written
            // per item of every split. The nested shape has its own case below: the comment
            // that used to sit here called it "the path that already worked", and it was the
            // one path where the bound failed outright.
            Map<String, Object> many = new LinkedHashMap<>();
            for (int i = 0; i < 2_000; i++) {
                many.put("field" + i, "x".repeat(500));
            }

            Map<String, Object> reported = ReportedParams.forReport(many);

            assertThat(reported.toString().length())
                .as("a reported map must not carry a payload")
                .isLessThan(ReportedParams.MAX_TOTAL_WEIGHT + 1_000);
            assertThat(reported.get(ReportedParams.TRUNCATED))
                .as("and a cut must be stated, never silent")
                .asString().contains("not shown");
            // The entries that fit are kept AS THEY ARE: the first thing a reader looks at
            // is still the value, not a description of it.
            assertThat(reported.get("field0")).isEqualTo("x".repeat(500));
        }

        @Test
        @DisplayName("WHICH entries survive is the map's own order, so a reader sees the first keys the node wrote")
        void keepsTheEntriesTheNodeWroteFirst() {
            // The cut is not a ranking: forReport walks the map it is given. A node builds
            // its report in a LinkedHashMap, so the entries that survive are the ones it
            // wrote first, and they are CONTIGUOUS from the start - there is no entry from
            // the tail that sneaked in because it happened to be small.
            Map<String, Object> many = new LinkedHashMap<>();
            for (int i = 0; i < 2_000; i++) {
                many.put("field" + i, "x".repeat(500));
            }

            Map<String, Object> reported = ReportedParams.forReport(many);

            List<String> kept = new ArrayList<>(reported.keySet());
            kept.remove(ReportedParams.TRUNCATED);
            assertThat(kept).isNotEmpty();
            for (int i = 0; i < kept.size(); i++) {
                assertThat(kept.get(i))
                    .as("entry %d of the report must be entry %d of the map", i, i)
                    .isEqualTo("field" + i);
            }
            assertThat(reported.get(ReportedParams.TRUNCATED))
                .asString()
                .contains(String.valueOf(2_000 - kept.size()));
        }

        @Test
        @DisplayName("the map budget counts what a NESTED container holds, which is the shape it used to miss entirely")
        void boundsAMapOfNestedContainers() {
            // The shape the test above deliberately did not cover, and the only one where
            // the bound actually failed. `weigh` charged a nested container a flat
            // `16 + size * 32` whatever it held, so two hundred entries of
            // `{data:{attributes:{html: <1 900 chars>}}}` - an ordinary wrapper shape from
            // any JSON API - each measured about eighty, the map budget summed sixteen
            // thousand, passed, and 383 KB went onto the step row of every item of every
            // split with no marker to tell the reader.
            Map<String, Object> many = new LinkedHashMap<>();
            for (int i = 0; i < 200; i++) {
                many.put("record" + i, Map.of("data", Map.of("attributes",
                    Map.of("html", "x".repeat(1_900)))));
            }

            Map<String, Object> reported = ReportedParams.forReport(many);

            assertThat(reported.toString().length())
                .as("a nested payload is a payload")
                .isLessThan(ReportedParams.MAX_TOTAL_WEIGHT + 4_000);
            assertThat(reported.get(ReportedParams.TRUNCATED))
                .as("and the cut must be stated, never silent")
                .asString().contains("not shown");
        }

        @Test
        @DisplayName("MAX_ITEMS is a boundary, not a suggestion: 200 items are walked, 201 are described")
        void describesACollectionPastTheItemCap() {
            // The constant exists because masking a collection means walking it, and a walk
            // is only free while the collection is short. Both sides asserted, because a cap
            // that fires one item early silently turns a readable row into a description.
            // Deliberately ONE-character items: anything larger and the per-VALUE size bound
            // fires first, which would make this a test of the other rule.
            List<Object> atTheCap = new ArrayList<>();
            List<Object> pastTheCap = new ArrayList<>();
            for (int i = 0; i < 201; i++) {
                if (i < 200) {
                    atTheCap.add("x");
                }
                pastTheCap.add("x");
            }

            Object kept = ReportedParams.reportValue(atTheCap);
            Object described = ReportedParams.reportValue(pastTheCap);

            assertThat(kept).isInstanceOf(List.class);
            assertThat((List<?>) kept).hasSize(200);
            assertThat(described)
                .as("past the cap it is described, and the description states the real size")
                .asString().contains("201");
        }

        @Test
        @DisplayName("past MAX_DEPTH the gate stops walking and describes, rather than pretending it masked")
        void describesPastTheDepthCap() {
            // A structure deeper than any node's configuration cannot be walked for
            // credentials any more, and saying so is the honest answer: the alternative is a
            // row that looks masked and is not. Asserted with a credential at the bottom, so
            // a regression that walked shallower than it claims would publish it.
            Map<String, Object> deepest = new LinkedHashMap<>();
            deepest.put("apiKey", SECRET);
            Map<String, Object> level = deepest;
            for (int i = 0; i < 8; i++) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("level" + i, level);
                level = wrapper;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("config", level);

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.toString())
                .as("a depth the gate stops walking at must not carry the credential either")
                .doesNotContain(SECRET);
        }

        @Test
        @DisplayName("a null or empty map comes back as an empty map, never as null")
        void handlesANullMap() {
            // Callers put the result straight into an output map. Returning null would put a
            // null under `resolved_params`, which the persistence layer reads as "this node
            // reported nothing" - the state the whole alignment exists to remove.
            assertThat(ReportedParams.forReport(null)).isNotNull().isEmpty();
            assertThat(ReportedParams.forReport(new LinkedHashMap<>())).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a value whose toString is large is weighed by what it would write, not by a flat estimate")
        void weighsAnUnknownTypeByWhatItWrites() {
            // `weigh` used to return a flat 32 for anything that was not text, a collection,
            // a map or an array. A type whose toString is megabytes - a Jackson TextNode is
            // the real example - therefore passed both budgets and was copied whole.
            Object heavy = new Object() {
                @Override
                public String toString() {
                    return "z".repeat(50_000);
                }
            };
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("payload", heavy);

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.toString().length())
                .as("a heavy value of an unfamiliar type is a payload like any other")
                .isLessThan(2_000);
        }

        @Test
        @DisplayName("an ARRAY is walked like a list: masked inside and bounded, not copied whole")
        void boundsAndMasksArrays() {
            // Arrays reach the gate from tool arguments and from a code node's $output.
            // They are not Collections, so before this they walked past every rule: a
            // credential inside one was published and a large one was copied in full.
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("apiKey", SECRET);
            inner.put("endpoint", "https://api.example.com");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("targets", new Object[] { inner });
            params.put("blob", new String[] { "y".repeat(5_000) });

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.toString())
                .as("no credential may survive being inside an array")
                .doesNotContain(SECRET);
            assertThat(reported.toString())
                .as("and no array may carry an unbounded payload onto the row")
                .doesNotContain("y".repeat(200));
        }

        @Test
        @DisplayName("a parameter the author named `paramsTruncated` loses to the marker, and the loss is logged")
        void theTruncationMarkerWinsTheCollision() {
            // The author's own key and the gate's marker collide. A silent cut is the worse
            // outcome, so the marker wins - the same trade-off AggregateNode makes for its
            // `fields` collision, and like it, the cost is stated rather than hidden.
            Map<String, Object> many = new LinkedHashMap<>();
            many.put(ReportedParams.TRUNCATED, "the author's own value");
            for (int i = 0; i < 2_000; i++) {
                many.put("field" + i, "x".repeat(500));
            }

            Map<String, Object> reported = ReportedParams.forReport(many);

            assertThat(reported.get(ReportedParams.TRUNCATED))
                .as("the marker, not the author's value")
                .asString()
                .contains("not shown")
                .doesNotContain("the author's own value");
        }

        @Test
        @DisplayName("a null parameter is reported as null, not as a crash inside the gate")
        @SuppressWarnings("unchecked")
        void reportsANullValueRatherThanThrowing() {
            // A reporting gate must never fail the node it reports on. This one did, for a
            // whole release of this class: the array walk reads the value's TYPE, so every
            // null parameter - `data_input` reports one for an item whose file resolved to
            // nothing, which is exactly what the panel is read for - hit
            // `value.getClass()` and turned a completed node into a NullPointerException.
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("file", null);
            params.put("rows", java.util.Arrays.asList("a", null));
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("inner", null);
            params.put("config", nested);

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported).containsEntry("file", null);
            assertThat(reported.get("rows")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).containsExactly("a", null);
            assertThat((Map<String, Object>) reported.get("config")).containsEntry("inner", null);
        }

        @Test
        @DisplayName("an ordinary configuration is untouched by the total budget")
        void leavesAnOrdinaryMapAlone() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("url", "https://api.example.com/v1/items");
            params.put("method", "GET");
            params.put("timeout", 30);

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported).isEqualTo(params);
            assertThat(reported).doesNotContainKey(ReportedParams.TRUNCATED);
        }

        @Test
        @DisplayName("the weigher stops at the budget rather than walking a whole dataset to measure it")
        void doesNotWalkTheWholeStructureToMeasureIt() {
            // A measurement that costs as much as the copy it prevents is no saving.
            // Counted rather than timed: a wall-clock assertion on a loaded CI box measures
            // the box, and would fail for a reason that has nothing to do with this rule.
            java.util.concurrent.atomic.AtomicInteger examined = new java.util.concurrent.atomic.AtomicInteger();
            List<Object> huge = new java.util.AbstractList<>() {
                @Override
                public Object get(int index) {
                    examined.incrementAndGet();
                    return "row-" + index;
                }

                @Override
                public int size() {
                    return 1_000_000;
                }
            };

            ReportedParams.value(huge);

            assertThat(examined.get())
                .as("the estimator must stop once the budget is exceeded, not read a million rows")
                .isLessThan(1_000);
        }
    }

    @Nested
    @DisplayName("a url, the one string that carries a credential inside it")
    class UrlMasking {

        @Test
        @DisplayName("masks the credential-named query values and keeps everything else readable")
        void masksQueryCredentials() {
            String masked = ReportedParams.maskUrlSecrets(
                "https://files.example.com/report.pdf?token=" + SECRET + "&page=2&api_key=" + SECRET);

            assertThat(masked).doesNotContain(SECRET);
            assertThat(masked).contains("files.example.com/report.pdf");
            assertThat(masked).contains("page=2");
            // The parameter NAMES stay: they say what was masked.
            assertThat(masked).contains("token=").contains("api_key=");
        }

        @Test
        @DisplayName("the bare query names are the credential ones: ?key=, ?sig=, ?auth=, ?token=")
        void masksTheBareQueryNames() {
            // Inside a query string `key` IS the credential - every Google API - while in a
            // configuration map the same word is a map entry's name. One predicate cannot
            // serve both, so the url has its own.
            for (String name : List.of("key", "sig", "auth", "token", "apikey")) {
                String masked = ReportedParams.maskUrlSecrets(
                    "https://api.example.com/v1?" + name + "=" + SECRET + "&page=2");
                assertThat(masked).as("?%s= carries a credential", name).doesNotContain(SECRET);
                assertThat(masked).as("and the rest of the url stays readable").contains("page=2");
            }
            // The counter-check: the same words in a configuration map are not masked.
            assertThat(ReportedParams.isCredentialKey("key")).isFalse();
        }

        @Test
        @DisplayName("a url with nothing to mask comes back untouched")
        void leavesACleanUrlAlone() {
            String url = "https://api.example.com/v1/items?page=2&sort=asc";

            assertThat(ReportedParams.maskUrlSecrets(url)).isEqualTo(url);
            assertThat(ReportedParams.maskUrlSecrets("https://api.example.com/v1/items"))
                .isEqualTo("https://api.example.com/v1/items");
            assertThat(ReportedParams.maskUrlSecrets(null)).isNull();
        }

        @Test
        @DisplayName("a fragment survives the masking, so the url still resolves as written")
        void keepsTheFragment() {
            String masked = ReportedParams.maskUrlSecrets(
                "https://example.com/a?token=" + SECRET + "#section-2");

            assertThat(masked).doesNotContain(SECRET);
            assertThat(masked).endsWith("#section-2");
        }
    }

    @Nested
    @DisplayName("a value an author's expression produced")
    class WorkspaceVariables {

        @Test
        @DisplayName("a scalar pulled from a workspace variable is withheld: one can be declared secret")
        void withholdsAWorkspaceScalar() {
            assertThat(ReportedParams.valueFrom("{{$vars.api_token}}", SECRET))
                .isEqualTo(ReportedParams.WITHHELD_WORKSPACE_VARIABLE);
            assertThat(ReportedParams.valueFrom("{{vars:api_token}}", SECRET))
                .isEqualTo(ReportedParams.WITHHELD_WORKSPACE_VARIABLE);
        }

        @Test
        @DisplayName("a structured workspace variable reports its SHAPE and not its contents")
        void reportsTheShapeOfAWorkspaceCollection() {
            // A workspace variable holding an OBJECT of credentials is as secret as one
            // holding a string. Returning the object as it is - which this did, while the
            // javadoc claimed otherwise - published every value in it.
            assertThat(ReportedParams.valueFrom("{{$vars.rows}}", List.of(1, 2, 3)))
                .isEqualTo("List(size=3)");
            assertThat(ReportedParams.valueFrom("{{$vars.creds}}", Map.of("password", "p")))
                .isEqualTo("Map(keys=[password])");
        }

        @Test
        @DisplayName("the run's own data is reported normally, or the rule becomes 'never show anything'")
        void reportsWorkflowDataNormally() {
            assertThat(ReportedParams.valueFrom("{{core:prepare.output.title}}", "Quarterly report"))
                .isEqualTo("Quarterly report");
            assertThat(ReportedParams.valueFrom(null, "plain")).isEqualTo("plain");
        }

        @Test
        @DisplayName("a mention inside a string literal is data, not a reference - asked of the engine's own normalizer")
        void doesNotWithholdALiteralMention() {
            // The filter that keeps this from accusing a healthy expression. Modelling the
            // two author forms here instead of asking VarsSyntaxNormalizer is how a literal
            // gets mistaken for a reference.
            assertThat(ReportedParams.referencesWorkspaceVariable("'$vars.x' == thing")).isFalse();
            assertThat(ReportedParams.referencesWorkspaceVariable("{{$vars.x}}")).isTrue();
            assertThat(ReportedParams.referencesWorkspaceVariable("no reference here")).isFalse();
            assertThat(ReportedParams.referencesWorkspaceVariable(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("a value its producer has already gated")
    class PreGatedValues {

        @Test
        @DisplayName("an author's variable named `token` keeps its wiring: the words rule does not read author labels")
        void doesNotMaskAnAuthorsOwnVariableNames() {
            // A PreGated holds names the AUTHOR chose for their screen variables, and its
            // producer has already gated every value under them. Masking by name on top of
            // that replaced the whole record - expression, resolved shape, status - with one
            // string, which is the failure this gate documents in the other direction: a row
            // that cannot say what it is about.
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("expression", "{{core:auth.output.session_label}}");
            variable.put("resolved", "text(12)");
            variable.put("status", "resolved");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("variableMapping", new ReportedParams.PreGated(Map.of("token", variable)));
            params.put("apiKey", SECRET);

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.get("apiKey"))
                .as("the exemption travels with the wrapper, and this value does not carry one")
                .isEqualTo(ReportedParams.WITHHELD_CREDENTIAL);
            assertThat(reported.toString())
                .as("the author's variable keeps the expression that diagnoses their empty screen")
                .contains("{{core:auth.output.session_label}}");
            assertThat(reported.get("variableMapping"))
                .as("and the wrapper is unwrapped, so what lands on the row is the plain map")
                .isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("a parameter merely NAMED like the pre-gated one claims no exemption")
        void aBorrowedNameClaimsNoExemption() {
            // The exemption is a wrapper TYPE, not a key name, precisely so this holds: a
            // tool argument an author happens to call `variableMapping` is a parameter like
            // any other. Keyed on the name, it would have been an exemption any caller could
            // claim for itself - which is not an exemption, it is a hole.
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("variableMapping", Map.of("apiKey", SECRET, "endpoint", "https://api.example.com"));

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.toString())
                .as("a credential under a borrowed name is still a credential")
                .doesNotContain(SECRET);
            assertThat(reported.toString()).contains("https://api.example.com");
        }

        @Test
        @DisplayName("a pre-gated value is bounded as a whole, so four hundred small entries are not eighty kilobytes")
        void boundsThePreGatedValueItself() {
            // The bound a per-entry rule cannot give: four hundred variables of two hundred
            // characters are individually small and were eighty kilobytes on the row.
            Map<String, Object> mapping = new LinkedHashMap<>();
            for (int i = 0; i < 400; i++) {
                mapping.put("field" + i, Map.of(
                    "expression", "{{core:prepare.output.field" + i + "}}",
                    "resolved", "y".repeat(100),
                    "status", "resolved"));
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("variableMapping", new ReportedParams.PreGated(mapping));

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.toString().length())
                .as("a pre-gated value must not carry a payload either")
                .isLessThan(ReportedParams.MAX_TOTAL_WEIGHT);
            Map<?, ?> boundedMapping = (Map<?, ?>) reported.get("variableMapping");
            assertThat(boundedMapping.get(ReportedParams.TRUNCATED))
                .as("and the cut is stated under the same word, one level down")
                .asString().contains("not shown");
            assertThat(boundedMapping.get("field0"))
                .as("what fits keeps its wiring: a bounded mapping is not an absent one")
                .isNotNull();
        }

        @Test
        @DisplayName("a pre-gated value that is not a map is bounded like any other value")
        void boundsANonMapPreGatedValue() {
            // Today's one producer always wraps a map. The branch exists so that a producer
            // that wraps something else gets a bound rather than a ClassCastException or an
            // unbounded copy - a reporting gate must not be able to fail the node it reports
            // on, and must not become a way past the budget either.
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("variableMapping", new ReportedParams.PreGated(null));
            params.put("other", "kept");

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported).containsEntry("variableMapping", null);
            assertThat(reported).containsEntry("other", "kept");
        }

        @Test
        @DisplayName("the one leaf that can hold UPSTREAM data is masked when the author's own name says credential")
        void withholdsTheResolvedLeafUnderACredentialName() {
            // The exemption stops the words rules reading author labels, which is right for
            // the record as a whole. But `resolved` is not configuration: the producer
            // DESCRIBES it, and a description returns a short string verbatim up to the
            // scalar cap. An interface variable an author called `token`, wired to
            // {{mcp:auth.output.access_token}}, therefore put the first 120 characters of
            // that token on the row of every epoch - and a ghp_ PAT fits in 40.
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("expression", "{{mcp:auth.output.access_token}}");
            variable.put("resolved", "ghp_" + "A".repeat(36));
            variable.put("status", "resolved");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("variableMapping", new ReportedParams.PreGated(Map.of("token", variable)));

            Map<String, Object> reported = ReportedParams.forReport(params);

            Map<?, ?> mapping = (Map<?, ?>) reported.get("variableMapping");
            Map<?, ?> token = (Map<?, ?>) mapping.get("token");
            assertThat(String.valueOf(token.get("resolved")))
                .as("the value is withheld")
                .isEqualTo(ReportedParams.WITHHELD_CREDENTIAL);
            // And everything the panel is actually opened for survives, which is why this is
            // not the whole-entry mask the exemption exists to prevent.
            assertThat(token.get("expression")).isEqualTo("{{mcp:auth.output.access_token}}");
            assertThat(token.get("status")).isEqualTo("resolved");
        }

        @Test
        @DisplayName("a variable whose name says nothing keeps its resolved value: that is the whole point of the panel")
        void keepsTheResolvedLeafUnderAnOrdinaryName() {
            // The other direction, and the one that costs more when it is wrong. A mapping
            // onto the run's own data is what a reader opens this panel to see.
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("expression", "{{core:prepare.output.title}}");
            variable.put("resolved", "\"Quarterly report\"");
            variable.put("status", "resolved");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("variableMapping", new ReportedParams.PreGated(Map.of("title", variable)));

            Map<String, Object> reported = ReportedParams.forReport(params);

            assertThat(reported.toString()).contains("Quarterly report");
        }
    }
}
