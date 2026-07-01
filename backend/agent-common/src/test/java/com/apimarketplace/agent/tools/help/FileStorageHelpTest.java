package com.apimarketplace.agent.tools.help;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-drift contract test for {@link FileStorageHelp}. Both
 * {@code InterfaceHelpModule} and {@code CatalogHelpModule} surface the
 * same map; this test fixes the required keys so that a refactor that
 * accidentally drops one of them fails fast.
 */
@DisplayName("FileStorageHelp")
class FileStorageHelpTest {

    @Test
    @DisplayName("returns the canonical key set every consumer relies on")
    void exposesCanonicalKeys() {
        Map<String, Object> help = FileStorageHelp.get();

        assertThat(help)
            .containsKeys(
                "title", "concept",
                "shapes", "auto_persist",
                "display_in_interface", "send_to_a_tool", "auth",
                "wrong", "see_also"
            );
    }

    @Test
    @DisplayName("send_to_a_tool teaches mapping a FileRef into a tool file param to upload it")
    void teachesSendingFileToToolParam() {
        String send = (String) FileStorageHelp.get().get("send_to_a_tool");
        // The action the agent takes: map the FileRef object into the tool's file param.
        assertThat(send).contains("send_photo");
        assertThat(send).containsIgnoringCase("upload");
        // Must steer to the OBJECT, not a drilled string (a drilled .path/.id would
        // be treated as a remote URL / file id and skip the binary upload).
        assertThat(send).contains("OBJECT");
        assertThat(send).contains(".path");
        assertThat(send).contains(".id");
        // The wrong list must carry the symmetric anti-pattern for tool params.
        @SuppressWarnings("unchecked")
        List<String> wrong = (List<String>) FileStorageHelp.get().get("wrong");
        assertThat(wrong).anyMatch(s ->
            s.contains(".path") && s.contains(".id") && s.contains("send_photo"));
    }

    @Test
    @DisplayName("shapes block declares canonical FileRef + scoped form_sidecars (PR2 clean break)")
    void declaresShapes() {
        @SuppressWarnings("unchecked")
        Map<String, Object> shapes = (Map<String, Object>) FileStorageHelp.get().get("shapes");
        // PR2 2026-05-15: legacy `flat` key was renamed to `form_sidecars` and
        // its scope narrowed - the 4 producer nodes (download_file, sftp,
        // convert_to_file, compression) and chat trigger attachments no longer
        // emit flat fields. Only form-trigger file uploads still produce the
        // `_file_url`/_file_name`/_file_size`/_content_type` sidecars alongside
        // the canonical FileRef under the field name.
        assertThat(shapes).containsKeys("canonical", "form_sidecars");
        assertThat(shapes).doesNotContainKey("flat");

        String canonical = shapes.get("canonical").toString();
        assertThat(canonical).contains("_type:'file'");
        assertThat(canonical).contains("download_file");
        assertThat(canonical).contains("sftp");
        assertThat(canonical).contains("convert_to_file");
        assertThat(canonical).contains("compression");
        assertThat(canonical).contains("form file uploads");
        assertThat(canonical).contains("chat trigger attachments");

        String sidecars = shapes.get("form_sidecars").toString();
        assertThat(sidecars).contains("_file_url");
        assertThat(sidecars).contains("_file_name");
        assertThat(sidecars).contains("_file_size");
        assertThat(sidecars).contains("_content_type");
        // Scope guard: form_sidecars must NOT teach the agent that the 4 core
        // producer nodes still emit flat fields - they don't (PR2 clean break).
        assertThat(sidecars).doesNotContain("download_file");
        assertThat(sidecars).doesNotContain("sftp");
        assertThat(sidecars).doesNotContain("convert_to_file");
        assertThat(sidecars).doesNotContain("compression");
    }

    @Test
    @DisplayName("display_in_interface covers all source patterns + comparison + download_link")
    void coversAllDisplayPatterns() {
        @SuppressWarnings("unchecked")
        Map<String, Object> display = (Map<String, Object>) FileStorageHelp.get().get("display_in_interface");
        assertThat(display).containsKeys(
            "rule",
            "single_mcp",
            "single_core_node",
            "single_form_upload",
            "single_interface_file_input",
            "single_chat_attachment",
            "multi",
            "comparison",
            "download_link"
        );
        // Anti-drift on the form duality: single_form_upload value MUST mention
        // both the canonical access path AND the flat sidecar so the agent
        // sees both shapes are first-class.
        assertThat(display.get("single_form_upload").toString())
            .contains("_file_url");
        // Source H (interface form file input) must reference the canonical
        // FileRef + the same trigger contract as standalone form triggers.
        String interfaceFile = display.get("single_interface_file_input").toString();
        assertThat(interfaceFile).contains("<input type='file'");
        assertThat(interfaceFile).contains("{{trigger:<label>.output.<field>}}");
        // download_link example must explain why <a download> works (the
        // `disposition=inline` + same-origin rule) so the agent isn't
        // surprised when the link saves instead of opening inline.
        assertThat(display.get("download_link").toString())
            .contains("download");
    }

    @Test
    @DisplayName("wrong list forbids drilling .path AND legacy flat fields on the 4 producer nodes")
    void forbidsPathDrillingAndLegacyFlats() {
        @SuppressWarnings("unchecked")
        List<String> wrong = (List<String>) FileStorageHelp.get().get("wrong");
        // The narrowed rule: only `.path` drilling is forbidden. Metadata
        // scalars (.name, .mimeType, .size) ARE safe - the download_link
        // example uses .name. Regression guard: if a future edit re-broadens
        // the rule to "no drilling FileRef" it would contradict download_link.
        assertThat(wrong).anyMatch(s -> s.contains("drill") && s.contains(".path"));
        assertThat(wrong).anyMatch(s -> s.contains(".name") && s.contains("safe"));
        // PR2 anti-pattern: legacy flat fields on the 4 producer nodes are
        // dead at runtime. An agent reading stale training data must be
        // explicitly told NOT to reference them.
        assertThat(wrong).anyMatch(s ->
            s.contains(".file_url")
            && s.contains("download_file")
            && s.contains("sftp")
            && s.contains("convert_to_file")
            && s.contains("compression"));
    }

    @Test
    @DisplayName("auto_persist block tells the agent NOT to add a redundant download_file after MCP")
    void warnsAgainstRedundantDownloadFile() {
        String autoPersist = (String) FileStorageHelp.get().get("auto_persist");
        assertThat(autoPersist).contains("NO download_file node is needed");
    }

    @Test
    @DisplayName("returned map is stable across calls (idempotent)")
    void idempotent() {
        // Defensive: callers should be able to compare via .equals() across
        // help-module surfaces without worrying about internal mutation.
        Map<String, Object> a = FileStorageHelp.get();
        Map<String, Object> b = FileStorageHelp.get();
        assertThat(a).isEqualTo(b);
    }
}
