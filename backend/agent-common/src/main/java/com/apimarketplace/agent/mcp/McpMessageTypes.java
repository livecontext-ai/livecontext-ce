package com.apimarketplace.agent.mcp;

import java.util.List;
import java.util.Map;

/**
 * Message types for Model Context Protocol (MCP).
 * Based on the MCP specification for tool exposure to AI agents.
 */
public class McpMessageTypes {

    // ==================== Server Info ====================

    public record ServerInfo(
        String name,
        String version,
        ServerCapabilities capabilities
    ) {
        public static ServerInfo defaultInfo() {
            return new ServerInfo(
                "LiveContext Agent Tools",
                "1.0.0",
                new ServerCapabilities(true, true, false)
            );
        }
    }

    public record ServerCapabilities(
        boolean tools,
        boolean resources,
        boolean prompts
    ) {}

    // ==================== Initialize ====================

    public record InitializeRequest(
        String protocolVersion,
        ClientInfo clientInfo,
        Map<String, Object> capabilities
    ) {}

    public record ClientInfo(
        String name,
        String version
    ) {}

    public record InitializeResponse(
        String protocolVersion,
        ServerInfo serverInfo,
        ServerCapabilities capabilities
    ) {
        public static InitializeResponse create() {
            return new InitializeResponse(
                "2024-11-05",
                ServerInfo.defaultInfo(),
                new ServerCapabilities(true, true, false)
            );
        }
    }

    // ==================== Tools ====================

    public record ToolsListResponse(
        List<McpTool> tools
    ) {}

    public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
    ) {}

    public record ToolCallRequest(
        String name,
        Map<String, Object> arguments
    ) {}

    public record ToolCallResponse(
        List<ToolContent> content,
        boolean isError
    ) {
        public static ToolCallResponse success(Object data) {
            return new ToolCallResponse(
                List.of(new ToolContent("text", data.toString(), null)),
                false
            );
        }

        public static ToolCallResponse error(String message) {
            return new ToolCallResponse(
                List.of(new ToolContent("text", message, null)),
                true
            );
        }

        public static ToolCallResponse json(Map<String, Object> data) {
            return new ToolCallResponse(
                List.of(new ToolContent("text", data.toString(), null)),
                false
            );
        }
    }

    public record ToolContent(
        String type,
        String text,
        String mimeType
    ) {}

    // ==================== Resources ====================

    public record ResourcesListResponse(
        List<McpResource> resources
    ) {}

    public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType
    ) {}

    public record ResourceReadRequest(
        String uri
    ) {}

    public record ResourceReadResponse(
        List<ResourceContent> contents
    ) {}

    public record ResourceContent(
        String uri,
        String mimeType,
        String text,
        byte[] blob
    ) {}

    // ==================== JSON-RPC Wrapper ====================

    public record JsonRpcRequest(
        String jsonrpc,
        String method,
        Object params,
        Object id
    ) {
        public JsonRpcRequest {
            if (jsonrpc == null) jsonrpc = "2.0";
        }
    }

    public record JsonRpcResponse(
        String jsonrpc,
        Object result,
        JsonRpcError error,
        Object id
    ) {
        public static JsonRpcResponse success(Object result, Object id) {
            return new JsonRpcResponse("2.0", result, null, id);
        }

        public static JsonRpcResponse error(int code, String message, Object id) {
            return new JsonRpcResponse("2.0", null, new JsonRpcError(code, message, null), id);
        }
    }

    public record JsonRpcError(
        int code,
        String message,
        Object data
    ) {
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
    }
}
