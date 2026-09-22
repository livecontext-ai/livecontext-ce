package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.service.NodeHelpFormatter;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.help.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Provides comprehensive help documentation for workflow creation.
 * Uses a hybrid approach:
 * - General concepts (overview, variables, spel): Static providers
 * - Node-specific help: Dynamic from database via NodeHelpFormatter
 *
 * The database table node_type_documentation is the single source of truth
 * for all node type definitions, with enriched parameters, outputs, concepts.
 */
@Component
@RequiredArgsConstructor
public class WorkflowHelpProvider {

    private final NodeLibraryService nodeLibraryService;
    private final NodeHelpFormatter nodeHelpFormatter;
    /** Reads the model catalogue so the generate help can name the ids. */
    private final com.apimarketplace.orchestrator.services.generation.GenerationExecutionService
            generationExecutionService;

    // ==================== AVAILABLE TOPICS ====================

    public static final List<String> TOPICS = List.of(
        "concepts",      // 📖 ARCHITECTURE & RULES - Read this first!
        "variables",     // Variable visibility and ancestor-only rule
        "spel",          // SpEL expression syntax and functions
        "overview",      // General overview and quick start
        "nodes",         // 📚 NODE LIBRARY - All available node types by category
        "triggers",      // All trigger nodes (schedule, table, webhook, manual, chat)
        "agents",        // AI nodes (agent, guardrail, classify)
        "cores",         // Control flow nodes (decision, switch, loop, split, etc.)
        "tables",        // Table operation nodes (CRUD)
        "mcps",          // MCP/integration nodes
        "interfaces",    // Interface linking
        "trigger",       // Trigger nodes (entry points) - alias for 'triggers'
        "mcp",           // MCP nodes (actions) - alias for 'mcps'
        "agent",         // Agent nodes (AI with tools) - specific node help
        "guardrail",     // Guardrail node - 1→1 AI content validation
        "classify",      // Classify node - 1→N exclusive AI routing
        "decision",      // Decision nodes (if/elsif/else) - 1→N exclusive
        "switch",        // Switch nodes (case matching) - 1→N exclusive
        "loop",          // Loop nodes (while iterations) - 1→2 (body/exit)
        "split",       // Split nodes (parallel items) - 1→N parallel
        "fork",          // Fork - split into N parallel streams
        "merge",         // Merge - wait for all streams
        "fork_merge",    // Fork and Merge patterns combined
        "aggregate",     // Aggregate (flatten N→1) - inverse of Split
        "transform",     // Transform - compute new fields
        "wait",          // Wait - pause execution
        "exit",          // Exit - end branch execution
        "response",      // Response - send chat message
        "crud",          // CRUD operations: insert_row, read_rows, update_row, delete_row, find_rows
        "resources",     // Pre-requisites: interfaces, agents, tables
        "schedule",      // Scheduling (cron expressions)
        "examples",      // Complete workflow examples
        "multi_dag",     // Multi-trigger / multi-DAG workflows
        "execute",       // Executing workflows programmatically (action='execute')
        "edge",          // Edge format (connecting nodes)
        "data_input",    // Data input node (inject data mid-workflow)
        "task",          // Task CRUD node (create, read, update, delete, list tasks)
        "stop_on_error", // StopOnError node (fail workflow with error message)
        "ssh",           // SSH node (execute commands on remote servers)
        "sftp",          // SFTP node (file operations on remote servers)
        "database",      // Database node (execute SQL queries)
        "media",         // Media node (probe, mux_audio, mix, extract_audio, concat, frame, overlay, subtitles)
        "generate",      // Generate node (image, video, audio, voice, music from a prompt)
        "runs",          // Inspecting past workflow runs
        "pin",           // Production version pinning (pin/unpin actions)
        "mocking",       // Node mocks: pin a node's output for editor runs (mock/mock_mode/mock_suggest)
        "node_policy"    // Per-node execution policy: retry, backoff, timeout, continueOnFailure, executeOnce, provider retry budget
    );

    // ==================== MAIN ENTRY POINT ====================

    /**
     * Get help for a single topic or multiple topics (batch).
     * Supports both String and List<String> parameters.
     */
    public Map<String, Object> getHelp(Object topicOrTopics) {
        // Handle List input: topics=['spel'] or topics=['webhook', 'agent']
        if (topicOrTopics instanceof List<?> topicsList) {
            if (topicsList.isEmpty()) {
                return ConceptsHelpProvider.getOverview();
            }

            // Single item list: treat as single topic (handles special topics like 'spel')
            if (topicsList.size() == 1) {
                return getHelp(topicsList.get(0).toString());
            }

            // Multiple items: batch query combining special topics and node-ids
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batch", true);
            result.put("requested", topicsList.size());

            Map<String, Object> content = new LinkedHashMap<>();
            List<String> nodeIdsToFetch = new ArrayList<>();

            for (Object item : topicsList) {
                String topic = item.toString().toLowerCase().trim();
                Map<String, Object> topicHelp = getHelpForSingleTopic(topic);
                if (topicHelp != null) {
                    content.put(topic, topicHelp);
                } else {
                    // Not a special topic, try as node-id
                    nodeIdsToFetch.add(topic);
                }
            }

            // Fetch remaining node-ids from NodeLibraryService
            if (!nodeIdsToFetch.isEmpty()) {
                Map<String, Object> nodesBatch = nodeLibraryService.getNodesBatch(nodeIdsToFetch);
                @SuppressWarnings("unchecked")
                Map<String, Object> nodes = (Map<String, Object>) nodesBatch.get("nodes");
                if (nodes != null) {
                    content.putAll(nodes);
                }
                // Include not_found from node lookup
                Object notFound = nodesBatch.get("not_found");
                if (notFound != null) {
                    result.put("not_found", notFound);
                }
            }

            result.put("found", content.size());
            result.put("content", content);
            return result;
        }

        // Single topic query (String)
        String topic = topicOrTopics != null ? topicOrTopics.toString() : null;
        if (topic == null || topic.isBlank()) {
            return ConceptsHelpProvider.getOverview();
        }

        // Try as special topic first
        Map<String, Object> help = getHelpForSingleTopic(topic.toLowerCase().trim());
        if (help != null) {
            return help;
        }

        // Not a special topic - try as node-id from NodeLibraryService
        Map<String, Object> nodesBatch = nodeLibraryService.getNodesBatch(List.of(topic));
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) nodesBatch.get("nodes");
        if (nodes != null && !nodes.isEmpty()) {
            return nodesBatch;
        }

        // Unknown topic - return overview
        return ConceptsHelpProvider.getOverview();
    }

    /**
     * Get help for a single topic. Returns null if not a recognized special topic.
     * Uses dynamic node help from database when available.
     */
    private Map<String, Object> getHelpForSingleTopic(String topic) {
        // Try database-driven node help first for specific node types
        Optional<NodeTypeDocumentationEntity> nodeOpt = nodeLibraryService.findByType(topic);
        if (nodeOpt.isPresent()) {
            Map<String, Object> formatted = nodeHelpFormatter.formatNodeHelp(nodeOpt.get());
            // Inject alias hints for nodes whose params accept multiple key spellings
            // (resolved by WorkflowBuilderModifier.harmonizeParams / UtilityNodeCreator).
            if ("loop".equals(topic)) {
                formatted = new LinkedHashMap<>(formatted);
                formatted.put("param_aliases",
                    "Both 'loopCondition' and 'maxIterations' (camelCase) and snake_case 'condition' / 'loop_condition' / 'max_iterations' are accepted. " +
                    "At least one of (loopCondition, maxIterations) is REQUIRED. " +
                    "Fix syntax: workflow(action='modify', node='<label>', params={maxIterations: 10})");
            } else if ("filter".equals(topic) || "sort".equals(topic)) {
                formatted = new LinkedHashMap<>(formatted);
                formatted.put("param_aliases",
                    "'input' is REQUIRED - the SpEL expression for the items to process. " +
                    "Aliases accepted: 'inputExpression', 'items', 'list'. " +
                    "Example: params={input: '{{mcp:fetch.output.rows}}', conditions: [...]}");
            } else if ("interface".equals(topic)) {
                formatted = new LinkedHashMap<>(formatted);
                formatted.put("param_aliases",
                    "Boolean toggles accept BOTH camelCase (canonical) and snake_case (alias). " +
                    "Pairs: isEntryInterface / is_entry_interface, generateScreenshot / generate_screenshot, " +
                    "exposeRenderedSource / expose_rendered_source, generatePdf / generate_pdf, " +
                    "pdfLandscape / pdf_landscape, generateVideo / generate_video " +
                    "(pdfFormat / pdf_format, videoPreset / video_preset and videoMode / video_mode are strings; " +
                    "videoMaxDurationSeconds / video_max_duration_seconds and videoFps / video_fps are integers). Pick one convention per call. " +
                    "All toggles default to false - only set them when you want the feature on. " +
                    "screenshot output → use generateScreenshot=true; pdf output → use generatePdf=true; " +
                    "video output → use generateVideo=true; " +
                    "rendered_html / rendered_css / rendered_js outputs → use exposeRenderedSource=true. " +
                    "The shape of the screenshot, the video and every preview is NOT set here: it is the " +
                    "interface's own format. Change it with interface(action='update', interface_id='<uuid>', " +
                    "format='vertical').");
            } else if ("media".equals(topic)) {
                // The DB row carries the params/outputs reference; the static section below
                // adds the operations table, per-op options and the worked recipes.
                formatted = new LinkedHashMap<>(formatted);
                formatted.putAll(getMediaHelp());
            } else if ("generate".equals(topic)) {
                // The DB row carries the params/outputs reference; the static section
                // below adds the pricing rules and the worked recipes.
                formatted = new LinkedHashMap<>(formatted);
                formatted.putAll(getGenerateHelp());
            }
            return formatted;
        }

        return switch (topic) {
            // Concepts & Core
            case "concepts", "architecture", "rules", "all" -> ConceptsHelpProvider.getConceptsHelp();
            case "variables", "visibility", "ancestor", "scope" -> ConceptsHelpProvider.getVariablesHelp();
            case "spel", "expressions", "syntax", "functions" -> ConceptsHelpProvider.getSpelHelp();
            case "overview", "general", "start" -> ConceptsHelpProvider.getOverview();

            // Node Library - using variable prefix categories (from DB)
            case "nodes", "library", "catalog" -> nodeLibraryService.getCategories();
            case "triggers" -> nodeLibraryService.getNodesByCategory("triggers");
            case "agents", "ai_nodes", "ai-nodes", "ai" -> nodeLibraryService.getNodesByCategory("agents");
            case "cores", "control_flow", "control-flow", "controlflow" -> nodeLibraryService.getNodesByCategory("cores");
            case "tables", "data_ops", "data-ops", "dataops" -> nodeLibraryService.getNodesByCategory("tables");
            case "mcps", "integration", "integrations", "external" -> nodeLibraryService.getNodesByCategory("mcps");
            case "interfaces" -> getInterfaceWorkflowHelp();

            // Aliases for node types (try DB)
            case "trigger" -> nodeLibraryService.getNodesByCategory("triggers");
            case "agent" -> getNodeHelpOrCategory("agent", "agents");
            case "mcp" -> getNodeHelpOrCategory("mcp", "mcps");
            case "interface" -> getInterfaceWorkflowHelp();

            // Control flow aliases
            case "if", "condition", "branch", "branching" -> getNodeHelpByType("decision");
            case "case", "match" -> getNodeHelpByType("switch");
            case "while", "iteration" -> getNodeHelpByType("loop");
            case "for_each", "parallel_iteration" -> getNodeHelpByType("split");
            case "fork_merge", "parallel", "convergence" -> getForkMergeHelp();
            case "flatten", "collect", "reduce" -> getNodeHelpByType("aggregate");

            // Data input aliases
            case "data_input", "datainput", "input_data" -> getNodeHelpByType("data_input");

            // Media aliases (fallback when the node docs row is absent; the "media" topic
            // itself is normally answered above with the DB row merged with this section)
            case "media", "mux", "mux_audio", "extract_audio", "audio_video",
                 "concat", "stitch", "join", "join_videos", "frame", "thumbnail", "cover", "overlay", "watermark",
                 "subtitles", "subtitle", "captions", "caption" -> getMediaHelp();

            // Generate aliases (fallback when the node docs row is absent; the "generate"
            // topic itself is normally answered above with the DB row merged with this section)
            case "generate", "generation", "image_generation", "text_to_image", "text_to_video",
                 "text_to_speech", "tts", "voice_over", "music_generation" -> getGenerateHelp();

            // Edge help (connection format)
            case "edge", "edges", "connection", "connections" -> getEdgeHelp();

            // CRUD operations - redirect to table nodes
            case "crud", "insert_row", "read_rows", "get_rows", "fetch_rows", "update_row", "delete_row", "find_rows", "find" -> getCrudHelp();
            case "resources", "prerequisites", "datasource" -> getNodeHelpByType("table");

            // Examples
            case "examples", "example", "complete" -> ExamplesHelpProvider.getCompleteExamples();

            // Multi-DAG / Multi-trigger
            case "multi_dag", "multi_trigger", "multiple_triggers", "multiple_dags", "dag" -> ConceptsHelpProvider.getMultiDagHelp();

            // Execute - programmatic workflow execution
            case "execute", "fire", "trigger_workflow" -> getExecuteHelp();

            // Mock mode - pin node outputs for editor runs
            case "mocking", "mock", "mocks", "mock_mode", "mock_suggest", "dry_run" -> ConceptsHelpProvider.getMockingHelp();

            // Per-node execution policy - retry / backoff / timeout / continueOnFailure / executeOnce / provider retry budget
            case "node_policy", "nodepolicy", "policy", "retry", "retries", "timeout", "continue_on_failure",
                 "execute_once", "execution_policy", "provider_retry" -> ConceptsHelpProvider.getNodePolicyHelp();

            // Plan format - JSON structure for set_plan/get_plan
            case "plan", "set_plan", "get_plan", "plan_format" -> ExamplesHelpProvider.getPlanHelp();

            // Runs inspection
            case "runs", "run", "run_history", "inspect_runs" -> getRunsHelp();

            // Pin / unpin - production version promotion
            case "pin", "unpin", "pinned", "production", "promote" -> getPinHelp();

            // Schedule alias
            case "scheduling", "cron", "interval", "recurring" -> getNodeHelpByType("schedule");

            // Guardrail/classify aliases
            case "validation", "safety" -> getNodeHelpByType("guardrail");
            case "classification", "categorize", "routing" -> getNodeHelpByType("classify");

            // Not a special topic - return null to try as node-id
            default -> null;
        };
    }

    /**
     * Get help for a specific node type from the database.
     */
    private Map<String, Object> getNodeHelpByType(String nodeType) {
        return nodeLibraryService.findByType(nodeType)
            .map(nodeHelpFormatter::formatNodeHelp)
            .orElse(null);
    }

    /**
     * Get node help if type exists, otherwise return category help.
     */
    private Map<String, Object> getNodeHelpOrCategory(String nodeType, String category) {
        Optional<NodeTypeDocumentationEntity> nodeOpt = nodeLibraryService.findByType(nodeType);
        if (nodeOpt.isPresent()) {
            return nodeHelpFormatter.formatNodeHelp(nodeOpt.get());
        }
        return nodeLibraryService.getNodesByCategory(category);
    }

    /** Build an insertion-ordered map (guarantees key order in JSON output). */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private Map<String, Object> getInterfaceWorkflowHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Interface Nodes - Wiring Pages to Workflow");

        result.put("1_concept", "Workflow=backend, interface=frontend. Workflow reaches interface → page shown → user interacts → triggers fire → workflow processes.");

        result.put("2_add_node", ordered(
            "syntax", "workflow(action='add_node', type='interface', label='...', params={interface_id: '<uuid>', variable_mapping: {...}, action_mapping: {...}, isEntryInterface: true|false, generateScreenshot: true|false, generatePdf: true|false, pdfFormat: 'A4', pdfLandscape: true|false, generateVideo: true|false, videoPreset: 'vertical', videoMaxDurationSeconds: 30, videoMode: 'smooth', videoFps: 30, exposeRenderedSource: true|false}, connect_after='...')",
            "interface_id", "REQUIRED. UUID returned by interface(action='create').",
            "shape_and_format", "NOT a node param. The dimensions of the screenshot, the video and every preview come from the INTERFACE's own format, because its HTML is authored for one fixed viewport width. Set it with interface(action='update', interface_id='<uuid>', format='vertical') - or pass format on interface(action='create'). Call interface(action='help') for the preset list. A `format` key on this node is ignored (it was a node param in an earlier version).",
            "isEntryInterface", "OPTIONAL boolean (default false). true = the app's entry page: the application opens on it (page order otherwise follows canvas layout). An app has exactly ONE entry page: setting it to true on add_node or modify automatically clears the flag on any other interface, and the response reports it under `entry_interface_moved`.",
            "generateScreenshot", "OPTIONAL boolean (default false). true → adds a `screenshot` FileRef to output (PNG of the rendered page). Dimensions follow the interface's own format: an exact frame when it declares one, a full-page capture at 1280 wide when it does not. To USE it, map the WHOLE FileRef into a file-accepting tool param to upload it: e.g. Telegram send_photo 'photo': '{{interface:<label>.output.screenshot}}', an email attachment, or an agent image input. Pass the object itself, NOT .path or .id. Best-effort: capture failure leaves the field absent, workflow continues. Rendering is an OPTIONAL deployment component: when it is absent, workflow(action='validate') reports an INTERFACE_RENDERER_UNAVAILABLE warning and the field stays absent at run time - only the user/admin can enable the component.",
            "generatePdf", "OPTIONAL boolean (default false). true → adds a `pdf` FileRef to output (a PDF rendering of the same interface). To USE it, map the WHOLE FileRef into a file-accepting tool param: e.g. an email attachment, Telegram send_document 'document': '{{interface:<label>.output.pdf}}', or an agent file input. Pass the object itself, NOT .path or .id. Best-effort: render failure leaves the field absent, workflow continues. Same optional-component caveat as generateScreenshot: an INTERFACE_RENDERER_UNAVAILABLE warning from workflow(action='validate') means the pdf field will be absent on this installation.",
            "pdfFormat", "OPTIONAL string (default 'A4'). Page size for generatePdf: 'A4' | 'Letter' | 'Legal'. Unknown values fall back to A4. Ignored when generatePdf is false.",
            "pdfLandscape", "OPTIONAL boolean (default false). true → the generatePdf output is rendered in landscape orientation. Ignored when generatePdf is false.",
            "generateVideo", "OPTIONAL boolean (default false). true → adds a `video` FileRef to output (an MP4 recording of the interface's animation). The recording starts when the page loads and stops as soon as the interface's JS sets window.__DONE__ = true, or after videoMaxDurationSeconds otherwise - so an animated interface (typewriter reveal, counters) ends its own clip at exactly the right moment. To USE it, map the WHOLE FileRef into a file-accepting tool param: e.g. Telegram send_video 'video': '{{interface:<label>.output.video}}', a social upload param, or an email attachment. Pass the object itself, NOT .path or .id. Best-effort: recording failure leaves the field absent, workflow continues. Same optional-component caveat as generateScreenshot: an INTERFACE_RENDERER_UNAVAILABLE warning from workflow(action='validate') means the video field will be absent on this installation.",
            "videoPreset", "OPTIONAL string. Per-video override of the capture dimensions: 'vertical' (1080x1920, TikTok/Reels/Shorts) | 'horizontal' (1920x1080) | 'square' (1080x1080). Precedence: explicit videoPreset > the interface's own format > vertical default. OMIT it to let the video and the screenshot share the interface's shape; set it only when this one clip must differ (e.g. a vertical cut of a widescreen dashboard). Unknown values are treated as absent. Ignored when generateVideo is false.",
            "videoMaxDurationSeconds", "OPTIONAL integer (default 30, clamped to 5-120). Recording ceiling in seconds for generateVideo. The interface ends the clip earlier by setting window.__DONE__ = true in its JS. Ignored when generateVideo is false.",
            "videoMode", "OPTIONAL string (default 'smooth'). 'smooth' renders the clip OFFLINE frame by frame under a virtual clock: every frame is perfect and the motion is fluid regardless of load (rendering takes roughly 2-4x the clip duration). 'live' records in real time (faster to produce, frames can drop under load). Unknown values fall back to smooth. Ignored when generateVideo is false.",
            "videoFps", "OPTIONAL integer (default 30, clamped to 10-60). Output frame rate of the generateVideo clip. 60 gives the smoothest motion at roughly double the smooth-mode render time. Ignored when generateVideo is false.",
            "exposeRenderedSource", "OPTIONAL boolean (default false). true → adds 3 string outputs `rendered_html`, `rendered_css`, `rendered_js` (the exact templates the iframe shows, HTML with {{var|default}} substituted via variable_mapping). Downstream consumers: email body, agent text input, debug logs. References: {{interface:<label>.output.rendered_html}}, .rendered_css, .rendered_js. Each capped at 256 KB. Best-effort: render failure leaves the fields absent, workflow continues.",
            "param_naming", "All boolean params accept BOTH conventions: camelCase (canonical: isEntryInterface, generateScreenshot, generatePdf, pdfLandscape, generateVideo, exposeRenderedSource) and snake_case aliases (is_entry_interface, generate_screenshot, generate_pdf, pdf_landscape, generate_video, expose_rendered_source). pdfFormat/pdf_format, videoPreset/video_preset and videoMode/video_mode are strings; videoMaxDurationSeconds/video_max_duration_seconds and videoFps/video_fps are integers. Pick one and stick to it; the validator accepts either. There is no format param here: the shape lives on the interface (see shape_and_format)."
        ));

        result.put("3_variable_mapping", ordered(
            "purpose", "Maps generic HTML vars ({{title}}) to workflow data.",
            "syntax", "{'title': '{{mcp:fetch.output.name}}', 'photo': '{{core:dl.output.file}}'}",
            "js_access", "All mapped data in window.__RESOLVED_DATA__ for js_template.",
            "images", "Use download_file/sftp/convert_to_file/compression BEFORE interface. Map `output.file` (canonical FileRef) → <img src=\"{{photo}}\"/>. " +
                "The FileRef object is auto-rewritten to a tokenised URL (auth'd app) or an HMAC-signed URL (marketplace + share preview for anonymous visitors). " +
                "No legacy `file_url` - these nodes emit only `file` as the canonical FileRef.",
            "file_params", "Sending a file to an mcp: API tool that takes one (Telegram send_photo `photo`/send_document `document`, etc.): map the WHOLE FileRef into that param, e.g. {'photo': '{{interface:card.output.screenshot}}'} or {'document': '{{core:dl.output.file}}'}. " +
                "The platform uploads the bytes for you. A plain string in the same param is sent verbatim = a public URL or the provider's own file id. Map the object, never .path or .id.",
            "url_pull_apis", "Some API params want a URL the provider downloads ITSELF (Instagram create_media_container video_url, TikTok PULL_FROM_URL source, link params) - a FileRef does NOT work there because platform file URLs need auth. " +
                "Add a public_link node (params={file:'{{...output.file}}', ttl_minutes:240}) and map its {{core:<label>.output.url}} into the URL param: a public, expiring, signed URL on the platform's own storage.",
            "BEST_PRACTICE", "Prefer variable_mapping for scalars (visible in builder). js_template only for arrays/JSON parsing. " +
                "Agent response is STRING - sub-field access won't work. Map whole response, parse in js_template."
        ));

        result.put("4_action_mapping", ordered(
            "purpose", "Binds HTML #id → trigger/navigation. Triggers MUST exist in workflow BEFORE adding action_mapping.",
            "shape", "Map<CssSelector, ActionToken> - value is ALWAYS a single string, NEVER an object. " +
                "Both key and value are STRINGS at every position. There is no nested structure inside a value.",
            "grammar", "Each entry MUST follow this exact grammar:\n" +
                "  KEY   : '#' + html-id     (must start with '#'. Example: '#search-form', '#delete-btn')\n" +
                "  VALUE : one of these string tokens - pick exactly ONE:\n" +
                "    'trigger:<normalized_label>:submit'    (form trigger - captures <input name> fields)\n" +
                "    'trigger:<normalized_label>:click'     (manual trigger - no data)\n" +
                "    'trigger:<normalized_label>:message'   (chat trigger - captures <input name='message'>)\n" +
                "    'interface:<normalized_label>:navigate' (switch displayed page in same DAG)\n" +
                "    '__continue'                           (advance the DAG past this interface node)\n" +
                "    '__pagination:next' | '__pagination:prev' | '__pagination:first' | '__pagination:last'",
            "types", ordered(
                "submit", "{'#form': 'trigger:label:submit'} - captures all <input name> as {name: value} pairs. " +
                    "Data accessible downstream on trigger: {{trigger:<label>.output.<field_name>}} where field_name matches <input name>.",
                "click", "{'#btn': 'trigger:label:click'} - fires trigger, no data",
                "message", "{'#chat': 'trigger:label:message'} - captures <input name='message'> from chat form. Same output pattern as submit.",
                "navigate", "{'#link': 'interface:<normalized_label>:navigate'} - switch to another interface in the SAME DAG. " +
                    "No workflow advance - just switches the displayed page. Target must be an executed interface node in the same DAG. " +
                    "Label is normalized: 'Settings Page' → interface:settings_page:navigate.",
                "continue", "{'#btn': '__continue'} - BLOCKING. REQUIRED when another node follows. " +
                    "Without __continue, workflow auto-advances immediately (user sees page and can interact, but workflow does NOT wait - successors run right away).",
                "pagination", "{'#prev': '__pagination:prev', '#next': '__pagination:next'} - " +
                    "navigates between results from different trigger fires. Each submit = new result (epoch). Prev/next browse the history."
            ),
            "field_binding_is_automatic_no_renaming", "Form fields auto-bind 1:1 BY NAME from <input name='X'> to trigger.output.X. " +
                "There is NO renaming layer inside action_mapping. If your HTML <input name='search_query'> must feed a trigger field called 'search_keyword', " +
                "you have exactly TWO options: (a) change the HTML to <input name='search_keyword'> so names align, OR (b) keep the HTML as-is " +
                "and add a downstream code/transform node that maps {search_query → search_keyword} from trigger.output. " +
                "Do NOT try to express the rename inside action_mapping - the value is a single string token, not a mapping object.",
            "WRONG", List.of(
                "WRONG: action_mapping: {'search_leads': 'trigger:search:submit'} - key MUST start with '#'. Use '#search-leads-form' (matching the HTML id).",
                "WRONG: action_mapping: {'#form': {trigger: 'search', mapping: {a: 'b'}}} - value is a STRING token, not an object. " +
                    "If you wanted to rename fields, see 'field_binding_is_automatic_no_renaming' - fix the HTML <input name> or remap downstream.",
                "WRONG: action_mapping: {'#form': 'search:submit'} - value MUST start with the prefix 'trigger:' or 'interface:' (or be one of '__continue', '__pagination:*').",
                "WRONG: action_mapping: {'#form': 'trigger:Search Input:submit'} - labels MUST be normalized (lowercase, spaces → '_'). Use 'trigger:search_input:submit'.",
                "WRONG: action_mapping: {'#form': 'trigger:search_input:fire'} - only 'submit', 'click', 'message' are valid trigger event suffixes. Use 'interface:<label>:navigate' to switch page; a 'trigger:<label>:navigate' written by an older builder still works and resolves against interfaces.",
                "WRONG: action_mapping: {'#btn': 'trigger:other_dag_trigger:click'} - see 'same_dag_rule' below. The trigger must live in the SAME DAG as this interface node."
            ),
            "same_dag_rule", "Interface can ONLY reference triggers/interfaces in its OWN DAG. Multiple buttons = multiple triggers in ONE DAG, NOT multiple DAGs.",
            "label_normalization", "Labels are normalized in ALL references: lowercase, spaces/special chars → underscore. " +
                "'Search Input' → trigger:search_input:submit. 'My Page' → interface:my_page:navigate.",
            "wiring_steps", "1. Create HTML with element IDs → 2. Create triggers in workflow (BEFORE interface node) → " +
                "3. Add interface node with action_mapping binding #ids to triggers → " +
                "4. User submits form → trigger fires with {input_name: value, ...} → DAG processes → results update on page",
            "example", "HTML: <form id=\"search\"><input name=\"query\"/></form><button id=\"del\">Del</button><button id=\"next\">Next</button>\n" +
                "action_mapping: {'#search':'trigger:search:submit', '#del':'trigger:delete:click', '#next':'__continue'}"
        ));

        result.put("5_interactions", ordered(
            "blocking_WARNING", "With __continue → BLOCKING (workflow pauses, page displayed, waits for user to click __continue to advance). " +
                "Without __continue → AUTO-ADVANCE (page displayed, user can interact with buttons/forms, but workflow does NOT wait - successors run immediately).",
            "clicks", "Unlimited. Each click fires trigger → DAG runs → new result accumulates. __pagination to browse history.",
            "navigate", "Switches to another interface in the SAME DAG without advancing workflow. " +
                "Target page must have been executed by the workflow (variables resolved). Like tabs in a web app."
        ));

        result.put("6_patterns", ordered(
            "wizard", "page1→(__continue)→page2→page3. Linear flow, __continue advances between pages.",
            "dashboard", "One page, multiple buttons/triggers in same DAG. No __continue - page stays, user interacts freely.",
            "multi_page_app", "fork → interface:profile + interface:posts + interface:settings → merge. " +
                "All pages execute in parallel (variables resolved), user navigates freely between them. " +
                "Each page has navigate buttons: {'#go-posts': 'interface:posts:navigate', '#go-settings': 'interface:settings:navigate'}. " +
                "Use isEntryInterface=true on the main page. " +
                "For fork/merge wiring syntax: workflow(action='help', topics=['fork_merge']).",
            "carousel", "Same as multi_page_app fork pattern, but pages display simultaneously side by side."
        ));

        result.put("7_rules", ordered(
            "selectors", "CSS selector must match HTML id exactly",
            "trigger_outputs", "Form submit fires trigger → data accessible as {{trigger:<label>.output.<field>}}. " +
                "Example: <input name='query'/> submitted via trigger:search → {{trigger:search.output.query}}",
            "entry", "isEntryInterface=true on main page only"
        ));

        result.put("7b_outputs", ordered(
            "always_present", "interface_id (string UUID), action_mapping (object), is_entry_interface (boolean).",
            "action_data", "After the user fires a trigger-bound action: output.<action_name>.<field> + output.<action_name>.fired_at (ISO timestamp). <action_name> = normalized trigger label. Absent until the user fires - guard with a SpEL default ({{interface:x.output.submit.email|}}).",
            "screenshot", "OPTIONAL FileRef PNG. Dimensions follow the interface's own format (set it with interface(action='update', interface_id='<uuid>', format='vertical')): an exact WIDTHxHEIGHT frame when it declares one (below-the-fold content is cropped), otherwise a full-page capture at 1280x800 viewport width. Present iff generateScreenshot=true AND the rendering component captured successfully. Absent on failure or when the optional rendering component is not enabled on this installation (workflow continues; workflow(action='validate') warns INTERFACE_RENDERER_UNAVAILABLE in that case).",
            "video", "OPTIONAL FileRef MP4. Present iff generateVideo=true AND the rendering component recorded successfully. Dimensions: explicit videoPreset when set (vertical 1080x1920 / horizontal 1920x1080 / square 1080x1080), otherwise the interface's own format, otherwise vertical. Length is at most videoMaxDurationSeconds, shorter when the page sets window.__DONE__ = true. Same absence/validate-warning semantics as screenshot.",
            "rendered_html", "OPTIONAL string - iframe-equivalent HTML with {{var|default}} substituted from variable_mapping. Present iff exposeRenderedSource=true AND interface has an htmlTemplate. Capped at 256 KB (truncated past). Absent on render failure.",
            "rendered_css", "OPTIONAL string - raw CSS template (NOT var-substituted, matches what the iframe receives). Present iff exposeRenderedSource=true AND interface has cssTemplate. Capped at 256 KB.",
            "rendered_js", "OPTIONAL string - raw JS template (NOT var-substituted; runtime vars are injected via window.__RESOLVED_DATA__ in the iframe). Present iff exposeRenderedSource=true AND interface has jsTemplate. Capped at 256 KB.",
            "spel_examples", List.of(
                "{{interface:my_form.output.interface_id}}                // static",
                "{{interface:my_form.output.submit.email}}                // form field after a 'submit' trigger fires",
                "{{interface:my_form.output.submit.fired_at}}             // ISO timestamp of the fire",
                "{{interface:my_form.output.screenshot}}                  // FileRef PNG (generateScreenshot=true)",
                "{{interface:my_form.output.video}}                       // FileRef MP4 (generateVideo=true)",
                "{{interface:my_form.output.rendered_html}}               // resolved HTML (exposeRenderedSource=true)",
                "{{interface:my_form.output.rendered_css}}                // raw CSS (exposeRenderedSource=true, absent if interface has no CSS)",
                "{{interface:my_form.output.rendered_js}}                 // raw JS  (exposeRenderedSource=true, absent if interface has no JS)"
            )
        ));

        // ── 8. COMPLETE END-TO-END EXAMPLE ──
        result.put("8_end_to_end", ordered(
            "scenario", "Interactive search page: user enters query → agent processes → results displayed.",
            "step_1_create_interface", "interface(action='create', name='Search Page', html_template='<meta name=\"viewport\" content=\"width=1280\">" +
                "<h1>{{title|Search}}</h1><form id=\"search-form\"><input name=\"query\" placeholder=\"Search...\"/>" +
                "<button type=\"submit\">Go</button></form><div id=\"results\">{{results|Enter a query}}</div>', " +
                "css_template='body{margin:0;padding:24px;font-family:system-ui,sans-serif}', js_template='') → returns interface_id",
            "step_2_create_trigger", "workflow(action='add_node', type='form', label='Search Input', params={fields:[{name:'query',type:'text',label:'Query'}]})",
            "step_3_add_agent", "workflow(action='add_node', type='agent', label='Search Agent', params={agent_id:'<uuid>'}, connect_after='Search Input')",
            "step_4_add_interface_node", "workflow(action='add_node', type='interface', label='Search Page', params={" +
                "interface_id:'<uuid-from-step-1>', " +
                "variable_mapping:{'title':'Search App', 'results':'{{agent:search_agent.output.response}}'}, " +
                "action_mapping:{'#search-form':'trigger:search_input:submit'}}, connect_after='Search Agent')",
            "flow", "User sees page → types query → submits → trigger:search_input fires with {query:'...'} → agent runs → results update on page. " +
                "Note: 'Search Input' normalized to 'search_input' in trigger reference, 'Search Agent' to 'search_agent' in variable_mapping."
        ));

        return result;
    }

    /**
     * Get help for edge/connection format.
     */
    /**
     * Media node help: operations table, per-operation params with defaults/bounds,
     * and worked recipes (simple mux, ducked mix, probe-calibrated TTS, clip compilation,
     * cover frame, watermark).
     */
    private Map<String, Object> getMediaHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Media Node - Audio/Video Processing (probe, mux_audio, mix, extract_audio, concat, frame, overlay, subtitles)");
        result.put("requires_component", "Runs on the optional renderer component. When that component is not " +
            "enabled on this installation, the node FAILS at run time (producing the media output IS its purpose - " +
            "this is NOT best-effort like interface screenshots) and validate warns with MEDIA_RENDERER_UNAVAILABLE. " +
            "Only the user or an administrator can enable the component.");
        result.put("file_params", "Every file param (input, video, audio, image, tracks[].source, inputs[].source) " +
            "takes the WHOLE FileRef output of an upstream node as a whole-value template, e.g. " +
            "'{{core:download.output.file}}' or '{{interface:card.output.video}}' - never .path, never a URL string. " +
            "All params accept {{...}} templates resolved at run time. A file that ALREADY exists in Files (no " +
            "upstream node) can be passed as the literal FileRef object from files(action='get') response field `ref`.");
        result.put("operations", ordered(
            "probe", "Read metadata. params={operation:'probe', input:'{{...file}}'}. Outputs FLAT fields (no file): " +
                "duration_seconds, size_bytes, format_name, bit_rate, has_video, has_audio, " +
                "video={codec,width,height,fps}|null, audio={codec,sample_rate,channels}|null.",
            "mux_audio", "Put ONE audio track onto ONE video (the 90% case). params={operation:'mux_audio', " +
                "video:'{{...file}}', audio:'{{...file}}'} + options below. Output: file (mp4, video stream copied, " +
                "audio aac) + duration_seconds.",
            "mix", "Mix 1-8 audio tracks, optionally onto a video. params={operation:'mix', tracks:[{source:'{{...file}}'}, ...]" +
                ", video:'{{...file}}' (optional)} + options below. Output: file (mp4 when video present, else " +
                "mp3/wav/aac per output_format) + duration_seconds.",
            "extract_audio", "Pull the audio track out of a video. params={operation:'extract_audio', input:'{{...file}}', " +
                "output_format:'mp3'|'wav'|'aac' (default mp3), audio_bitrate:'192k', trim_start_seconds/trim_end_seconds " +
                "(optional)}. Output: file + duration_seconds.",
            "concat", "Glue 1-8 videos back to back into ONE mp4. params={operation:'concat', " +
                "inputs:[{source:'{{...file}}'}, ...]} + options below. A SINGLE input is allowed - that is the " +
                "trim/speed-edit use case. Output: file (always mp4) + duration_seconds.",
            "frame", "Extract ONE still image (cover/thumbnail). params={operation:'frame', input:'{{...file}}'} + " +
                "options below. Output: file (image/jpeg or image/png) + timestamp_seconds (the ACTUAL timestamp " +
                "used after default/clamp); duration_seconds is null for frame.",
            "overlay", "Burn an image (logo, watermark, badge) onto a video. params={operation:'overlay', " +
                "video:'{{...file}}', image:'{{...file}}'} + options below. PNG alpha is respected; the operation " +
                "fails if the image file is not an image. Output: file (mp4) + duration_seconds.",
            "subtitles", "Burn timed captions INTO the picture (they become part of the image, so they show on " +
                "every player and on platforms that ignore a caption track). params={operation:'subtitles', " +
                "video:'{{...file}}', cues:[{start_seconds:0, end_seconds:2.4, text:'It starts here'}, ...]} + " +
                "options below. Output: file (mp4) + duration_seconds."
        ));
        result.put("mux_audio_options", ordered(
            "volume", "Audio volume in percent 0-400, default 100",
            "offset_seconds", "Audio starts at this time on the video, >= 0, default 0",
            "trim_start_seconds/trim_end_seconds", "Use only this segment of the AUDIO, >= 0, optional",
            "loop", "Loop the audio to the video length, default false (same effect as audio_fit:'loop')",
            "fade_in_seconds/fade_out_seconds", "Fades in seconds, >= 0, defaults 0 / 1.0",
            "keep_original_audio", "Keep the video's own audio MIXED under the new track, default false; " +
                "original_volume (percent 0-400, default 100) sets its level",
            "audio_fit", "What happens when audio is shorter/longer than the video: pad | shortest | loop, default pad",
            "normalize", "Loudness normalisation: true (default, -16 LUFS), false, or a LUFS target number in -70..-5",
            "audio_bitrate", "Output audio bitrate string, default '192k'"
        ));
        result.put("mix_track_options", ordered(
            "id", "Optional name used by duck_under references; defaults to track_1, track_2, ... in array order",
            "source", "REQUIRED whole FileRef expression for this track's audio",
            "volume/offset_seconds/trim_*/loop/fade_*", "Same semantics and defaults as mux_audio " +
                "(fade_out_seconds defaults to 0 here)",
            "speed", "Playback speed 0.5-2.0, pitch-preserving, default 1.0",
            "duck_under", "Id of ANOTHER track: THIS track auto-lowers while that one is audible " +
                "(sidechain). Tune with duck_amount_db (default 12), duck_attack_ms (20), duck_release_ms (300)",
            "globals", "keep_original_audio/original_volume (only with video), audio_fit (default pad), " +
                "normalize (default true), audio_bitrate ('192k'), output_format (mp4 forced with video; " +
                "mp3|wav|aac otherwise, default mp3)"
        ));
        result.put("concat_input_options", ordered(
            "source", "REQUIRED whole FileRef expression (or literal FileRef object) of the clip; the clip may or " +
                "may not have audio (silent clips get a silent bed when re-encoding)",
            "trim_start_seconds", "Use the clip only FROM this second, >= 0, optional",
            "trim_end_seconds", "Use the clip only UP TO this second; must be GREATER than trim_start_seconds, optional",
            "speed", "Playback speed 0.5-2.0, pitch-preserving, default 1.0"
        ));
        result.put("concat_options", ordered(
            "transition", "cut (default) or crossfade. crossfade requires at least 2 inputs and blends both video " +
                "and audio between consecutive clips",
            "transition_seconds", "Crossfade length 0.1-5.0 seconds, default 0.5. It must be shorter than every " +
                "clip's EFFECTIVE duration (after trim/speed) - the run fails naming the offending clip otherwise. " +
                "Output duration = sum of effective durations - (N-1) x transition_seconds",
            "target_width/target_height", "Output canvas, 16-4096 each, BOTH or NEITHER (odd values are rounded " +
                "down to even). Default: the FIRST input's dimensions. Clips that do not match are scaled to FIT " +
                "and padded with black bars, never stretched",
            "target_fps", "Output frame rate 1-60, default: the FIRST input's fps",
            "fade_in_seconds/fade_out_seconds", "Global fades on the RESULT, >= 0, defaults 0 / 0 (NOTE: " +
                "mux_audio's fade_out default is 1.0; concat's is 0 on purpose)",
            "normalize", "Default FALSE for concat (unlike mux/mix): set true (or a LUFS number -70..-5) to even " +
                "out loudness between clips - it forces the re-encode path",
            "audio_bitrate", "Re-encode path only, default '192k'"
        ));
        result.put("frame_options", ordered(
            "at_seconds", "Timestamp of the still, >= 0. DEFAULT: the MIDDLE of the video (duration/2). A value " +
                "past the end is CLAMPED to just before the end - never an error. The output field " +
                "timestamp_seconds reports the ACTUAL timestamp used",
            "image_format", "jpeg (default, high quality) or png",
            "width", "Optional 16-4096: scale the image to this width, aspect ratio kept (height automatic)"
        ));
        result.put("overlay_options", ordered(
            "position", "top_left | top_right | bottom_left | bottom_right (default) | center - the corner/center " +
                "the image is anchored to",
            "margin_px", "Distance in pixels from the two nearest edges, >= 0, default 24 (ignored for center)",
            "width_percent", "Image width as a percent of the VIDEO width, 1-100, default 15 (height automatic, " +
                "aspect ratio kept)",
            "opacity", "0-1, default 1.0 (semi-transparent watermarks: e.g. 0.5)",
            "start_seconds/end_seconds", "Optional visibility window: the overlay shows only between these " +
                "timestamps (end_seconds must be greater than start_seconds); absent = the whole video. The " +
                "video re-encodes; the audio is copied untouched when present"
        ));
        result.put("subtitles_cues", ordered(
            "cues", "REQUIRED array of 1-600 entries, each {start_seconds, end_seconds, text}: when the line " +
                "appears, when it disappears (seconds from the start of the VIDEO), and what it says. text is at " +
                "most 240 characters - split a longer line across consecutive cues - and the whole track is at " +
                "most 40000 characters. Can also be a whole-value expression when the track is computed upstream " +
                "(a transcription, a code node): cues:'{{core:build_cues.output.result.cues}}'.",
            "ordering", "Cues must be given in ASCENDING, NON-OVERLAPPING order (each start_seconds at or after " +
                "the previous end_seconds). An overlap is REFUSED rather than reordered: two cues covering the " +
                "same instant are drawn on top of each other, so an overlap is always a timing bug.",
            "gaps", "Gaps between cues are fine and normal - nothing is shown there. A cue that runs past the end " +
                "of the video simply stops being visible when the video ends.",
            "timing_source", "To caption a narration you generated, time the cues from the audio you are burning " +
                "in, not from the script: media probe gives the audio's real duration_seconds. Cues that ALL start " +
                "at or after the end of the video are refused rather than producing a video with no caption on it."
        ));
        result.put("subtitles_options", ordered(
            "style", "tiktok (default) or classic. tiktok is the big, bold, UPPERCASE block that sits above the " +
                "middle of the frame (the short-form video look); classic is smaller, mixed case, near the bottom " +
                "(the film-subtitle look). Every size in a preset is a percentage of the video HEIGHT, so one " +
                "style reads the same on a 720x1280 phone cut and a 1080x1920 master.",
            "font_family", "Optional font family name. OMIT IT unless you have a reason: the default is a family " +
                "the renderer always ships (it covers Latin script; other scripts fall back to the fonts the " +
                "renderer has for them), and a family it cannot find is REFUSED (the operation fails with a clear " +
                "error) rather than silently swapped for another face.",
            "font_size_percent", "Optional 1-20: caption size as a percent of the video HEIGHT, overriding the " +
                "style preset.",
            "position_percent", "Optional 0-100: distance from the TOP of the frame, overriding the style preset " +
                "(72 sits above centre, 89 near the bottom, 100 sits on the bottom edge). 0 means as high as the " +
                "line can sit and still be fully visible - captions are never placed off the frame.",
            "text_color/outline_color", "Optional hex colours like '#FFFFFF' / '#000000'. The outline is what " +
                "keeps captions readable over a bright shot - lightening it defeats that."
        ));
        result.put("constraints", ordered(
            "loop_vs_trim", "loop:true cannot be combined with trim_start_seconds/trim_end_seconds on the same " +
                "audio or track: extract the trimmed segment with a separate media node first, or drop one of the two.",
            "mix_length_anchor", "An audio-only mix cannot have loop:true on EVERY track (nothing anchors the " +
                "output length): keep at least one non-looping track, or provide a video.",
            "concat_fast_copy", "concat is near-instant and LOSSLESS (no re-encode) only when ALL of: every input " +
                "shares the same video codec, width, height, aspect and fps AND the same audio situation (all aac " +
                "with the same sample rate and channels, or none has audio); no clip has trims or speed != 1.0; " +
                "transition is 'cut'; fade_in_seconds and fade_out_seconds are 0; normalize is false. Anything " +
                "else re-encodes (h264 + aac mp4) - correct but slower, so prefer defaults when clips come from " +
                "the same source.",
            "concat_crossfade", "crossfade needs at least 2 inputs, and transition_seconds must be strictly " +
                "shorter than every clip's effective duration (after trim/speed) - the failure names the " +
                "offending clip index.",
            "concat_target_dims", "target_width and target_height come TOGETHER or not at all; without them the " +
                "first input defines the canvas and other clips are fitted with black padding, never stretched.",
            "frame_default_middle", "frame without at_seconds grabs the MIDDLE of the video; an at_seconds past " +
                "the end is clamped to just before the end (never an error) - read output.timestamp_seconds for " +
                "the timestamp actually used.",
            "subtitles_are_permanent", "subtitles burns the captions INTO the picture: they cannot be turned off, " +
                "restyled or translated afterwards. Caption LAST, once the cut is final, and keep the un-captioned " +
                "file if another language may be needed - re-running subtitles on a captioned video stacks a " +
                "second set of captions over the first.",
            "subtitles_ordering", "cues must be ascending and non-overlapping, and an overlap is refused rather " +
                "than reordered (overlapping cues would be drawn on top of each other).",
            "subtitles_font", "A font_family the renderer cannot find is REFUSED, not substituted: an unreadable " +
                "or wrong-looking caption track is a worse outcome than a failed run, and a substituted face would " +
                "come back as a perfectly successful video. Omit font_family to use the guaranteed default.",
            "budget", "Renders have a per-operation time budget and an input size limit on the renderer. A timeout " +
                "or too-large failure means: use shorter inputs (trim_start_seconds/trim_end_seconds) or smaller files. " +
                "A busy failure means: retry when fewer media operations run concurrently."
        ));
        result.put("examples", ordered(
            "1_simple_mux", "Soundtrack a rendered clip at 80% volume with a 2s fade-out: " +
                "workflow(action='add_node', type='media', label='Add Music', params={operation:'mux_audio', " +
                "video:'{{interface:card.output.video}}', audio:'{{core:download_track.output.file}}', volume:80, " +
                "fade_out_seconds:2}, connect_after='Card'). Then send {{core:add_music.output.file}} to any file param.",
            "2_duck_music_under_voice", "Voiceover over background music that automatically dips while the voice " +
                "speaks: workflow(action='add_node', type='media', label='Final Mix', params={operation:'mix', " +
                "video:'{{interface:card.output.video}}', tracks:[{id:'voice', source:'{{mcp:tts.output.file}}'}, " +
                "{id:'music', source:'{{core:download_track.output.file}}', volume:60, loop:true, duck_under:'voice'}]}, " +
                "connect_after='TTS'). The music track lowers by duck_amount_db (12dB) whenever the voice track is audible.",
            "3_probe_then_calibrated_tts", "Fit narration to a clip's exact length: (1) media probe on the video -> " +
                "{{core:probe_clip.output.duration_seconds}}; (2) a code node computes the word budget, e.g. " +
                "$output = {words: Math.floor(input_duration * 2.5)}; (3) the agent/TTS tool generates speech for " +
                "that budget; (4) media mux_audio puts the narration onto the video. The probe's flat fields " +
                "(has_audio, video.fps, ...) are all referencable the same way.",
            "4_compile_clips_with_crossfade", "Stitch three clips into one reel with soft transitions: " +
                "workflow(action='add_node', type='media', label='Compile Reel', params={operation:'concat', " +
                "inputs:[{source:'{{agent:intro.output.file}}'}, {source:'{{core:demo.output.file}}', " +
                "trim_end_seconds:12}, {source:'{{agent:outro.output.file}}'}], transition:'crossfade', " +
                "transition_seconds:0.5, fade_out_seconds:1}, connect_after='Outro'). Note the two prefixes: " +
                "Intro and Outro were made by generate nodes, which are AI nodes keyed agent:<label>, while Demo " +
                "came from a core node such as download_file. Each source carries the prefix of the node that " +
                "produced it, and the wrong one resolves to an empty string rather than failing. One input + " +
                "trims/speed = a simple cut-down edit of a single video.",
            "5_cover_frame", "Grab a cover image for a produced video (default = the middle, usually the best " +
                "shot): workflow(action='add_node', type='media', label='Cover', params={operation:'frame', " +
                "input:'{{core:compile_reel.output.file}}', width:1280}, connect_after='Compile Reel'). Then " +
                "{{core:cover.output.file}} is an image FileRef for thumbnails/posts and " +
                "{{core:cover.output.timestamp_seconds}} tells you which second was used.",
            "6_watermark", "Brand a video with a semi-transparent logo bottom-right: workflow(action='add_node', " +
                "type='media', label='Brand It', params={operation:'overlay', video:'{{core:compile_reel.output.file}}', " +
                "image:'{{core:download_logo.output.file}}', position:'bottom_right', margin_px:24, width_percent:12, " +
                "opacity:0.7}, connect_after='Compile Reel').",
            "7_burn_captions", "Caption a finished cut in the short-form style: workflow(action='add_node', " +
                "type='media', label='Caption It', params={operation:'subtitles', " +
                "video:'{{core:add_music.output.file}}', style:'tiktok', cues:[{start_seconds:0, end_seconds:2.4, " +
                "text:'Ninety metres below the surface'}, {start_seconds:2.6, end_seconds:5.2, " +
                "text:'nothing has moved for eight months'}]}, connect_after='Add Music'). Caption LAST, after the " +
                "cut and the audio are final: the captions become part of the picture. Time the cues from the " +
                "narration you actually burned in (media probe reports its real duration_seconds), not from the " +
                "script, or the captions drift out of sync with the voice."
        ));
        result.put("edges", "No ports. Exactly ONE incoming edge (like every utility node - validate rejects more). "
            + "To feed it from two branches (e.g. a video download AND an audio download), either chain them "
            + "(A -> B -> media: the file params reference ANY upstream node by template, not just the direct "
            + "predecessor) or join the branches with a merge node before it.");
        return result;
    }

    /**
     * Generate node help: how a model is chosen, what it costs, and the shape of
     * what comes back.
     */
    private Map<String, Object> getGenerateHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Generate Node (AI) - one asset from a prompt (image, video, audio, voice, music)");
        result.put("family", "An AI node, like agent, browser_agent, classify and guardrail: it is keyed "
            + "agent:<label> and its output is addressed as {{agent:<label>.output.file}}. A {{core:...}} "
            + "reference to it resolves to nothing, silently, because an unresolved template is an empty "
            + "string rather than an error.");
        result.put("model_first", "'model' is the only required param and it decides everything else: the "
            + "format produced, which other params are accepted, and the price. Model ids cannot be guessed, "
            + "so the ids are listed under 'models' in THIS payload - you already have them, no other call "
            + "is needed to build the node. Then workflow(action='add_node', type='generate', "
            + "label='Make Clip', params={model:'<id-from-that-list>', prompt:'...'}, connect_after='...'). "
            + "The generation tool, if you have it, answers the same list; it is opt-in per agent because "
            + "CREATING spends credits, so do not assume you hold it and never tell someone to run it. "
            + "Building the node needs only this list: each row carries what the model accepts, the limits "
            + "on those params, what its file slots are for, and what it costs.");
        // The ids, from the live catalogue, in the payload the builder already
        // reads. Discovery is free; only creating spends. Before this, the ids
        // existed solely behind the opt-in generation tool, so an agent without
        // it could not name a single model and had no way to say why.
        com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.ModelCatalogue catalogue = generationExecutionService.readModels();
        List<Map<String, Object>> models = catalogue.models();
        if (models.isEmpty()) {
            // The two empties are different facts and only one of them is about
            // the installation. Telling someone to abandon the feature on the
            // strength of a network blip is the more expensive mistake.
            // Read from the SAME answer, not a second call: the served flag used
            // to live on the service as shared mutable state, so a concurrent
            // request's transport blip could be read here as this one's result
            // and produce the confident, wrong "served but empty" sentence.
            Boolean served = catalogue.served();
            String why;
            if (Boolean.FALSE.equals(served)) {
                why = "This installation does not serve generation at all, so the generate node cannot "
                    + "work here. Say so rather than guessing a model id: only an administrator can "
                    + "change it.";
            } else if (Boolean.TRUE.equals(served)) {
                // Served, and its catalogue is genuinely empty: a real state, and
                // an administrator's to fix by seeding it.
                why = "No generation model is available on this installation: generation is served but "
                    + "its catalogue is empty. The generate node cannot work until one is seeded, which "
                    + "only an administrator can do. Say so rather than guessing an id.";
            } else {
                why = "The model catalogue could not be read just now, so no id can be offered. This "
                    + "says NOTHING about whether generation works here: do not tell anyone the feature "
                    + "is unavailable. Ask again, and report that the catalogue is unreachable if it "
                    + "keeps failing.";
            }
            result.put("models", why);
        } else {
            List<Map<String, Object>> rows = new ArrayList<>(models.size());
            for (Map<String, Object> m : models) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("model", m.get("model"));
                row.put("kind", m.get("kind"));
                row.put("label", m.get("label"));
                row.put("provider", m.get("provider"));
                // What it TAKES and what it INSISTS on: enough to write the
                // params of a valid node without a second call.
                row.put("accepts", m.get("accepts"));
                row.put("required", m.get("required"));
                // Which param the price multiplies, so the cost of a longer
                // request is predictable before anyone runs it.
                row.put("billed_on", m.get("billedOn"));
                // The half that decides whether this model is runnable AT ALL
                // on this installation, and it differs cloud versus self-hosted.
                Object runsOn = m.get("runsOn");
                row.put("runs_on", runsOn == null ? "unknown" : String.valueOf(runsOn));
                // The three things the BUILDER shows on this same model that this
                // payload used to withhold, sending the reader to a tool that is
                // opt-in and that most agents do not hold:
                //   * limits - the allowed values and the min/max of each param.
                //     Without them a param with a closed vocabulary can only be
                //     guessed at, and while a wrong guess is refused for free, the
                //     refusal is a round trip that the builder never has to make.
                //   * inputs - what each FILE slot is FOR and how many it takes.
                //     "input_image" does not say whether the image comes back
                //     changed, becomes a first frame, or only lends its style.
                //   * price - the rate. Stating what a run will cost before it runs
                //     is not a nicety here: the amount is charged on success and
                //     scales with a param the caller chooses.
                // 59 models on the reference catalogue, so this is a readable
                // payload rather than a large one; if that ever stops being true
                // the answer is to let the reader ask for ONE model, not to go back
                // to describing a model two different ways on two surfaces.
                if (m.get("limits") != null) row.put("limits", m.get("limits"));
                if (m.get("inputs") != null) row.put("inputs", m.get("inputs"));
                if (m.get("price") != null) row.put("price", m.get("price"));
                rows.add(row);
            }
            result.put("models", rows);
            result.put("credential_default", "A generate node left unstated runs on the PLATFORM key: "
                + "the node substitutes it, so there is no try-the-owner's-key-first fallback here (the "
                + "generation tool's own create does have one; the node does not). 'runs_on' is therefore "
                + "the field to read before writing the node: 'platform_or_own_key' means the platform "
                + "resells this provider on this installation and an unstated node works; 'own_key_only' "
                + "means it does not, and the node needs credential_source:'user' plus a key the owner "
                + "has connected - if they have not, say so instead of building a node that fails on its "
                + "first run. A third value, 'unknown', means the check itself did not answer: make no "
                + "claim either way, and say the payer could not be confirmed rather than guessing. "
                + "'platform_or_own_key' means a platform key exists AND a price is published, which is "
                + "what billing requires; a run can still be refused if the model is priced in a unit "
                + "the call cannot be measured in.");
            result.put("models_note", "Every generation model this installation offers, by kind. "
                + "'accepts' is the exact set of params that model takes: anything else is refused before "
                + "the provider is called, at no cost. 'limits' gives the allowed values and the min/max "
                + "of each of those params, 'inputs' says what each file slot is for and how many files it "
                + "takes, and 'price' is the starting rate (unit, unitCredits, baseCredits, and a floor or "
                + "ceiling where the model has one). That is everything you need to write a valid node and "
                + "to say what it will cost, from this payload alone. Two things inside 'limits' change "
                + "what you should DO with a value. allowedEnforced:false means the listed values are what "
                + "the provider documents and nothing checks them, so one outside the list reaches the "
                + "provider and fails there: treat that list as a suggestion and confirm the value with "
                + "the person. optionsAvailable:true means there is no fixed list at all: those values "
                + "belong to the provider ACCOUNT whose key runs the node, so the voices or presets one "
                + "key can use are not the ones another can, and no list written here could be true for "
                + "two readers. Ask the person for that value rather than inventing one: a wrong id is "
                + "opaque and is only refused after the generation has been paid for.");
        }
        result.put("params", ordered(
            "model", "REQUIRED. One of the ids under 'models' in this payload.",
            "prompt", "What to generate. Required by every model that accepts it.",
            "duration_seconds", "Length for video, music and speech. Models priced per second bill on this.",
            "aspect_ratio", "Framing, e.g. '16:9'. Only the values in that model's limits.",
            "resolution", "Output size, e.g. '720p'. Only the values in that model's limits.",
            "voice", "Voice id for speech synthesis.",
            "language", "Language code for speech synthesis.",
            "quality", "Provider quality tier. Only the values in that model's limits.",
            "style", "Provider style preset.",
            "seed", "Seed for reproducible output.",
            "negative_prompt", "What to avoid.",
            "input_image / input_audio / input_video", "A whole FileRef from an upstream node, used as a "
                + "reference or a first frame, e.g. '{{core:download.output.file}}' from a core node or "
                + "'{{agent:make_cover.output.file}}' from another generate node - never .path, never a URL. "
                + "What the file is FOR is the 'role' under that model's 'inputs' row, and it is not the "
                + "same on every model: an image can be the first frame of the clip, the picture that comes "
                + "back edited, or only a style reference. Where that row says maxItems above 1 the model "
                + "takes SEVERAL files here: send a LIST of whole FileRefs and their order is the order the "
                + "provider reads them in. A list longer than maxItems is refused before the provider is "
                + "called, at no cost, and so is a list where any one file cannot be read (never partly "
                + "sent, so you are never charged for a request you did not make).",
            "first_frame_image / last_frame_image / reference_image", "The slots of a model that takes "
                + "SEVERAL files in one call, named by what each file IS: the still the clip opens on, the "
                + "one it lands on, and files it borrows a subject or a style from without ever showing "
                + "them. Same shape as input_image, one whole FileRef each (reference_image takes a LIST "
                + "where its 'inputs' row says maxItems above 1). A model shows the ones it has in that "
                + "'inputs' row, and only those: send a slot it does not list and the run is refused, at no "
                + "cost. They are not interchangeable, and filling the wrong one is a different video that "
                + "you have paid for. That row also carries two rules, when the provider has them: "
                + "'requires' names slots this one must be sent WITH (a model that pins both ends of a clip "
                + "refuses one frame on its own) and 'excludes' names slots it may never be sent with "
                + "(pinning a frame and lending a reference are, for some providers, two different kinds of "
                + "request). Both are refused before anything is charged.",
            "credential_source", "'platform' = the platform's provider key, billed at the platform "
                + "price. 'user' = a key the owner configured themselves, billed nothing by the platform. "
                + "UNSTATED MEANS 'platform' for this node: it is substituted before the run, so leaving "
                + "it out is a choice of payer, not a fallback. Read 'runs_on' in the models list before "
                + "you decide: where it says own_key_only the platform does not resell that provider "
                + "here, and a node left unstated - or set to 'platform' - fails on its first run. Set "
                + "credential_source:'user' for those, and only if the owner has connected a key.",
            "credential_id", "WHICH of the owner's own provider keys this node runs on, when they hold "
                + "several for the same provider. Only meaningful beside credential_source:'user'. You have "
                + "no way to look one up: the ids belong to the owner's account and no action here lists "
                + "them, so the owner sets this in the builder. add_node and modify both ACCEPT it, which is "
                + "what lets you carry one across a rewrite - copy it unchanged when you rewrite a node that "
                + "already has one, and never invent a number. A "
                + "node without it runs on the owner's default key for that provider, which is the right "
                + "answer for every node you create."
        ));
        result.put("accepted_params", "A model accepts only the params listed in its 'accepts' row. Sending "
            + "one it does not accept is refused with the accepted list BEFORE the provider is called, so a "
            + "rejected call costs nothing: correct the param and run again. A value outside a limit is "
            + "refused the same way, EXCEPT where that limit carries allowedEnforced=false: those values "
            + "are what the provider documents, nothing checks them, and one outside the list reaches the "
            + "provider and fails there. Treat those as a suggestion and confirm the value with the person "
            + "rather than trying others.");
        result.put("price", "Every successful run is charged. 1 credit = $0.001. A model is priced per call, "
            + "per second, per image or per character, so a longer request costs more: a per-second video "
            + "model at 60 credits/second costs 600 credits for a 10 second clip. The node reports what it "
            + "actually billed on as output.billed_quantity, counted in output.billed_unit. That unit is the "
            + "one the size was MEASURED in, which is not always the unit the rate is quoted in: a model "
            + "listed per minute reports seconds. What the run COST is reported directly as "
            + "output.billed_credits, so read that rather than multiplying a rate by a size: the rate can be "
            + "republished between the run and the reading. It is absent when the platform charged nothing, "
            + "which is not a charge of zero. The size billed is derived "
            + "from the request, and it is the size ASKED FOR rather than the size delivered: a provider "
            + "that clamps a long request to its own maximum still charges what you sent, so set the "
            + "length you need rather than the ceiling. It is not a param and setting one does not change "
            + "it. With "
            + "credential_source='user' the platform bills nothing and you pay the provider directly; "
            + "unstated means 'platform'.");
        result.put("outputs", ordered(
            "file", "The generated asset as a whole FileRef, stored so it outlives the provider's own link. "
                + "Map the WHOLE object into a downstream file param: '{{agent:make_clip.output.file}}'.",
            "model", "The model id that ran.",
            "kind", "The format produced: image, video, audio, voice, music, ...",
            "provider", "The provider whose model ran.",
            "billed_quantity", "The size billed on, counted in billed_unit. A model sold per call reports 1.",
            "billed_unit", "call, second, image or character. It is the unit the size was MEASURED in, not "
                + "always the one the rate is quoted in: a model listed per minute reports seconds here.",
            "billed_credits", "What the platform charged for this run, in credits, as it was committed. "
                + "ABSENT means the platform charged nothing (credential_source='user' pays the provider "
                + "directly), which is not a charge of zero.",
            "provider_response", "The provider's own payload, kept under its own key so a provider field can "
                + "never shadow file. Its shape varies by provider; do not build on it."
        ));
        result.put("failure", "The node FAILS when no asset comes back, rather than continuing with an empty "
            + "file: the call has already been charged by then, and a downstream node running on nothing "
            + "would hide that. A long job (a model listed as async) is waited for; the node never hands "
            + "back a job id to poll.");
        result.put("recipes", ordered(
            "video_clip", "type='generate', label='Make Clip', params={model:'<a video model id>', "
                + "prompt:'a paper boat drifting down a rain gutter, cinematic', duration_seconds:5, "
                + "aspect_ratio:'16:9'}, connect_after='Start'.",
            "voice_over", "type='generate', label='Say It', params={model:'<a voice model id>', "
                + "prompt:'Welcome aboard. Please fasten your seatbelt.', voice:'<a voice id from limits>'}. "
                + "Priced per character, so the prompt's length is the cost.",
            "generate_then_edit", "Chain into core:media: 'Make Clip' -> 'Say It' -> 'Add Voice' with "
                + "params={operation:'mux_audio', video:'{{agent:make_clip.output.file}}', "
                + "audio:'{{agent:say_it.output.file}}'}. The two generate nodes are addressed with "
                + "agent:, the media node with core: - the prefix follows the node, not the workflow.",
            "own_key", "Add credential_source:'user' to run on a provider key you configured yourself; the "
                + "platform then bills nothing for that node."
        ));
        result.put("edges", "No ports. Exactly ONE incoming edge (like every utility node - validate rejects "
            + "more). It can reference ANY upstream node by template, not just its direct predecessor.");
        return result;
    }

    private Map<String, Object> getEdgeHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Edge Format - Connecting Nodes");
        result.put("format", ordered(
            "simple", "{ from: 'trigger:start', to: 'mcp:step1' }",
            "decision_if", "{ from: 'core:check:if', to: 'mcp:success' }",
            "decision_else", "{ from: 'core:check:else', to: 'mcp:failure' }",
            "loop_body", "{ from: 'core:loop:body', to: 'mcp:iterate' }",
            "loop_exit", "{ from: 'core:loop:exit', to: 'mcp:after_loop' }",
            "fork_branch", "{ from: 'core:parallel:branch_0', to: 'mcp:task_a' }"
        ));
        result.put("rules", ordered(
            "key_format", "prefix:normalized_label - label is lowercased, spaces/special chars become _. Example: 'Check Status' → core:check_status",
            "merge_edges", "Multiple edges can point to the same target (implicit merge). Example: mcp:task_a → mcp:final AND mcp:task_b → mcp:final. The target waits for ALL predecessors.",
            "one_port_one_target", "Each NAMED port (decision if/else, switch case_N, loop body/exit, fork branch_N, option choice_N, approval approved/rejected/timeout, classify category_N, guardrail pass/fail) connects to AT MOST ONE target. A second connect from the same port is rejected - to fan one port out to several nodes in parallel, add a fork on that port and connect each branch separately.",
            "builder", "Use workflow(action='connect', from='Source Label', to='Target Label') - labels are auto-normalized. For ports: from='Decision Label:if'",
            "conditions", "Stored in cores[] definition, NOT on edges. Edges only carry port references.",
            "mail_account", "send_email and email_inbox use the default account only when credentialId is omitted. An unavailable selected account fails without using another mailbox. Relay the refusal to the user; do not remove the selection to retry with another account."
        ));
        result.put("ports_by_node_type", ordered(
            "decision", "if, else, elseif_0, elseif_1, ... - Example: core:check:if, core:check:else, core:check:elseif_0",
            "switch", "case_0, case_1, ..., default - Example: core:route:case_0, core:route:default",
            "loop", "body, exit - Example: core:retry:body, core:retry:exit",
            "fork", "branch_0, branch_1, ... - Example: core:parallel:branch_0",
            "option", "choice_0, choice_1, ... - Example: core:pick:choice_0",
            "approval", "approved, rejected, timeout - Example: core:review:approved",
            "guardrail", "pass, fail - Example: agent:safety:pass, agent:safety:fail",
            "classify", "category_0, category_1, ... - Example: agent:router:category_0",
            "no_port_nodes", "split, merge, transform, wait, exit, stop_on_error, response, aggregate, http_request, download_file, public_link, media, generate (agent:), data_input, code, filter, sort, limit, remove_duplicates, summarize, date_time, crypto_jwt, xml, compression, rss, convert_to_file, extract_from_file, compare_datasets, sub_workflow, respond_to_webhook, send_email, email_inbox, set, html_extract, task, ssh, sftp, database - NO ports, single outgoing edge"
        ));
        return result;
    }

    /**
     * Get combined help for Fork and Merge patterns.
     */
    private Map<String, Object> getForkMergeHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Fork and Merge - Parallel Execution");

        Optional<NodeTypeDocumentationEntity> forkOpt = nodeLibraryService.findByType("fork");
        Optional<NodeTypeDocumentationEntity> mergeOpt = nodeLibraryService.findByType("merge");

        if (forkOpt.isPresent()) {
            result.put("fork", nodeHelpFormatter.formatNodeHelp(forkOpt.get()));
        }
        if (mergeOpt.isPresent()) {
            result.put("merge", nodeHelpFormatter.formatNodeHelp(mergeOpt.get()));
        }

        result.put("pattern", ordered(
            "fork", "1 node → N parallel streams (ALL execute). Ports: branch_0, branch_1, ...",
            "merge", "N streams → 1 (waits for ALL predecessors - completed OR skipped count as resolved)",
            "vs_decision", "Fork=ALL branches execute, Decision=ONE branch executes",
            "implicit_fork", "A PORT-LESS node (trigger or plain step) with multiple outgoing edges = implicit fork (all targets execute in parallel). A node's NAMED port connects to exactly one target - to fan a single port out, add a fork on it.",
            "implicit_merge", "Any node with multiple incoming edges = implicit merge (waits for all predecessors). If EVERY predecessor ends SKIPPED the merge is SKIPPED too, and the skip cascades to every node after it"
        ));

        result.put("builder_example", ordered(
            "step_1", "workflow(action='add_node', type='fork', label='Parallel', params={branches: ['Email', 'SMS']}, connect_after='Start')",
            "step_2a", "workflow(action='add_node', type='<email-tool-uuid>', label='Send Email', params={...}) - then connect: from='Parallel:branch_0', to='Send Email'",
            "step_2b", "workflow(action='add_node', type='<sms-tool-uuid>', label='Send SMS', params={...}) - then connect: from='Parallel:branch_1', to='Send SMS'",
            "step_3", "workflow(action='add_node', type='merge', label='Wait All', connect_after='Send Email') - then also connect: from='Send SMS', to='Wait All'",
            "step_4", "workflow(action='add_node', type='...', label='Next Step', connect_after='Wait All')"
        ));

        return result;
    }

    /**
     * Get combined help for CRUD table operations.
     */
    private Map<String, Object> getCrudHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Table CRUD Operations");
        result.put("description", "Database operations for tables: insert, read, update, delete");
        result.put("note", "Builder action 'read_rows' maps to DB type 'get_rows'. Both work in set_plan.");
        result.put("param_format", "FLAT params at top level - table_id, columns, set, where directly in params, NOT nested under 'crud'. " +
            "Example: workflow(action='add_node', type='insert_row', label='Save', params={table_id: 123, columns: {name: 'X'}}). " +
            "Nested {crud: {table_id, columns}} is accepted for backward compat (auto-flattened, top-level keys win on conflict).");
        result.put("required_params_per_op", Map.of(
            "insert_row", "table_id (int) + columns (object)",
            "update_row", "table_id (int) + set (object) + where (object)",
            "delete_row", "table_id (int) + where (object)",
            "get_rows",   "table_id (int) - where/limit/offset/order_by optional",
            "find_rows",  "table_id (int) [aliases: dataSourceId, datasource_id] - crud.where/crud.limit optional"
                + ", params.similarity={column, queryVector, topK?, threshold?} for vector search (RAG)"
        ));

        for (String type : List.of("insert_row", "update_row", "delete_row")) {
            nodeLibraryService.findByType(type)
                .ifPresent(node -> result.put(type, nodeHelpFormatter.formatNodeHelp(node)));
        }
        // read_rows: DB type is 'get_rows', but builder action is 'read_rows' (aliases: get_rows, fetch_rows)
        nodeLibraryService.findByType("get_rows")
            .ifPresent(node -> result.put("read_rows", nodeHelpFormatter.formatNodeHelp(node)));

        // FindNode documentation (hybrid CRUD + split node) - static, more complete than DB entry
        result.put("find_rows", getFindNodeHelp());

        return result;
    }

    /**
     * Get help documentation for the FindNode (crud-find).
     * FindNode queries a table and returns rows as items[] array (no split/spawn).
     */
    private Map<String, Object> getFindNodeHelp() {
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("title", "Find Rows (crud-find) - Table Query → items[] Array");
        help.put("description",
            "FindNode queries a data table and returns matching rows as an items[] array. " +
            "This is a simple collection node - it does NOT split or spawn parallel contexts. " +
            "To iterate per-row, connect a Split node after this node.");
        help.put("prefix", "table:");
        help.put("type_field", "crud-find");
        // Similarity is documented unconditionally since 2026-09-03. It used to be described only
        // on self-hosted, because vector search was a property of the DEPLOYMENT and this help
        // could answer that. It is now a property of the WORKSPACE'S PLAN, which this help is
        // built without: hiding the pattern from everyone to spare the workspaces that cannot use
        // it would also hide it from the ones paying for it. A run that lacks the plan is refused
        // at the step, with the plan named.
        help.put("param_format", "dataSourceId at top level. where/limit go in 'crud' block. "
            + "similarity goes in 'params' block. Example: {type:'crud-find', label:'X', dataSourceId:6, "
            + "crud:{where:{...}, limit:5}, params:{similarity:{column:'embedding', queryVector:'{{...}}', topK:5}}}");

        // Parameters
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dataSourceId", Map.of("type", "number", "required", true, "description", "Target datasource ID (top-level field)"));
        params.put("label", Map.of("type", "string", "required", true, "description", "Node label (normalized to table:label)"));
        params.put("crud.where", Map.of("type", "object", "required", false,
            "description", "Filter condition inside crud block: {column, operator, value}",
            "example", Map.of("column", "status", "operator", "=", "value", "active")));
        params.put("crud.limit", Map.of("type", "integer", "required", false, "default", 100,
            "description", "Maximum number of rows to return (inside crud block)"));
        params.put("params.similarity", Map.of("type", "object", "required", false,
            "description", "Vector similarity search (RAG) - MUST be inside 'params' block. " +
                "Fields: column (vector column name), queryVector (template expression e.g. {{mcp:embed.output.data[0].embedding}}), " +
                "topK (max results, default 5), threshold (min similarity score, optional). " +
                "Can combine with crud.where for hybrid filtering. " +
                "On managed cloud this is a paid capability: a workspace whose plan does not include it " +
                "gets the step refused with a message naming the plan that does.",
            "example", Map.of("column", "embedding", "queryVector", "{{mcp:embed_query.output.data[0].embedding}}", "topK", 5)));
        help.put("parameters", params);

        // Outputs
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("items", "array - All found rows (after limit). Use Split to iterate. A cell from a file or image column is the file OBJECT {_type:'file', id, path, url, name, mimeType, size}: map the WHOLE cell into a parameter that takes a file, never one field of it; path and url are each present only when the file has one.");
        outputs.put("item_count", "number - Number of items found");
        outputs.put("total_before_limit", "number - Total rows before limit cap");
        outputs.put("has_more", "boolean - Whether more rows exist beyond the limit");
        outputs.put("exit_reason", "string - 'items_found' or 'empty_result'");
        outputs.put("find_id", "string - Node ID");
        outputs.put("max_items", "number - Configured limit cap");
        help.put("outputs", outputs);

        // Behavior
        help.put("behavior", Map.of(
            "execution", "Queries the datasource and returns all matching rows as items[] array",
            "successors", "Execute once with the full items[] array - NOT per-item",
            "empty_result", "If no rows found, successors still execute with items: []",
            "pagination", "limit caps the number of rows returned; has_more indicates if more exist",
            "iteration", "To iterate per-row: connect a Split node after FindNode with list={{table:label.output.items}}"
        ));

        // Edge format
        help.put("edge_format", Map.of(
            "entry", "{ \"from\": \"trigger:start\", \"to\": \"table:find_users\" }",
            "successor", "{ \"from\": \"table:find_users\", \"to\": \"core:split_rows\" }",
            "note", "Successors execute once with the full items[] array"
        ));

        // Example: Find → Split → Process
        help.put("example_plan_json", Map.of(
            "tables", List.of(Map.of(
                "type", "crud-find",
                "label", "Find Active Users",
                "dataSourceId", 123,
                "crud", Map.of(
                    "where", Map.of("column", "status", "operator", "=", "value", "active"),
                    "limit", 50
                )
            )),
            "cores", List.of(Map.of(
                "type", "split",
                "label", "Each User",
                "list", "{{table:find_active_users.output.items}}"
            )),
            "edges", List.of(
                Map.of("from", "trigger:start", "to", "table:find_active_users"),
                Map.of("from", "table:find_active_users", "to", "core:each_user"),
                Map.of("from", "core:each_user", "to", "mcp:send_email")
            ),
            "note", "Find returns items[], Split iterates per-row. mcp:send_email runs once per user with {{item}}."
        ));

        // RAG example: Embed -> Find with similarity -> Agent. Advertised everywhere since
        // 2026-09-03: managed cloud runs it too, from the plan the admin set on
        // feature:vector_search. A workspace below that bar has the step refused with the plan
        // named, which is a better answer than never being shown the pattern.
        {
            help.put("example_rag_plan", Map.of(
                "description", "RAG pattern: embed user query → similarity search → agent generates answer from context",
                "mcps", List.of(Map.of(
                    "label", "Embed Query",
                    "id", "openai/openai-create-embedding",
                    "params", Map.of("input", "{{trigger:chat.data.message}}", "model", "text-embedding-ada-002")
                )),
                "tables", List.of(Map.of(
                    "type", "crud-find",
                    "label", "Search Knowledge Base",
                    "dataSourceId", 42,
                    "crud", Map.of("limit", 5),
                    "params", Map.of("similarity", Map.of(
                        "column", "embedding",
                        "queryVector", "{{mcp:embed_query.output.data[0].embedding}}",
                        "topK", 5
                    ))
                )),
                "agents", List.of(Map.of(
                    "label", "Answer",
                    "prompt", "Answer the user question using the context below:\\n{{table:search_knowledge_base.output.items}}"
                )),
                "edges", List.of(
                    Map.of("from", "trigger:chat", "to", "mcp:embed_query"),
                    Map.of("from", "mcp:embed_query", "to", "table:search_knowledge_base"),
                    Map.of("from", "table:search_knowledge_base", "to", "agent:answer")
                )
            ));
        }

        // vs Split comparison
        help.put("vs_split", Map.of(
            "find_rows", "Queries a database table → returns items[] array (no iteration)",
            "split", "Takes a list expression → spawns N parallel contexts with {{item}} and {{index}}",
            "composable", "Use FindNode → Split together: find collects rows, split iterates"
        ));

        return help;
    }

    /**
     * Help for inspecting past workflow runs (action='runs', action='get_run').
     */
    private Map<String, Object> getRunsHelp() {
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("title", "Inspect Workflow Runs");
        help.put("description", "View past and current workflow executions, their status, and detailed node-by-node results.");

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("1_list_runs", ordered(
            "syntax", "workflow(action='runs', workflow_id='uuid')",
            "zoom_level", "Widest - list of recent runs for a workflow.",
            "returns", "Per run: run_id, status, plan_version, started_at, ended_at, duration_ms, total_nodes, execution_mode",
            "next", "Pick a run_id and call get_run for the macro overview."
        ));
        actions.put("2_get_run_overview", ordered(
            "syntax", "workflow(action='get_run', run_id='uuid')",
            "zoom_level", "Run-level - macro overview: epoch summaries + DAG counters, no per-node detail.",
            "returns", "{run_id, status, plan_version, dags:{<trigger_id>:{current_epoch,fire_count,current_spawn}}, epochs:[{epoch,trigger_id,started_at,ended_at,duration_ms,node_counts:{completed,failed,partial_failed,skipped},status}], total_epochs}",
            "next", "Pick an epoch number from epochs[] and call get_run with epoch=N for the per-node breakdown."
        ));
        actions.put("3_get_run_epoch_detail", ordered(
            "syntax", "workflow(action='get_run', run_id='uuid', epoch=N)",
            "zoom_level", "Epoch-level - every node executed in this epoch with its node-level status and per-item counts.",
            "returns", "{run_id, epoch, status, nodes: [{node_id, label, type, status, execution_count, status_counts:{completed,failed,skipped,running,...}, error}]} - execution_count is the number of persisted rows for this node in this epoch (1 for normal nodes, N for split fan-out / loop iterations / spawn re-runs); status_counts is omitted when execution_count == 1.",
            "key_insight", "When status_counts is present, the node-level status (e.g. COMPLETED) hides per-item disparity. A classify node downstream of a split with status=COMPLETED + status_counts={completed:0,skipped:32} produced no real output - call get_node_output to see what each item did.",
            "next", "For one node, call get_node_output (use item_index/iteration/spawn to zoom into one row when execution_count > 1)."
        ));
        actions.put("4_get_node_output", ordered(
            "syntax", "workflow(action='get_node_output', run_id='uuid', epoch=N, node_id='core:my_node'[, item_index=I][, iteration=K][, spawn=S][, field='F'][, offset=O][, max_bytes=B])",
            "zoom_level", "Deepest - full input/output/error for ONE row in ONE epoch.",
            "returns_list_mode", "When the node has multiple persisted rows for this epoch and no filter is passed: {run_id, epoch, node_id, label, type, status, execution_count, status_counts, items:[{item_index, iteration?, spawn?, item_id?, item_number?, status, error?, skip_reason?, selected_branch?, condition_result?, loop_iteration?, started_at, ended_at, duration_ms}]} - items[] is sorted by (item_index, iteration, spawn). NO output blob in list mode (call again with a filter to fetch one).",
            "returns_zoom_mode", "When only one row exists, OR when item_index/iteration/spawn filters select exactly one row: {run_id, epoch, node_id, label, type, status (node-level), item_status (per-row), item_index, iteration?, spawn?, item_id?, item_number?, node_type?, tool_id?, http_status?, output, resolved_params, error?, selected_branch?, condition_expression?, condition_result?, loop_id?, loop_iteration?, loop_exit_reason?, merge_strategy?, merge_received_branches?, merge_skipped_branches?, skip_reason?, skip_source_node?, started_at, ended_at, duration_ms}",
            "filter_miss", "If item_index / iteration / spawn don't match any row: {note, execution_count, status_counts} so you can re-call without the filter.",
            "params", "run_id (required), epoch (required int), node_id (required - use exact node_id from get_run epoch detail), item_index (optional int - pick one item from a split fan-out), iteration (optional int - pick one loop iteration), spawn (optional int - pick one re-run), field (optional - name of a text output field to read in full), offset (optional int - byte offset to expand a field from), max_bytes (optional int - field window size, default & cap 128 KB). Filters are combinable.",
            "expand_large_output", "Text output fields larger than 128 KB come back as a {truncated:true, original_length, preview} stub carrying a NEXT pointer. To read the full value, follow that NEXT - it sets field=<the field's dot-path, e.g. 'output.image' for a nested field> plus the SAME item_index/iteration/spawn, and returns {field, content, offset, returned_bytes, original_length, truncated, NEXT}. Keep following NEXT (each carries the next offset) to page through the whole field. Same 128 KB cap + truncated/original_length vocabulary as the files tool.",
            "field_meanings", ordered(
                "status", "Node-level aggregated status from EpochState (COMPLETED if the node finished its work, regardless of how many items succeeded).",
                "item_status", "Per-row status (COMPLETED, FAILED, SKIPPED) for the specific item/iteration/spawn this zoom resolved to. Disagreement with `status` is expected - a classify node can be COMPLETED at the node level while every item is SKIPPED.",
                "selected_branch", "For decision/classify nodes: the branch (port) this row routed to (e.g. 'category_promotions', 'if', 'else').",
                "condition_expression / condition_result", "For decision nodes: the SpEL expression and its boolean result for this row.",
                "loop_iteration / loop_exit_reason", "For nodes inside a loop: which iteration this is (1-based) and why the loop exited (MAX_ITERATIONS, CONDITION_MET, etc.).",
                "merge_*", "For merge nodes: the strategy used and the lists of branches that contributed (received) vs. were skipped.",
                "skip_reason / skip_source_node", "For SKIPPED rows: why the row was skipped and which upstream node propagated the skip.",
                "resolved_params.list / resolved_params.listResolved", "For split and find nodes: `list` is the expression as configured, `listResolved` is what it evaluated to, described in one line (e.g. 'List(size=0)', 'Map(keys=[items])', 'null'). Read it whenever the node produced 0 items: 'List(size=0)' means the upstream node really returned an empty array, while 'Map(keys=[items])' means the reference points at the object WRAPPING the array - re-point it one level deeper. On a find whose table served the rows, listResolved reads '(not evaluated: the table returned rows)'.",
                "resolved_params.variableMapping", "For interface nodes: one entry per template variable - {expression, resolved, status}. `status` is 'resolved' (it held a value when the node ran), 'unresolved' (it held nothing) or 'not_evaluated' (nothing measured it, e.g. the render's variable byte budget was exhausted before this one; resolved_params.variableMappingError says why). Read it when the page renders empty: a variable whose `resolved` is 'Map(keys=[result])' means the template must read one level deeper, and a mapping of '{{core:<code node>.output}}' always carries that extra `result` wrapper - map '{{core:<code node>.output.result}}' instead. A variable fed by a node that runs AFTER the interface reports 'unresolved' here and still displays once that node has run. A variable mapped from a WORKSPACE variable ({{$vars.x}}) reports '<withheld: workspace variable>' instead of its value, since one can be declared secret - its expression and its status still tell you whether the wiring works.",
                "resolved_params values that are not the value", "Two strings can appear anywhere in resolved_params, on any node, and both are also what `{{core:<label>.input.<key>}}` returns for that key. '<withheld: credential>' means the key's NAME says it holds a credential (token, apiKey, password, connectionString, a qualified key like accessToken) - the parameter ran with its real value, only the report hides it, so do not read it back into another node's parameters. '<withheld: workspace variable>' means the value came from a {{$vars.x}} workspace variable, same rule. A key named `paramsTruncated` is not a parameter at all: it says how many entries were dropped because the whole map exceeded its size budget, and the entries that survived are the ones the node reported first."
            )
        ));
        help.put("actions", actions);

        help.put("usage_pattern",
            "JIT zoom/dezoom: start wide, zoom in only when needed. " +
            "(1) runs → pick run_id. " +
            "(2) get_run (no epoch) → list of nodes + statuses. " +
            "(3a) get_node_output → drill into ONE node (preferred when you know which one). " +
            "(3b) get_run with epoch=N → drill into ALL nodes of an epoch (expensive, use only if you need everything). " +
            "Never fetch epoch detail just to read one node - use get_node_output instead.");
        help.put("status_values", "COMPLETED | FAILED | RUNNING | PAUSED | WAITING_TRIGGER | CANCELLED | TIMEOUT. A run is never PARTIAL_SUCCESS: any failed node makes it FAILED, and the failing nodes carry the detail. A run whose trigger has not fired yet stays WAITING_TRIGGER. Runs that finished before this rule may still report a stored PARTIAL_SUCCESS.");

        help.put("examples", ordered(
            "list_all", "workflow(action='runs', workflow_id='<uuid>')",
            "overview", "workflow(action='get_run', run_id='<uuid>')",
            "epoch_detail", "workflow(action='get_run', run_id='<uuid>', epoch=1)",
            "single_node", "workflow(action='get_node_output', run_id='<uuid>', epoch=1, node_id='core:build_html')",
            "split_node_list", "workflow(action='get_node_output', run_id='<uuid>', epoch=1, node_id='agent:classify') - returns items[] when the node ran across multiple split items.",
            "split_node_zoom", "workflow(action='get_node_output', run_id='<uuid>', epoch=1, node_id='agent:classify', item_index=2) - drills into one specific item.",
            "loop_iteration", "workflow(action='get_node_output', run_id='<uuid>', epoch=1, node_id='mcp:retry_call', iteration=3) - picks iteration 3 of an enclosing loop.",
            "expand_large_field", "workflow(action='get_node_output', run_id='<uuid>', epoch=1, node_id='agent:writer', field='agent_response', offset=131072) - page past the 128 KB preview to read the rest of a large text output field; follow the returned NEXT to continue.",
            "after_execute", "After workflow(action='execute') returns run_id, call get_run for the macro overview, then get_run with epoch=N for the per-node statuses, then get_node_output on each node you actually need."
        ));

        return help;
    }

    /**
     * Help for workflow(action='pin' / 'unpin') - production version promotion.
     */
    private Map<String, Object> getPinHelp() {
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("title", "Pin / Unpin - Production Version Promotion");
        help.put("description",
            "Each time a workflow is saved, a new plan version is recorded. Production triggers " +
            "(webhook, schedule, workflow(action='execute', version='pinned')) fire ONLY the pinned " +
            "version - never the latest draft. Pinning is explicit: no auto-pin happens on first run. " +
            "A version you have never executed can be pinned: pin prepares the production run that " +
            "fires accumulate into, so there is no execute-then-pin sequence to perform.");

        help.put("interactive_chat_authorization",
            "IMPORTANT (interactive chat only): pin and unpin are sensitive actions and need the "
            + "user's authorization, because pinning hands a version every trigger its plan declares "
            + "and unpinning takes them all off the air. Asking them happens inside your call: it may "
            + "simply take longer to answer while they decide. Do NOT stop, do NOT announce that you "
            + "are waiting, and do NOT re-call pin - just read what comes back. A normal result "
            + "(workflow_id, pinned_version, is_production, status) means they allowed it and it took "
            + "effect; "
            + "{executed:false} with status 'authorization_required', 'denied' or 'stopped' means "
            + "NOTHING changed - the workflow is still on whatever version it was on, and no trigger "
            + "was armed or disarmed. On {executed:false} do not describe the workflow as live, do not "
            + "call pin again, and continue with other work or finish your turn. If they answer after "
            + "that, they come back with a new request.");

        help.put("production_model", List.of(
            "The pinned version IS the production workflow - think of it as 'what's live'.",
            "Production triggers always target the LATEST run of the pinned version (ordered by startedAt DESC). " +
            "That run is typically a WAITING_TRIGGER run; each fire accumulates a new epoch into it.",
            "No pin → production triggers are refused entirely (webhooks return an error, schedules skip). " +
            "Editor runs (workflow(action='execute') without version) are unaffected.",
            "Re-pinning to a different version is instantaneous: the next trigger fire routes to the newly " +
            "pinned version's latest run and all webhook/schedule triggers re-sync from that version's plan."
        ));

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("pin", ordered(
            "syntax", "workflow(action='pin', workflow_id='<uuid>', version=N)",
            "purpose", "Promote version N to production. If N already has a successful or active run " +
                       "(COMPLETED / WAITING_TRIGGER / RUNNING / PAUSED) that run becomes the production " +
                       "run triggers hit; if it has none, pin prepares one. Either way you do NOT have " +
                       "to execute N first.",
            "side_effects", "All existing webhook/schedule triggers re-sync to the pinned plan immediately."
        ));
        actions.put("unpin", ordered(
            "syntax", "workflow(action='unpin', workflow_id='<uuid>')",
            "purpose", "Clear the pin. Production triggers will be rejected until a version is pinned again. " +
                       "Editor runs (workflow(action='execute') without version param) still work."
        ));
        actions.put("inspect", ordered(
            "syntax", "workflow(action='get', workflow_id='<uuid>')",
            "returns", "id, name, plan (full JSON), pinned_version, is_production, latest_version, default_trigger_id, trigger_types, data_inputs_schema (field names + select options for the fireable trigger), fireable_triggers (only on multi-trigger workflows). " +
                       "workflow(action='list') items carry trigger_types and node_types, but no data_inputs_schema - call get before execute when you need the data_inputs field names."
        ));
        help.put("actions", actions);

        help.put("typical_flow", List.of(
            "1. Save workflow → version N is recorded",
            "2. workflow(action='execute', id='<uuid>', version=N) → verifies v_N works end-to-end (recommended, not required by pin)",
            "3. workflow(action='pin', workflow_id='<uuid>', version=N) → promote to production",
            "4. Webhooks/schedules now fire v_N; edits create v_(N+1) without affecting production",
            "5. workflow(action='pin', workflow_id='<uuid>', version=N+1) when ready to ship the next cut"
        ));

        // The rule itself is stated in `description` and in actions.pin.purpose. This entry
        // only adds what neither says: why step 2 above is still worth doing.
        help.put("why_still_execute_first", "Step 2 above is about confidence in the plan, not about "
            + "unlocking the pin: skipping it ships a version whose behaviour you have not observed.");

        help.put("common_errors", ordered(
            "version_required", "pin requires the 'version' param - use 'unpin' to clear the pin",
            "production_run_unavailable", "The version has no production run and pin could not prepare one. "
                + "Pin is refused rather than left half-done, because a pinned version with no production run "
                + "arms the webhook, schedule and chained-workflow triggers with nothing to fire. Retry once; "
                + "if it fails again, the owner of the workflow has to resolve it.",
            "version_not_found", "That version number doesn't exist in the workflow history - check with workflow(action='get')."
        ));

        return help;
    }

    /**
     * Help for workflow(action='execute') - programmatic trigger firing.
     */
    private Map<String, Object> getExecuteHelp() {
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("title", "Execute Workflow - Fire a Trigger Programmatically");
        help.put("description",
            "Fires a workflow trigger on behalf of the user. The workflow runs and the result " +
            "is returned synchronously once the triggered epoch completes.");
        help.put("interactive_chat_authorization",
            "IMPORTANT (interactive chat only): execute is a sensitive action and needs the user's " +
            "authorization. Asking them happens inside your call: it may simply take longer to " +
            "answer while they decide. Do NOT stop, do NOT announce that you are waiting, and do " +
            "NOT re-call execute - just read what comes back. Two shapes: a normal run result " +
            "(run_id, status, outputs) means they allowed it and it RAN; " +
            "{executed:false} with status 'authorization_required', 'denied' or 'stopped' means " +
            "NOTHING ran - no run_id, no node status, no output. On {executed:false} do not " +
            "describe, summarize or invent an outcome, and do not call execute again: continue " +
            "with other work or finish your turn. If they answer after that, they come back with a " +
            "new request. A response carrying run_id/status/outputs is the ONLY proof it executed.");

        // Parameters
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", Map.of(
            "type", "string", "required", true,
            "description", "Workflow UUID. Shown in execute_info.hint returned by load/describe."));
        params.put("data_inputs", Map.of(
            "type", "object", "required", false,
            "description", "Payload sent to the trigger. Shape depends on trigger type (see execute_info.triggers[].required_data). Omit or pass {} for manual/schedule/datasource."));
        params.put("trigger_id", Map.of(
            "type", "string", "required", false,
            "description", "Normalized trigger key, e.g. 'trigger:my_webhook'. Only required when the workflow has multiple fireable triggers. Shown in execute_info.hint."));
        params.put("version", Map.of(
            "type", "integer | 'pinned' (optional)", "required", false,
            "description",
            "Target a specific plan version. Omit for the default editor-style run on the current plan. " +
            "Pass a positive integer (e.g. 3) to replay that historical version as an editor run without " +
            "creating a new version. Pass the literal 'pinned' to fire the workflow's pinned production " +
            "version - this requires the workflow to be pinned AND to already have a WAITING_TRIGGER run " +
            "(same accumulation pattern as webhook/schedule)."));
        params.put("mock_mode", Map.of(
            "type", "string (optional)", "required", false,
            "description",
            "Run-level mock override. Omit for the default: every node carrying an enabled mock block returns " +
            "its configured mock, all other nodes execute for real. 'off' = ignore ALL mocks this run. " +
            "'all_mcp' = full dry-run: configured mocks plus every mcp catalog-tool node without one serves " +
            "its catalog example (zero credentials, zero external calls). Refused with version='pinned'. " +
            "Full guide: workflow(action='help', topics=['mocking'])."));
        help.put("parameters", params);

        // Run behavior
        help.put("run_behavior", ordered(
            "default_no_version", "Omitting 'version' runs the current plan as an editor run. Executing never creates a new version: the run resolves to the latest version (parameter-level plan changes update that version's stored content in place) and epochs accumulate on the existing live run - it is reused whether it sits in WAITING_TRIGGER between fires or is still RUNNING/PAUSED on a previous epoch (firing then opens a new epoch on the same run). Only a structural plan change (nodes/edges added or removed) or an execution-mode switch (automatic vs step-by-step) starts a fresh run at that same version.",
            "version_int", "version=N replays a specific frozen version as an editor run - no new version is created; runs accumulate at that version. The fired epoch executes EXACTLY the stored content of version N, even if the current canvas plan has changed since.",
            "version_pinned", "version='pinned' fires the workflow's PRODUCTION run - the single run its pin points at. Requires a pinned version, and that production run must be in COMPLETED, WAITING_TRIGGER, RUNNING or PAUSED. Two distinct failures: (a) no such run yet - create one with version=<pinned version>, then pin that version, then retry; (b) the production run exists but is parked on a signal (a pending approval, a wait timer) - resolve the signal instead, because creating and pinning a new run would point production away from the run holding it. The error message tells you which case you are in.",
            "editor_vs_prod", "Editor fires (no version, or version=N) and production fires (version='pinned') target DIFFERENT runs: an editor fire never touches the production run - it reuses or creates its own editor run, and mock_mode only ever applies there. What makes a run 'production' is being the pin's current target, not how it was originally created: pinning promotes an existing run, so the production run may well have started life as the editor run you tested with."
        ));

        // execute_info block
        help.put("discover_schema", Map.of(
            "how", "Call workflow(action='load', id='...') or workflow(action='describe') - the response includes execute_info.",
            "execute_info_triggers", "Per-trigger: trigger_id, type, fireable flag, required_data schema, example.",
            "execute_info_hint", "Ready-to-use call syntax: workflow(action='execute', id='...', data_inputs={...})."
        ));

        // Fireable trigger types
        Map<String, String> fireableTypes = new LinkedHashMap<>();
        fireableTypes.put("manual", "Free-form data_inputs (any fields).");
        fireableTypes.put("chat", "Requires data_inputs={\"message\": \"...\"}.");
        fireableTypes.put("form", "Requires form fields as defined in trigger params.");
        fireableTypes.put("webhook", "Any JSON payload - agent simulates the external call.");
        fireableTypes.put("schedule", "Optional data_inputs - fires the scheduled job immediately.");
        fireableTypes.put("datasource", "Production: fires automatically on real row changes when workflow is " +
            "pinned. Output shape: {event_type, row_id, row, previous_row, datasource_id, triggered_at}. Read " +
            "columns via {{trigger:label.output.row.<column>}} (always safe). Editor test runs: workflow(action=" +
            "'execute') waits in WAITING_TRIGGER, then on fire runs the batch-scan loader and emits " +
            "{data:[{id, data:{...columns}}, ...], count, hasMore} - same shape as find_rows.items. data_inputs " +
            "is ignored. For per-row processing of the batch, chain core:split with input={{trigger:label.output.data}} " +
            "and read columns via {{item.data.<column>}}. ⚠️ NEVER use {{trigger:label.output.<column>}} at the " +
            "top level - columns named status/count/data/source/error/etc. silently shadow payload metadata.");
        fireableTypes.put("workflow (NOT fireable)", "Fired automatically by parent workflow completion.");
        fireableTypes.put("error (NOT fireable)", "Fired automatically when the parent workflow run ends in FAILED " +
            "or PARTIAL_SUCCESS. Configure with parent_workflow_id (the workflow whose failures should fire this " +
            "handler). BOOTSTRAP: after saving the error handler, call workflow(action='execute', id=" +
            "'<error_handler_id>') ONCE - the call returns status='BOOTSTRAPPED' with run_id=<seed run>, no fire " +
            "happens (the trigger is system-only). The dispatcher then attaches future parent failures to that seed " +
            "run; without it, failures are silently dropped (logged as 'No active run, skipping dispatch'). To " +
            "exercise the chain end-to-end, fail the parent workflow and check workflow(action='runs', workflow_id=" +
            "'<error_handler_id>') for a new epoch on the seed run. Anti-loop: an error handler that itself fails " +
            "does NOT trigger another handler. Output: {parentWorkflowId, parentRunId, status, errorMessage, " +
            "triggeredAt, failedSteps, completedSteps, totalSteps, skippedSteps}.");
        help.put("fireable_types", fireableTypes);

        // Response format
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", "Public run ID for follow-up calls.");
        response.put("trigger_id", "Normalized trigger key that was fired (e.g. 'trigger:my_webhook').");
        response.put("epoch", "Which execution epoch this result belongs to (0-based).");
        response.put("fire_count", "Total number of times this trigger has been fired (epoch + 1).");
        response.put("plan_version", "Plan version this epoch ran at - references an entry of the version history (workflow(action='versions')). For editor runs and version replays the stored plan of that entry is the plan the epoch executed; pinned production fires may additionally carry pinned-version run state.");
        response.put("pinned_version", "Workflow's pinned version (may differ from plan_version).");
        response.put("status", "COMPLETED | FAILED | AWAITING_INPUT | TIMEOUT");
        response.put("duration_ms", "Wall-clock time of the triggered epoch.");
        response.put("outputs", "List of completed terminal nodes: [{node_id, status, output}]. Large outputs (>3 rows) truncated: {row_count, preview (3 rows), truncated: true}.");
        response.put("errors", "List of failed nodes: [{node, error}]. Includes actual error messages (one per node, the most recent across this run's epochs).");
        response.put("skipped", "List of skipped node IDs (did not execute - downstream of a failure, or on an unmatched decision/switch branch).");
        response.put("awaiting_signal", "List of node IDs paused waiting for user action (approval, interface, timer).");
        response.put("running", "List of node IDs still executing.");
        response.put("blocking_on", "Present when status=AWAITING_INPUT: {node, signal_type} (USER_APPROVAL, INTERFACE_SIGNAL, WAIT_TIMER, WEBHOOK_WAIT). " +
            "INTERFACE_SIGNAL is blocking only when __continue is in action_mapping; otherwise interface auto-advances.");
        help.put("response_format", response);

        // Examples
        help.put("examples", Map.of(
            "manual_trigger", "workflow(action='execute', id='uuid', data_inputs={\"key\": \"value\"})",
            "chat_trigger", "workflow(action='execute', id='uuid', data_inputs={\"message\": \"Hello\"}, trigger_id='trigger:chat')",
            "no_inputs", "workflow(action='execute', id='uuid')",
            "multi_trigger", "workflow(action='execute', id='uuid', trigger_id='trigger:webhook_1', data_inputs={\"event\": \"created\"})"
        ));

        help.put("inspect_runs", "For run inspection: workflow(action='help', topics=['runs'])");

        return help;
    }
}
