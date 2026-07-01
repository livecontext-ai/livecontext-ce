package com.apimarketplace.agent.tools.help;

import java.util.List;
import java.util.Map;

/**
 * Shared agent-facing help block describing how files travel through the
 * platform - from MCP catalog tool outputs and the {@code download_file}
 * core node, through the storage proxy, into interfaces.
 *
 * <p>Used by both {@code interface(action='help')} (embedded under the
 * {@code 3b_images_and_files} key) and {@code catalog(action='help',
 * topics=['file_storage'])}. Single source of truth so the two surfaces
 * never drift.
 *
 * <p><b>What this help promises to the agent</b> - every concept maps to
 * an action the agent can take through MCP tool calls:
 * <ul>
 *   <li>An output shape the agent will see in tool results
 *       ({@code _type:'file', path, ...} vs {@code file_url, ...}).</li>
 *   <li>A {@code variable_mapping} pattern the agent emits when wiring an
 *       interface node, with the exact corresponding HTML / js_template.</li>
 *   <li>An anti-pattern the agent must NOT emit (drilled {@code .path},
 *       redundant {@code download_file} after MCP, hand-built proxy URLs).</li>
 * </ul>
 *
 * <p>Pure value class - call {@link #get()} from either help module.
 */
public final class FileStorageHelp {

    private FileStorageHelp() {}

    /**
     * Immutable help map. Returned by both interface and catalog help
     * surfaces so the agent reads the SAME wording regardless of which
     * tool it asked.
     */
    public static Map<String, Object> get() {
        // Use a LinkedHashMap because Map.of() caps at 10 entries and ordering
        // helps the agent scan the sections in the natural read order.
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("title", "File storage in workflows and interfaces");

        map.put("concept",
            "Files (images, audio, PDFs, etc.) live in object storage. Many node types - MCP catalog " +
            "binary tools (image_generation, screenshot, …), the download_file core node, sftp downloads, " +
            "convert_to_file, compression, form file uploads, chat trigger attachments - emit a canonical " +
            "FileRef. The interface render auto-converts every FileRef into an authenticated proxy URL " +
            "(logged-in app) or HMAC-signed URL (marketplace + share preview) inside <img src>, " +
            "<a href>, <video src>, etc.");

        map.put("shapes", Map.of(
            "canonical",
                "{_type:'file', path:'tenantId/.../filename', name, mimeType, size}  - the universal " +
                "shape. Emitted by MCP catalog binary tools (image_generation, screenshot, …), the " +
                "catalog auto-dehydrator (any base64 leaf ≥ 64KB), the 4 core file-producing nodes " +
                "(download_file, sftp, convert_to_file, compression), form file uploads (under the " +
                "field's name), and chat trigger attachments.",
            "form_sidecars",
                "Form file uploads ALSO emit flat sidecar fields alongside the canonical FileRef: " +
                "{{trigger:<label>.output.<field>_file_url}}, _file_name, _file_size, _content_type. " +
                "These are convenience aliases for form-trigger payloads only; prefer the canonical " +
                "FileRef under {{trigger:<label>.output.<field>}} for consistency with all other sources."
        ));

        map.put("auto_persist",
            "Catalog tools that return base64 in their response auto-persist to S3 via the catalog " +
            "dehydrator. Heuristic threshold today: any base64 leaf ≥ 64KB (subject to change). After " +
            "auto-persist the agent-visible result contains a canonical FileRef. NO download_file node " +
            "is needed after an MCP file output - the file is already in storage.");

        // display_in_interface - 8 entries (max for Map.of is 10 entries → 5 keys).
        // Keep as a separate LinkedHashMap so we can grow.
        Map<String, Object> display = new java.util.LinkedHashMap<>();
        display.put("rule",
            "Map the canonical FileRef value into variable_mapping under any name you like, then use " +
            "that name in <img src>, <a href>, <video src>, etc. Same pattern regardless of the source " +
            "node - the interface render auto-rewrites the FileRef to a proxy URL with auth token " +
            "(logged-in app) or HMAC signature (marketplace + share preview).");
        display.put("single_mcp",
            "MCP source (canonical FileRef): " +
            "variable_mapping {'photo':'{{mcp:gen.output.images[0]}}'} → <img src=\"{{photo}}\"/>");
        display.put("single_core_node",
            "Core file-producing nodes (download_file, sftp, convert_to_file, compression) all emit " +
            "the canonical FileRef under `.output.file`: variable_mapping " +
            "{'photo':'{{core:download.output.file}}'} → <img src=\"{{photo}}\"/>");
        display.put("single_form_upload",
            "Form file fields produce the canonical FileRef under " +
            "`{{trigger:<label>.output.<field>}}` AND flat sidecars " +
            "`{{trigger:<label>.output.<field>_file_url}}`, `_file_name`, `_file_size`, " +
            "`_content_type`. Prefer the FileRef object for new workflows.");
        display.put("single_interface_file_input",
            "Interface forms that include `<input type='file' name='<field>'>` submit through " +
            "the SAME form-trigger contract as standalone form triggers. After the user " +
            "submits, the next workflow step reads the canonical FileRef under " +
            "`{{trigger:<label>.output.<field>}}` (same shape as source `single_form_upload`). " +
            "Flat sidecars (`_file_url`, …) are also emitted for back-compat, but prefer the " +
            "canonical FileRef in new interface designs.");
        display.put("single_chat_attachment",
            "Chat trigger attachments (when the user pastes a file into chat): " +
            "variable_mapping {'photo':'{{trigger:chat.output.attachments[0]}}'} → " +
            "<img src=\"{{photo}}\"/>");
        display.put("multi",
            "List of files: variable_mapping {'images':'{{mcp:gen.output.images}}'} → in js_template " +
            "iterate as `(data.images||[]).map(u => `<img src=\"${u}\">`)`. Each item is a proxy URL " +
            "string after the auto-conversion. Works the same for any list of FileRef values.");
        display.put("comparison",
            "Side-by-side: variable_mapping " +
            "{'a':'{{mcp:gen_a.output.images[0]}}','b':'{{core:dl.output.file}}'} → " +
            "<img src=\"{{a}}\"/><img src=\"{{b}}\"/>  - sources can be mixed (MCP + core).");
        display.put("download_link",
            "Map BOTH the FileRef AND its filename: variable_mapping " +
            "{'href':'{{core:dl.output.file}}','filename':'{{core:dl.output.file.name}}'} → " +
            "<a href=\"{{href}}\" download=\"{{filename}}\">Download</a>. Why this works: " +
            "the FileRef is rewritten to an opaque `/api/proxy/files/by-id/{id}/raw?disposition=inline&token=…` " +
            "URL (no tenant id / s3 key; or the HMAC-signed equivalent for marketplace/share preview), and the HTML " +
            "`download` attribute on `<a>` forces the browser to save the bytes instead of " +
            "navigating - same-origin URLs honour it even when the server returns " +
            "Content-Disposition: inline. Scalar metadata fields (.name, .mimeType, .size) are " +
            "SAFE to drill - only `.path` is forbidden (bare S3 key, no auth). Same for MCP: " +
            "{'href':'{{mcp:gen.output.images[0]}}','filename':'{{mcp:gen.output.images[0].name}}'}.");
        map.put("display_in_interface", display);

        map.put("send_to_a_tool",
            "To SEND a file to an mcp: API tool that takes one (Telegram send_photo `photo` / " +
            "send_document `document`, image/audio/video/file params on other APIs), map the canonical " +
            "FileRef into that tool's file param: {'photo':'{{interface:card.output.screenshot}}'} or " +
            "{'document':'{{core:dl.output.file}}'}. The platform uploads the bytes for you (multipart). " +
            "A plain STRING in the same param is sent verbatim = a public URL or the provider's own file " +
            "id. Same rule as <img>: pass the FileRef OBJECT, never .path or .id.");

        map.put("auth",
            "Token is a query param injected at iframe render time. The proxy is ORG-scoped: any member " +
            "of the file's workspace can view it (not just the uploader); only requests from a different " +
            "organization return 403.");

        map.put("wrong", List.of(
            "DON'T add a download_file node after an MCP that returns a file - wastes a step, the " +
                "file is already in S3.",
            "DON'T drill `.path` on a FileRef (e.g. '{{mcp:foo.output.images[0].path}}') - produces " +
                "a bare S3 key with no proxy prefix and no auth. Use the FileRef OBJECT itself in " +
                "<img src>; metadata fields like `.name`, `.mimeType`, `.size` ARE safe to drill " +
                "(they're scalars, not paths).",
            "DON'T reference `.file_url`, `.file_name`, `.file_size`, or `.content_type` on the 4 " +
                "core file-producing nodes (download_file, sftp, convert_to_file, compression). " +
                "These flat fields were REMOVED - the nodes emit ONLY the canonical FileRef under " +
                "`.file`. Templates like '{{core:dl.output.file_url}}' resolve to null at runtime. " +
                "Use '{{core:<label>.output.file}}' for rendering, drill `.file.name` for filename.",
            "DON'T construct proxy URLs by hand. Pass the FileRef object; the rewriter handles " +
                "auth + prefix.",
            "DON'T drill `.path` or `.id` when sending a FileRef to a tool's file param (e.g. Telegram " +
                "send_photo `photo`). Map the OBJECT so the platform can upload the bytes; a drilled " +
                "string would be treated as a remote URL / file id and the upload would not happen.",
            "DON'T put '{{mcp:foo.output.…}}' directly in raw HTML. Always go through " +
                "variable_mapping; raw HTML is for static markup."
        ));

        map.put("see_also",
            "Use download_file ONLY when the URL is external/public (e.g. user pastes an image URL " +
            "on a form). After download_file, the same {{...output.file}} canonical FileRef pattern works.");

        return map;
    }
}
