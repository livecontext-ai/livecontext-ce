package com.apimarketplace.agent.widget;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWidgetConfigEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentWidgetConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public controller for widget endpoints.
 * No authentication required - uses widget token for identification.
 */
@RestController
@RequestMapping("/api/internal/widget")
public class WidgetController {

    private static final Logger logger = LoggerFactory.getLogger(WidgetController.class);

    private final AgentWidgetConfigService widgetConfigService;
    private final WidgetSessionService sessionService;
    private final AgentRepository agentRepository;
    private final String widgetBaseUrl;

    public WidgetController(
            AgentWidgetConfigService widgetConfigService,
            WidgetSessionService sessionService,
            AgentRepository agentRepository,
            @Value("${agent.widget.base-url:}") String widgetBaseUrl) {
        this.widgetConfigService = widgetConfigService;
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
        this.widgetBaseUrl = widgetBaseUrl;
    }

    /**
     * Serve widget.js loader script.
     * Dynamically generates the script with the correct base URL.
     */
    @GetMapping(value = "/loader.js", produces = "application/javascript")
    public ResponseEntity<String> getLoaderScript(HttpServletRequest request) {
        String baseUrl = resolveBaseUrl(request);
        String script = generateLoaderScript(baseUrl);
        return ResponseEntity.ok()
            .header("Cache-Control", "public, max-age=3600")
            .body(script);
    }

    /**
     * Get widget configuration by token.
     * Returns theme, colors, agent info for the embed page.
     */
    @GetMapping("/{token}/config")
    public ResponseEntity<WidgetResponse> getConfig(
            @PathVariable String token,
            HttpServletRequest request) {

        // Find widget config
        Optional<AgentWidgetConfigEntity> widgetOpt = widgetConfigService.findByWidgetToken(token);
        if (widgetOpt.isEmpty()) {
            return ResponseEntity.status(404).body(WidgetResponse.notFound());
        }

        AgentWidgetConfigEntity widget = widgetOpt.get();

        // Check if active
        if (!Boolean.TRUE.equals(widget.getIsActive())) {
            return ResponseEntity.status(403).body(WidgetResponse.inactive());
        }

        // Validate origin
        String origin = resolveOrigin(request);
        if (!widgetConfigService.validateOrigin(widget, origin)) {
            return ResponseEntity.status(403).body(WidgetResponse.originDenied());
        }

        // Get agent info
        Optional<AgentEntity> agentOpt = agentRepository.findById(widget.getAgentId());
        if (agentOpt.isEmpty()) {
            return ResponseEntity.status(404).body(WidgetResponse.error("Agent not found"));
        }

        AgentEntity agent = agentOpt.get();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("position", widget.getPosition());
        data.put("theme", widget.getTheme());
        data.put("primaryColor", widget.getPrimaryColor());
        data.put("welcomeMessage", widget.getWelcomeMessage());
        data.put("bubbleText", widget.getBubbleText());
        data.put("showAvatar", widget.getShowAvatar());
        data.put("autoOpenDelay", widget.getAutoOpenDelay());
        data.put("agentName", agent.getName());
        data.put("agentAvatarUrl", agent.getAvatarUrl());

        return ResponseEntity.ok().body(WidgetResponse.success(data));
    }

    /**
     * Create a new anonymous session for widget chat.
     */
    @PostMapping("/{token}/session")
    public ResponseEntity<WidgetResponse> createSession(
            @PathVariable String token,
            HttpServletRequest request) {

        // Find active widget config
        Optional<AgentWidgetConfigEntity> widgetOpt = widgetConfigService.findActiveByWidgetToken(token);
        if (widgetOpt.isEmpty()) {
            Optional<AgentWidgetConfigEntity> inactiveOpt = widgetConfigService.findByWidgetToken(token);
            if (inactiveOpt.isPresent()) {
                return ResponseEntity.status(403).body(WidgetResponse.inactive());
            }
            return ResponseEntity.status(404).body(WidgetResponse.notFound());
        }

        AgentWidgetConfigEntity widget = widgetOpt.get();

        // Validate origin
        String origin = resolveOrigin(request);
        if (!widgetConfigService.validateOrigin(widget, origin)) {
            return ResponseEntity.status(403).body(WidgetResponse.originDenied());
        }

        // Get agent
        Optional<AgentEntity> agentOpt = agentRepository.findById(widget.getAgentId());
        if (agentOpt.isEmpty()) {
            return ResponseEntity.status(404).body(WidgetResponse.error("Agent not found"));
        }

        AgentEntity agent = agentOpt.get();
        if (!Boolean.TRUE.equals(agent.getIsActive())) {
            return ResponseEntity.status(403).body(WidgetResponse.error("Agent is inactive"));
        }

        // Create session
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        try {
            WidgetSessionService.WidgetSession session = sessionService.createSession(agent, ip, userAgent);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", session.sessionId());
            data.put("conversationId", session.conversationId());

            return ResponseEntity.ok().body(WidgetResponse.success(data));
        } catch (Exception e) {
            logger.error("Failed to create widget session: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(WidgetResponse.error("Failed to create session"));
        }
    }

    /**
     * Send a message in a widget chat session (synchronous).
     * Blocks until agent completes, then returns WidgetResponse with {content, conversationId}.
     */
    @PostMapping(value = "/{token}/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(
            @PathVariable String token,
            @RequestHeader(value = "X-Widget-Session", required = false) String sessionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {

        // Validate token
        Optional<AgentWidgetConfigEntity> widgetOpt = widgetConfigService.findActiveByWidgetToken(token);
        if (widgetOpt.isEmpty()) {
            return ResponseEntity.status(404).body(WidgetResponse.notFound());
        }

        AgentWidgetConfigEntity widget = widgetOpt.get();

        // Validate origin
        String origin = resolveOrigin(request);
        if (!widgetConfigService.validateOrigin(widget, origin)) {
            return ResponseEntity.status(403).body(WidgetResponse.originDenied());
        }

        // Validate session
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.status(401).body(WidgetResponse.error("Missing session"));
        }

        String ip = getClientIp(request);
        if (!sessionService.validateSession(sessionId, ip)) {
            return ResponseEntity.status(401).body(WidgetResponse.error("Invalid or expired session"));
        }

        WidgetSessionService.WidgetSession session = sessionService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(401).body(WidgetResponse.error("Session not found"));
        }

        // Get agent
        Optional<AgentEntity> agentOpt = agentRepository.findById(widget.getAgentId());
        if (agentOpt.isEmpty()) {
            return ResponseEntity.status(404).body(WidgetResponse.error("Agent not found"));
        }

        // Send message
        String message = body.get("message") != null ? body.get("message").toString() : "";
        if (message.isBlank()) {
            return ResponseEntity.badRequest().body(WidgetResponse.error("Message is required"));
        }

        try {
            Map<String, Object> result = sessionService.sendMessage(
                session.tenantId(),
                session.conversationId(),
                message,
                agentOpt.get()
            );

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("content", result.getOrDefault("content", ""));
                data.put("conversationId", session.conversationId());
                return ResponseEntity.ok().body(WidgetResponse.success(data));
            } else {
                String errorMsg = result.get("error") != null ? result.get("error").toString() : "Agent execution failed";
                return ResponseEntity.status(500).body(WidgetResponse.error(errorMsg));
            }
        } catch (Exception e) {
            logger.error("Error in widget chat: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(WidgetResponse.error("Error processing message: " + e.getMessage()));
        }
    }

    /**
     * Get conversation history for a widget session.
     */
    @GetMapping("/{token}/history")
    public ResponseEntity<?> getHistory(
            @PathVariable String token,
            @RequestHeader(value = "X-Widget-Session", required = false) String sessionId,
            HttpServletRequest request) {

        // Validate token
        Optional<AgentWidgetConfigEntity> widgetOpt = widgetConfigService.findActiveByWidgetToken(token);
        if (widgetOpt.isEmpty()) {
            return ResponseEntity.status(404).body(WidgetResponse.notFound());
        }

        AgentWidgetConfigEntity widget = widgetOpt.get();

        // Validate origin
        String origin = resolveOrigin(request);
        if (!widgetConfigService.validateOrigin(widget, origin)) {
            return ResponseEntity.status(403).body(WidgetResponse.originDenied());
        }

        // Validate session
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.status(401).body(WidgetResponse.error("Missing session"));
        }

        String ip = getClientIp(request);
        if (!sessionService.validateSession(sessionId, ip)) {
            return ResponseEntity.status(401).body(WidgetResponse.error("Invalid or expired session"));
        }

        WidgetSessionService.WidgetSession session = sessionService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(401).body(WidgetResponse.error("Session not found"));
        }

        String organizationId = session.organizationId();
        if ((organizationId == null || organizationId.isBlank()) && widget.getAgentId() != null) {
            organizationId = agentRepository.findById(widget.getAgentId())
                .map(AgentEntity::getOrganizationId)
                .filter(orgId -> orgId != null && !orgId.isBlank())
                .orElse(null);
        }

        List<Map<String, Object>> messages = sessionService.getHistory(
            session.tenantId(),
            session.conversationId(),
            organizationId
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", messages);

        return ResponseEntity.ok().body(WidgetResponse.success(data));
    }

    // ========== Private helpers ==========

    private String resolveOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            String referer = request.getHeader("Referer");
            if (referer != null && !referer.isBlank()) {
                try {
                    java.net.URL url = new java.net.URL(referer);
                    origin = url.getProtocol() + "://" + url.getHost();
                    if (url.getPort() != -1 && url.getPort() != url.getDefaultPort()) {
                        origin += ":" + url.getPort();
                    }
                } catch (Exception e) {
                    // Ignore invalid referer
                }
            }
        }
        return origin;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (widgetBaseUrl != null && !widgetBaseUrl.isBlank()) {
            return widgetBaseUrl.endsWith("/") ? widgetBaseUrl.substring(0, widgetBaseUrl.length() - 1) : widgetBaseUrl;
        }
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        if ((scheme.equals("http") && serverPort == 80) || (scheme.equals("https") && serverPort == 443)) {
            return scheme + "://" + serverName;
        }
        return scheme + "://" + serverName + ":" + serverPort;
    }

    private String generateLoaderScript(String baseUrl) {
        return "(function() {\n" +
            "  'use strict';\n" +
            "  if (window.__lcWidgetLoaded) return;\n" +
            "  window.__lcWidgetLoaded = true;\n" +
            "\n" +
            "  var script = document.currentScript;\n" +
            "  var config = {\n" +
            "    widgetToken: script.dataset.widgetToken || '',\n" +
            "    position: script.dataset.position || 'bottom-right',\n" +
            "    theme: script.dataset.theme || 'auto',\n" +
            "    color: script.dataset.color || '#000000',\n" +
            "    welcome: script.dataset.welcome || '',\n" +
            "    bubbleText: script.dataset.bubbleText || 'Chat',\n" +
            "    showAvatar: script.dataset.showAvatar !== 'false',\n" +
            "    autoOpen: parseInt(script.dataset.autoOpen || '0', 10)\n" +
            "  };\n" +
            "\n" +
            "  var BASE_URL = '" + escapeJs(baseUrl) + "';\n" +
            "\n" +
            "  // Create styles\n" +
            "  var style = document.createElement('style');\n" +
            "  style.textContent = [\n" +
            "    '#lc-widget-bubble{position:fixed;z-index:2147483647;cursor:pointer;',\n" +
            "    'width:60px;height:60px;border-radius:50%;display:flex;align-items:center;',\n" +
            "    'justify-content:center;box-shadow:0 4px 12px rgba(0,0,0,0.15);',\n" +
            "    'transition:transform 0.2s,box-shadow 0.2s;border:none;outline:none;}',\n" +
            "    '#lc-widget-bubble:hover{transform:scale(1.1);box-shadow:0 6px 20px rgba(0,0,0,0.2);}',\n" +
            "    '#lc-widget-bubble svg{width:28px;height:28px;fill:#fff;}',\n" +
            "    '#lc-widget-label{position:fixed;z-index:2147483646;',\n" +
            "    'padding:8px 16px;border-radius:8px;font-family:-apple-system,BlinkMacSystemFont,sans-serif;',\n" +
            "    'font-size:14px;box-shadow:0 2px 8px rgba(0,0,0,0.1);white-space:nowrap;',\n" +
            "    'opacity:0;transform:translateY(10px);transition:opacity 0.3s,transform 0.3s;}',\n" +
            "    '#lc-widget-label.visible{opacity:1;transform:translateY(0);}',\n" +
            "    '#lc-widget-frame{position:fixed;z-index:2147483647;border:none;',\n" +
            "    'border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,0.16);',\n" +
            "    'width:400px;height:600px;max-width:calc(100vw - 32px);max-height:calc(100vh - 100px);',\n" +
            "    'display:none;overflow:hidden;}',\n" +
            "    '@media(max-width:480px){#lc-widget-frame{width:100vw;height:100vh;',\n" +
            "    'max-width:100vw;max-height:100vh;border-radius:0;top:0!important;left:0!important;',\n" +
            "    'right:0!important;bottom:0!important;}}'\n" +
            "  ].join('');\n" +
            "  document.head.appendChild(style);\n" +
            "\n" +
            "  // Position helpers\n" +
            "  function applyPosition(el, type) {\n" +
            "    var pos = config.position;\n" +
            "    var isBottom = pos.indexOf('bottom') >= 0;\n" +
            "    var isRight = pos.indexOf('right') >= 0;\n" +
            "    if (type === 'bubble') {\n" +
            "      el.style[isBottom ? 'bottom' : 'top'] = '20px';\n" +
            "      el.style[isRight ? 'right' : 'left'] = '20px';\n" +
            "    } else if (type === 'label') {\n" +
            "      el.style[isBottom ? 'bottom' : 'top'] = '90px';\n" +
            "      el.style[isRight ? 'right' : 'left'] = '20px';\n" +
            "    } else if (type === 'frame') {\n" +
            "      el.style[isBottom ? 'bottom' : 'top'] = '90px';\n" +
            "      el.style[isRight ? 'right' : 'left'] = '20px';\n" +
            "    }\n" +
            "  }\n" +
            "\n" +
            "  // Create bubble\n" +
            "  var bubble = document.createElement('button');\n" +
            "  bubble.id = 'lc-widget-bubble';\n" +
            "  bubble.style.backgroundColor = config.color;\n" +
            "  bubble.innerHTML = '<svg viewBox=\"0 0 24 24\"><path d=\"M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z\"/></svg>';\n" +
            "  bubble.setAttribute('aria-label', config.bubbleText || 'Open chat');\n" +
            "  applyPosition(bubble, 'bubble');\n" +
            "  document.body.appendChild(bubble);\n" +
            "\n" +
            "  // Create bubble label\n" +
            "  var label = document.createElement('div');\n" +
            "  label.id = 'lc-widget-label';\n" +
            "  label.textContent = config.bubbleText;\n" +
            "  var isDark = config.theme === 'dark' || (config.theme === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);\n" +
            "  label.style.backgroundColor = isDark ? '#1f2937' : '#ffffff';\n" +
            "  label.style.color = isDark ? '#f3f4f6' : '#111827';\n" +
            "  applyPosition(label, 'label');\n" +
            "  document.body.appendChild(label);\n" +
            "  setTimeout(function() { label.classList.add('visible'); }, 500);\n" +
            "\n" +
            "  // Create iframe\n" +
            "  var frame = document.createElement('iframe');\n" +
            "  frame.id = 'lc-widget-frame';\n" +
            "  frame.src = BASE_URL + '/w/embed/' + encodeURIComponent(config.widgetToken);\n" +
            "  frame.setAttribute('allow', 'clipboard-write');\n" +
            "  applyPosition(frame, 'frame');\n" +
            "  document.body.appendChild(frame);\n" +
            "\n" +
            "  var isOpen = false;\n" +
            "\n" +
            "  function toggleWidget() {\n" +
            "    isOpen = !isOpen;\n" +
            "    frame.style.display = isOpen ? 'block' : 'none';\n" +
            "    label.style.display = isOpen ? 'none' : 'block';\n" +
            "    bubble.innerHTML = isOpen\n" +
            "      ? '<svg viewBox=\"0 0 24 24\"><path d=\"M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z\"/></svg>'\n" +
            "      : '<svg viewBox=\"0 0 24 24\"><path d=\"M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z\"/></svg>';\n" +
            "  }\n" +
            "\n" +
            "  bubble.addEventListener('click', toggleWidget);\n" +
            "\n" +
            "  // Listen for messages from iframe\n" +
            "  window.addEventListener('message', function(e) {\n" +
            "    if (e.source !== frame.contentWindow) return;\n" +
            "    var data = e.data;\n" +
            "    if (!data || !data.type) return;\n" +
            "    if (data.type === 'lc-widget-close') {\n" +
            "      if (isOpen) toggleWidget();\n" +
            "    } else if (data.type === 'lc-widget-resize') {\n" +
            "      if (data.height) frame.style.height = data.height + 'px';\n" +
            "    }\n" +
            "  });\n" +
            "\n" +
            "  // Auto-open\n" +
            "  if (config.autoOpen > 0) {\n" +
            "    setTimeout(function() {\n" +
            "      if (!isOpen) toggleWidget();\n" +
            "    }, config.autoOpen * 1000);\n" +
            "  }\n" +
            "})();\n";
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }
}
