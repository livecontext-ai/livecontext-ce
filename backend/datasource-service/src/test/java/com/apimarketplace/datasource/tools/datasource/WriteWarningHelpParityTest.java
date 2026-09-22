package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.datasource.config.DataSourceAgentDefaultsConfig;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool help describes what the coercion can say. Nothing kept the two in step, and every
 * mistake this test now catches was really made while writing that help:
 *
 * <ul>
 *   <li>messages the coercion produces that the help never mentioned, so an agent reading the help
 *       had no way to classify them ('Invalid URL', 'Invalid epoch value');</li>
 *   <li>messages filed under the WRONG outcome. The vector ones were listed as warnings on a
 *       successful write, when a bad vector actually throws and no row is created, so the help sent
 *       the reader hunting for a row to repair that does not exist.</li>
 * </ul>
 *
 * <p>It reads {@link com.apimarketplace.datasource.crud.service.ColumnValueCoercer}'s SOURCE rather
 * than a hand-copied list, because a hand-copied list is the thing that drifted. Adding a message
 * to the coercer fails this test until someone decides which bucket it belongs in, the same shape
 * as ParamAliasCreatorParityTest in orchestrator-service.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("table(action='help') describes the warnings the coercion really produces")
class WriteWarningHelpParityTest {

    private static final Path COERCER = Path.of(
            "src/main/java/com/apimarketplace/datasource/crud/service/ColumnValueCoercer.java");

    /**
     * The three ways the coercer produces a warning sentence.
     *
     * <p>The first pattern alone was not enough, and the gap was not theoretical: an earlier
     * version of this test matched only {@code CoercionResult.failed(...)} /
     * {@code withWarning(...)}, so everything routed through {@code clampWithWarning} was invisible
     * to it. That hid a real, shipping message ("Interpreted ... as fraction", written when a
     * progress column scored out of more than ten is given 0.75) from every help surface, while
     * this test reported the help complete. Whenever a new way of building a warning appears, add
     * it here; {@link #noUndiscoveredWarningProducer()} is the tripwire that says when.
     */
    private static final List<Pattern> MESSAGE_PATTERNS = List.of(
            // CoercionResult.failed("...") / withWarning(value, "...")
            Pattern.compile(
                    "CoercionResult\\.(?:failed|withWarning)\\(\\s*(?:[A-Za-z0-9_.()\\[\\]]+\\s*,\\s*)?\"([^\"]+)\""),
            // warnings.add("...") inside clampWithWarning
            Pattern.compile("warnings\\.add\\(\\s*\"([^\"]+)\""),
            // the extraWarning argument of clampWithWarning(value, min, max, typeName, "...")
            Pattern.compile("clampWithWarning\\([^;]*?\"([^\"]+)\"\\s*\\+", Pattern.DOTALL));

    /**
     * Messages deliberately absent from the help in that exact form, each with the reason. A
     * message may only be listed here because the help names it in another form, never because
     * nobody got round to documenting it.
     */
    private static final Map<String, String> DELIBERATELY_UNLISTED = Map.ofEntries(
            Map.entry("Coercion error: ", "named by its short form, 'Coercion error'"),
            Map.entry("Value '", "named by its distinctive tail, 'does not match any defined option'"),
            Map.entry("Value does not look like a file URL: '", "named as 'does not look like a file URL'"),
            Map.entry("Value does not look like a valid email: '", "named as 'does not look like a valid email'"),
            Map.entry("File reference has no id and no URL - it cannot be displayed",
                    "named by its tail, 'it cannot be displayed'"),
            Map.entry("Cannot parse as ", "the generic fallback: the type name is interpolated, and "
                    + "the help names the concrete forms plus 'Cannot parse as <type>'"),
            Map.entry("Cannot parse vector from string: ", "vector: named in aBadVectorIsNOTAWarning"),
            Map.entry("Converted RFC date to ISO: '", "named without its trailing colon"),
            Map.entry("Converted compact date to ISO: '", "named without its trailing colon"),
            Map.entry("Converted date format to ISO: '", "named without its trailing colon"));

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceAgentDefaultsConfig agentDefaults;

    private String helpText;
    private String writeWarningsText;
    private Map<String, Object> writeWarnings;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        DataSourceTableModule module = new DataSourceTableModule(dataSourceService, new ObjectMapper(),
                agentDefaults, new VectorFeatureGate(new AppEditionProvider(env), null));

        Optional<ToolExecutionResult> result = module.execute("help", Map.of(), "t1", null);
        assertThat(result).as("the tool must answer action='help'").isPresent();
        Map<String, Object> help = (Map<String, Object>) result.get().data();
        Object block = help.get("writeWarnings");
        assertThat(block).as("help must carry a writeWarnings block").isInstanceOf(Map.class);
        writeWarnings = (Map<String, Object>) block;
        writeWarningsText = String.join(" ", writeWarnings.values().stream().map(String::valueOf).toList());
        helpText = String.valueOf(help);
    }

    private String bucketText(String bucket) {
        Object value = writeWarnings.get(bucket);
        assertThat(value).as("the help must still carry a '%s' bucket", bucket).isNotNull();
        return String.valueOf(value);
    }

    private static Set<String> coercerMessages() throws Exception {
        assertThat(Files.exists(COERCER))
                .as("run from the datasource-service module dir; %s must exist", COERCER)
                .isTrue();
        Set<String> messages = new LinkedHashSet<>();
        String source = Files.readString(COERCER);
        for (Pattern pattern : MESSAGE_PATTERNS) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                messages.add(matcher.group(1));
            }
        }
        assertThat(messages)
                .as("the patterns must still find the coercer's messages: a short set means the "
                        + "coercer was refactored and this test is passing on nothing")
                .hasSizeGreaterThan(20);
        return messages;
    }

    /**
     * The tripwire for the blind spot above. Every warning the coercer emits leaves through one of
     * two doors: a {@code CoercionResult} static factory, or the single {@code clampWithWarning}
     * helper that builds one directly. A third door would be invisible to
     * {@link #MESSAGE_PATTERNS}, and the parity check would go on reporting the help complete while
     * a message nobody documented reached callers - which is exactly what happened once already.
     *
     * <p>So this counts the direct constructions. If the number moves, someone added a producer:
     * teach {@link #MESSAGE_PATTERNS} about it before changing this number.
     */
    @Test
    @DisplayName("No warning producer exists that the message scan cannot see")
    void noUndiscoveredWarningProducer() throws Exception {
        String source = Files.readString(COERCER);
        int directConstructions = source.split("new CoercionResult\\(", -1).length - 1;
        assertThat(directConstructions)
                .as("only clampWithWarning may build a CoercionResult directly; a new one needs a "
                        + "matching pattern in MESSAGE_PATTERNS or its messages go undocumented")
                .isEqualTo(1);
        assertThat(source)
                .as("clampWithWarning is the known direct producer and must still collect into "
                        + "'warnings.add(', which is what the second pattern reads")
                .contains("private CoercionResult clampWithWarning(")
                .contains("warnings.add(");
    }

    @Test
    @DisplayName("Every message the coercion can produce is either named in the help or explicitly excused")
    void everyCoercerMessageIsAccountedFor() throws Exception {
        Set<String> unexplained = new TreeSet<>();
        for (String message : coercerMessages()) {
            if (DELIBERATELY_UNLISTED.containsKey(message)) {
                continue;
            }
            // The help quotes the stable head of a message: everything before the first place a
            // value is interpolated. A comma counts, because some messages append the offending
            // value as a clause ("Vector must be an array of numbers, got string: '...'").
            String head = message.split("[',:]")[0].trim();
            if (head.isEmpty() || writeWarningsText.contains(head)) {
                continue;
            }
            unexplained.add(message);
        }
        assertThat(unexplained)
                .as("these coercion messages are in neither the help nor the excuse list: decide "
                        + "which bucket each belongs in rather than adding it to the list")
                .isEmpty();
    }

    /**
     * The false statement this test exists for. A vector coercion failure is promoted to a thrown
     * failure by {@code CrudExecutorService.failOnDroppedVector}, so it can never be a warning on a
     * successful write, and the help must not file it as one: the reader would go looking for a row
     * that was never created.
     */
    @Test
    @DisplayName("No vector message is filed as a warning on a successful write")
    void vectorMessagesAreNotFiledAsWarnings() {
        for (String bucket : List.of("theCellIsNowEMPTY", "theCellIsStoredButUnusable",
                "mostAreNormalisations", "someAreAdvisory")) {
            assertThat(bucketText(bucket))
                    .as("%s must not present a vector failure as something a successful write reports", bucket)
                    .doesNotContain("Vector");
        }
        assertThat(bucketText("aBadVectorIsNOTAWarning"))
                .as("the vector outcome must be stated where the reader will look")
                .contains("Vector dimension mismatch")
                .contains("NO row")
                .contains("fail");
    }

    @Test
    @DisplayName("The help does not quote a message the coercion never writes")
    void helpInventsNoMessages() throws Exception {
        String coercerSource = Files.readString(COERCER);
        List<String> quoted = List.of(
                "Converted date format to ISO", "Converted RFC date to ISO",
                "Converted compact date to ISO", "Interpreted comma as decimal separator",
                "Stripped non-numeric characters from", "Clamped ", "does not match any defined option",
                "does not look like a valid email", "Phone number has fewer than 7 digits",
                "Invalid URL", "Invalid number", "Invalid epoch value",
                "Cannot convert boolean to date", "Cannot parse as number", "Cannot parse as date",
                "Cannot parse as progress", "File reference found in EMAIL column",
                "File reference found in PHONE column", "it cannot be displayed",
                "does not look like a file URL", "Coercion error",
                "Vector dimension mismatch", "Vector must not be empty",
                "Vector element is not a number", "Vector must be an array of numbers");
        for (String example : quoted) {
            assertThat(writeWarningsText)
                    .as("this test's own list must stay in step with the help")
                    .contains(example);
            assertThat(coercerSource)
                    .as("the help quotes '%s', which the coercion never writes", example)
                    .contains(example);
        }
    }

    @Test
    @DisplayName("The two write actions point at the block, so a reader finds it without asking")
    void theWriteActionsPointAtTheBlock() {
        assertThat(helpText).contains("help.writeWarnings");
    }
}
