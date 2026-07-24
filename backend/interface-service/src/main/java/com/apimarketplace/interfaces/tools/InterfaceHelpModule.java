package com.apimarketplace.interfaces.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Help module for the interface tool.
 * Interface-service native version - identical help content, no HTTP hop.
 */
@Component
public class InterfaceHelpModule implements ToolModule {

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return "help".equals(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!"help".equals(action)) return Optional.empty();
        return Optional.of(executeHelp());
    }

    private ToolExecutionResult executeHelp() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("1_concept", """
            An interface is a WEB PAGE (HTML/CSS/JS) that users see and interact with.

            STANDALONE: Create any web content (landing page, game, calculator).
              Just interface(action='create') - no workflow needed.

            IN A WORKFLOW: The interface is the FRONTEND, the workflow is the BACKEND.
              - Workflow runs → reaches interface node → page is displayed
              - User interacts (forms, buttons) → triggers fire → workflow processes → results update
              - Multiple interfaces in a workflow = multiple pages of an app""");

        result.put("2_create", Map.of(
            "syntax", "interface(action='create', name='...', format='...', html_template='...', css_template='...', js_template='...')",
            "rule", "ALWAYS provide html_template, css_template, js_template (even if empty string).",
            "format", "OPTIONAL - the shape this interface is designed for. It drives the dimensions of every "
                + "screenshot, video and preview of this interface, everywhere it is shown. Set it in the SAME call "
                + "that writes html_template, and match your `<meta name=\"viewport\" content=\"width=...\">` and layout "
                + "to it (see 3c_iframe_and_responsive). Presets: classic (1280x800) | widescreen (1920x1080) | "
                + "vertical (1080x1920, TikTok/Reels/Shorts) | square (1080x1080) | portrait (1080x1350) | "
                + "mobile (390x844) | tablet (820x1180) | desktop (1440x900) | banner (1500x500) | "
                + "social_card (1200x630) | a4_portrait (794x1123) | a4_landscape (1123x794). Aliases accepted: "
                + "landscape=classic, horizontal=16:9=widescreen, story=reel=9:16=vertical, 1:1=square, 4:5=portrait, "
                + "og=social_card. Or a custom \"WIDTHxHEIGHT\" with each side 16-2160 (e.g. '1080x1920'). "
                + "OMIT IT for a tall page you want captured whole (a long dashboard, a report): with no format the "
                + "screenshot is a FULL-PAGE capture at 1280 wide, however tall the content is. Note format='classic' "
                + "is NOT the same as omitting it: classic is an exact 1280x800 frame and crops anything below the "
                + "fold. An invalid value is rejected with an error listing the presets, never silently ignored. "
                + "create/update/get responses echo the stored `format`, normalized to its canonical value "
                + "(alias 'story' comes back as 'vertical'; absent = none set); clear it with format=''.",
            "example", "interface(action='create', name='Story Card', format='vertical', html_template='<meta name=\"viewport\" content=\"width=1080\"><h1>{{title|My Story}}</h1>', css_template='body { font-family: sans-serif; }', js_template='')"
        ));

        result.put("3_template_syntax", Map.of(
            "variables", "{{name|default}} - Use GENERIC names. The pipe '|' sets a default value if not mapped.",
            "mapping_rule", "HTML uses generic names ({{title}}, {{results}}). The workflow node maps them to real data via variable_mapping.",
            "only_supported", "ONLY {{variable}} and {{variable|default}} are resolved. Any other template syntax renders as raw text.",
            "default_no_braces", "The default value CANNOT contain '}' - the regex stops at the first '}'. " +
                "WRONG: {{obj|{}}} leaves an orphan '}' that breaks JSON.parse. " +
                "RIGHT for objects/arrays: {{obj|}} (empty default) and handle the null/empty case in js_template.",
            "dynamic_logic", "For loops/conditionals/any logic: use js_template with window.__RESOLVED_DATA__. Example:\n" +
                "  const data = window.__RESOLVED_DATA__;\n" +
                "  document.getElementById('list').innerHTML = (data.items||[]).map(i => `<div>${i.name}</div>`).join('');"
        ));

        // ═══════════════════════════════════════════════════════════════
        // SECTION 3b: IMAGES & FILES IN INTERFACES
        //
        // Single source of truth: FileStorageHelp.get() in agent-common.
        // The same map is surfaced via catalog(action='help', topics=
        // ['file_storage']). Updating one updates both - see
        // InterfaceHelpModuleTest + CatalogHelpModuleTest verbatim asserts.
        // ═══════════════════════════════════════════════════════════════
        result.put("3b_images_and_files", com.apimarketplace.agent.tools.help.FileStorageHelp.get());

        // ═══════════════════════════════════════════════════════════════
        // SECTION 3c: IFRAME RUNTIME & RESPONSIVE / JS RULES
        // (Things every css_template / js_template must respect - the page
        // renders inside an iframe with no inherited theme.)
        // ═══════════════════════════════════════════════════════════════
        result.put("3c_iframe_and_responsive", Map.of(
            "viewport_meta", "MANDATORY: include a FIXED-width `<meta name=\"viewport\" content=\"width=<W>\">` in html_template. " +
                "The host iframe does NOT pass device width - `width=device-width` will misrender. " +
                "<W> = the width of THIS interface's own `format` param (pass it in the same create/update call): " +
                "1280 for 'classic' or when you set no format, 1920 for 'widescreen', 1080 for 'vertical' " +
                "(1080x1920) / 'square' (1080x1080) / 'portrait' (1080x1350), 390 for 'mobile', 820 for 'tablet', " +
                "1440 for 'desktop', 1500 for 'banner', 1200 for 'social_card', 794 for 'a4_portrait', " +
                "1123 for 'a4_landscape', or the WIDTH you gave in a custom \"WIDTHxHEIGHT\". A fixed width keeps a " +
                "stable internal coordinate space; the host scales it for the actual viewport. Design the layout for " +
                "that width and, for portrait formats, for the format's height as the visible fold (a screenshot or " +
                "video of this interface crops below it).",
            "body_theme", "MANDATORY in css_template: `body { background-color: #ffffff; color: #111827; }` for light, " +
                "or `body { background-color: #171614; color: #edecea; }` for dark. " +
                "There is NO inherited theme - without these, body falls back to transparent + browser default text color.",
            "body_centering_injected", "The platform PREPENDS this CSS to every interface iframe (BEFORE your css_template):\n" +
                "  html { height: 100%; }\n" +
                "  body { min-height: 100%; margin: 0; display: flex; align-items: safe center; justify-content: safe center; }\n" +
                "WHY: small interfaces stay vertically centered; tall dashboards stay scrollable from the top thanks to `safe`.\n" +
                "CONSEQUENCE: `body` is a flex container - direct children are flex items. If your layout needs " +
                "full-width / top-aligned blocks (e.g. a hero banner spanning the viewport), wrap the page in a single " +
                "`<div id=\"app\">…</div>` so flex centers THAT div, and put your normal block layout INSIDE the wrapper. " +
                "Do NOT redeclare `body { display: block }` - you'd lose the safe-overflow scroll guarantee.",
            "responsive_breakpoints", "Desktop-first. Add @media blocks at 1024px (tablet), 768px (mobile), 480px (small mobile). " +
                "Sidebars: collapse to a position:fixed drawer with `transform: translateX(-100%)` + an `.open` toggle below 768px. " +
                "Grids: `grid-template-columns: 1fr` below 768px. Touch targets ≥ 44px on mobile, font-size ≥ 11px.",
            "external_resources", "Google Fonts and Material Icons load fine via standard `<link>` tags in html_template. " +
                "No CSP blocks them. Prefer `display=swap` to avoid FOIT.",
            "js_safety", "Wrap js_template in an IIFE - `(function(){ ... })();` - to avoid leaking globals across re-renders. " +
                "Prefer `var` over `const`/`let` (some iframe runtimes are non-strict). " +
                "ALWAYS try/catch around JSON.parse and `window.__RESOLVED_DATA__` access; default to `[]` / `{}` on failure."
        ));

        result.put("4_interactive_html", Map.of(
            "purpose", "To make a page interactive, HTML elements need an 'id' attribute. " +
                "The workflow's action_mapping binds these IDs to triggers.",
            "form_submit", "<form id=\"search-form\"><input name=\"query\"/><button type=\"submit\">Go</button></form>\n" +
                "  → action_mapping: {'#search-form': 'trigger:search:submit'}  (captures all <input name='...'> fields)\n" +
                "  THREE things are ALL required for a submit binding to carry data: (1) a real <form> element whose id matches the selector, " +
                "(2) every field you want sent has a `name` attribute - an `id` alone is NOT captured, (3) the submit control is a " +
                "<button type=\"submit\"> (or <input type=\"submit\">) INSIDE that form. A <button type=\"button\"> or a bare <div>/<a> will not fire the form submit.",
            "button_click", "<button id=\"delete-btn\">Delete</button>\n" +
                "  → action_mapping: {'#delete-btn': 'trigger:delete:click'}  (fires trigger, no data)",
            "field_binding_no_rename", "<input name='X'> auto-binds 1:1 to trigger.output.X. There is NO renaming layer - " +
                "if your HTML field name must match a different trigger field name, change the <input name> in the HTML to align with the trigger field, " +
                "or remap downstream in a code node. Never embed a rename map inside action_mapping - its value is a single string token, not an object.",
            "key_rules", List.of(
                "CSS selector '#search-form' matches <form id=\"search-form\"> - must be exact match",
                "submit captures all <input name='...'> inside the <form> as key-value pairs",
                "click fires the trigger with no data - use for buttons that don't need input",
                "Each element → ONE trigger within the SAME DAG. One page can have MULTIPLE elements → multiple triggers, all in the same DAG"
            )
        ));

        result.put("5_mistakes", List.of(
            "WRONG: {{mcp:step.output.field}} in HTML → Use GENERIC names ({{title}}), map via variable_mapping on the workflow node.",
            "WRONG: Any template syntax other than {{var}} / {{var|default}} → Renders as raw text. Put logic in js_template using window.__RESOLVED_DATA__.",
            "WRONG: {{obj|{}}} or any default containing '}' → Regex stops at first '}', leaves an orphan that breaks JSON.parse. Use {{obj|}} and fall back in JS.",
            "WRONG: <form id='search'> with action_mapping {'#search-form': ...} → ID mismatch! Selector must match exactly.",
            "WRONG: form fields with only an id and no name (e.g. <input id='to'/>) → submit captures by `name`, so to/from/body arrive EMPTY. Add name='to' etc. (id is for styling/JS, name is what the trigger receives).",
            "WRONG: a submit button as <button type='button'> (or a <div>/<a>) inside the form → the form 'submit' event never fires, the trigger never receives data. Use <button type='submit'> inside a real <form>.",
            "WRONG: action_mapping key without '#' (e.g. {'search_leads': 'trigger:...'}) → Keys are CSS selectors and MUST start with '#'.",
            "WRONG: action_mapping value as an object (e.g. {'#form': {trigger:'X', mapping:{a:'b'}}}) → Value is a single STRING token only " +
                "('trigger:label:submit' | 'trigger:label:click' | 'trigger:label:message' | 'interface:label:navigate' | '__continue' | '__pagination:next|prev|first|last').",
            "WRONG: action_mapping value as a field-rename map → Field renaming is NOT supported. Align <input name> to trigger field names in the HTML, or remap downstream in a code node."
        ));

        result.put("6_workflow", "For wiring to workflow (action_mapping, variable_mapping, interactions): " +
            "workflow(action='help', topics=['interface'])");

        Map<String, String> actions = new LinkedHashMap<>();
        actions.put("create", "interface(action='create', name='...', description='...', format='...', html_template='...', css_template='...', js_template='...')");
        actions.put("get", "interface(action='get', interface_id='<uuid>') - returns name, description, format (absent when none is set) and the stored templates.");
        actions.put("list", "interface(action='list', limit=25, offset=0)");
        actions.put("update", "interface(action='update', interface_id='<uuid>', html_template='...') - REPLACES the whole template. " +
            "For a small change (a label, a color, one block), prefer 'patch' instead of re-sending everything. " +
            "Re-shape an existing interface with format='vertical' (see 2_create for the preset list); " +
            "format='' clears it back to no declared shape (full-page capture at 1280 wide). Any field you omit is " +
            "left untouched.");
        actions.put("patch", "interface(action='patch', interface_id='<uuid>', target='html'|'css'|'js', " +
            "edits=[{old:'<exact current text>', new:'<replacement>'}]) - surgical search/replace, like a coding agent. " +
            "Patches ONE template per call: 'target' picks which stored template (html, css, or js); to edit two of them, " +
            "call patch twice. Each 'old' must match the CURRENT content EXACTLY (copy it verbatim, whitespace included) " +
            "and be UNIQUE; if it appears more than once, add surrounding context or pass replace_all=true. 'new' can be " +
            "'' to delete. Edits apply in order, all-or-nothing (one bad 'old' → nothing is written, nothing is wasted). " +
            "Example - recolor a title in the css: target='css', edits=[{old:'.title { color: red; }', " +
            "new:'.title { color: blue; }'}]. Unsure of the exact text? Call interface(action='get') first and copy " +
            "from its htmlTemplate/cssTemplate/jsTemplate (target 'html'→htmlTemplate, 'css'→cssTemplate, " +
            "'js'→jsTemplate). patch only EDITS existing text - to ADD css/js where a template is still empty " +
            "(get returns no such field), use interface(action='update') instead. Up to 10 successful patches per " +
            "interface (failed matches are refunded, so they don't count). On a 'not found' or 'matches N places' " +
            "error → re-get and copy verbatim, or use replace_all=true only when you mean to change ALL matches.");
        actions.put("delete", "interface(action='delete', interface_id='<uuid>')");
        actions.put("publish",
            "Add the interface to the marketplace. Params: interface_id (required), title (required), " +
            "visibility ('PRIVATE' default, 'PUBLIC', 'UNLISTED'), credits_per_use (default 0). " +
            "An interface IS its own landing page - no separate landing interface_id is accepted here. " +
            "PUBLIC listings go through platform review; PRIVATE/UNLISTED activate immediately.");
        actions.put("unpublish",
            "Mark the interface's marketplace listing inactive. Params: interface_id (required). " +
            "Existing acquirers keep their copies - only new installs are blocked.");
        result.put("7_actions", actions);

        return ToolExecutionResult.success(result);
    }
}
