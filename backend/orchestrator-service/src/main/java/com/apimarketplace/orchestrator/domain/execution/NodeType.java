package com.apimarketplace.orchestrator.domain.execution;

/**
 * Types of nodes in a workflow graph.
 * Must stay in sync with execution v2 NodeType enum.
 */
public enum NodeType {

    // ===== Triggers =====
    TRIGGER,

    // ===== Steps =====
    MCP,
    AGENT,

    // ===== Control flow =====
    DECISION,
    SWITCH,
    LOOP_CONTROLLER,
    SPLIT_CONTROLLER,
    MERGE,
    FORK,
    AGGREGATE,
    OPTION,

    // ===== Data processing =====
    TRANSFORM,
    FILTER,
    SORT,
    LIMIT,
    REMOVE_DUPLICATES,
    SUMMARIZE,
    COMPARE_DATASETS,
    HTML_EXTRACT,

    // ===== I/O =====
    FIND,
    HTTP_REQUEST,
    DOWNLOAD_FILE,
    CONVERT_TO_FILE,
    EXTRACT_FROM_FILE,
    RSS,
    XML,
    COMPRESSION,
    SEND_EMAIL,
    EMAIL_INBOX,
    CODE,
    SUB_WORKFLOW,

    // ===== Security / encoding =====
    CRYPTO_JWT,
    DATE_TIME,

    // ===== Interaction =====
    INTERFACE,
    DATA_INPUT,
    APPROVAL,
    RESPOND_TO_WEBHOOK,
    RESPONSE,

    // ===== Flow control =====
    WAIT,
    EXIT,
    END,

    // ===== CRUD (emitted by StepNode for table: operations) =====
    INSERT_ROW,
    GET_ROWS,
    UPDATE_ROW,
    DELETE_ROW,
    CREATE_COLUMN;

    /**
     * Returns the prefix used in normalized node IDs.
     */
    public String getPrefix() {
        return switch (this) {
            case TRIGGER -> "trigger";
            case MCP -> "mcp";
            case INSERT_ROW, GET_ROWS, UPDATE_ROW, DELETE_ROW, CREATE_COLUMN -> "table";
            case AGENT -> "agent";
            case INTERFACE -> "interface";
            default -> "core";
        };
    }

    /**
     * Parses NodeType from a normalized node ID.
     * Example: "trigger:test" -> TRIGGER, "mcp:api_call" -> MCP, "agent:assistant" -> AGENT
     *
     * Note: For "core:" prefix, this method returns DECISION as default.
     * Use fromCoreType() when you have access to the Core object.
     */
    public static NodeType fromNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return MCP;
        }
        if (nodeId.startsWith("trigger:")) {
            return TRIGGER;
        }
        if (nodeId.startsWith("mcp:")) {
            return MCP;
        }
        if (nodeId.startsWith("table:")) {
            return GET_ROWS; // Default CRUD type for table: prefix
        }
        if (nodeId.startsWith("agent:")) {
            return AGENT;
        }
        if (nodeId.startsWith("interface:")) {
            return INTERFACE;
        }
        if (nodeId.startsWith("core:")) {
            // Default to DECISION for core nodes
            // Use fromCoreType() when you have access to the Core object
            return DECISION;
        }
        // Default to MCP (includes implicit merge nodes detected from edges)
        return MCP;
    }

    /**
     * Returns the NodeType for a Core node based on its type field.
     *
     * @param coreType The Core.type() value (e.g., "decision", "switch", "loop", "split", "merge", "fork", "aggregate", "option")
     * @return The corresponding NodeType
     */
    public static NodeType fromCoreType(String coreType) {
        if (coreType == null || coreType.isBlank()) {
            return DECISION; // Default
        }
        return switch (coreType.toLowerCase()) {
            case "decision" -> DECISION;
            case "switch" -> SWITCH;
            case "loop" -> LOOP_CONTROLLER;
            case "split" -> SPLIT_CONTROLLER;
            case "merge" -> MERGE;
            case "fork" -> FORK;
            case "aggregate" -> AGGREGATE;
            case "option" -> OPTION;
            case "transform" -> TRANSFORM;
            case "filter" -> FILTER;
            case "sort" -> SORT;
            case "limit" -> LIMIT;
            case "remove_duplicates" -> REMOVE_DUPLICATES;
            case "summarize" -> SUMMARIZE;
            case "compare_datasets" -> COMPARE_DATASETS;
            case "html_extract" -> HTML_EXTRACT;
            case "find" -> FIND;
            case "http_request" -> HTTP_REQUEST;
            case "download_file" -> DOWNLOAD_FILE;
            case "convert_to_file" -> CONVERT_TO_FILE;
            case "extract_from_file" -> EXTRACT_FROM_FILE;
            case "rss" -> RSS;
            case "xml" -> XML;
            case "compression" -> COMPRESSION;
            case "send_email" -> SEND_EMAIL;
            case "email_inbox" -> EMAIL_INBOX;
            case "code" -> CODE;
            case "sub_workflow" -> SUB_WORKFLOW;
            case "crypto_jwt" -> CRYPTO_JWT;
            case "date_time" -> DATE_TIME;
            case "interface" -> INTERFACE;
            case "data_input" -> DATA_INPUT;
            case "approval" -> APPROVAL;
            case "respond_to_webhook" -> RESPOND_TO_WEBHOOK;
            case "response" -> RESPONSE;
            case "wait" -> WAIT;
            case "exit" -> EXIT;
            case "end" -> END;
            default -> DECISION;
        };
    }
}
