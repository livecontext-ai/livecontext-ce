package com.apimarketplace.orchestrator.execution.v2.nodes;

/**
 * Types of execution nodes in the workflow tree.
 */
public enum NodeType {
    TRIGGER,
    MCP,  // MCP tool (was STEP)
    DECISION,
    SWITCH,   // Switch - Value-based branching (case/default)
    LOOP,
    SPLIT,    // Split - Iteration over list items (parallel semantics)
    FORK,     // Fork - Parallel branching (ALL branches execute)
    MERGE,
    AGGREGATE, // Aggregate - Collect N items into 1 (data transformation)
    WAIT,      // Wait/delay - pauses execution for specified duration
    TRANSFORM, // Transform - applies data mappings
    DOWNLOAD_FILE, // Download file from URL and store in S3/MinIO
    END,
    AGENT,  // AI Agent with LLM + tool calling capabilities
    EXIT,   // Exit - ends execution along this branch (other parallel branches continue)
    RESPONSE, // Response - sends a message to chat and continues
    OPTION,  // Option - multiple choice branching with expressions
    HTTP_REQUEST, // HTTP Request - makes HTTP calls to external APIs
    APPROVAL,  // Approval - user approval branching (approved/rejected/timeout ports)
    FIND,  // Find - CRUD read + split parallel per row
    INTERFACE,  // Interface - UI node (blocking with __continue, auto-advance otherwise)
    DATA_INPUT,  // Data Input - provides text/files as structured input
    FILTER,  // Filter - keep only items matching conditions
    SORT,    // Sort - reorder items by fields
    LIMIT,   // Limit - pass through only first/last N items
    REMOVE_DUPLICATES,  // RemoveDuplicates - deduplicate items by fields
    SUMMARIZE,  // Summarize - aggregate data (sum, avg, count, min, max, group by)
    DATE_TIME,  // DateTime - parse, format, convert, manipulate dates
    CRYPTO_JWT,  // CryptoJWT - hash, encrypt, JWT, base64
    XML,  // XML - parse XML to JSON and convert JSON to XML
    COMPRESSION,  // Compression - compress/decompress files (ZIP, GZIP)
    RSS,  // RSS - fetch and parse RSS/Atom feeds
    CONVERT_TO_FILE,  // ConvertToFile - export JSON data to CSV, XLSX, JSON, TXT files
    EXTRACT_FROM_FILE,  // ExtractFromFile - import CSV, XLSX, JSON files to JSON items
    COMPARE_DATASETS,  // CompareDatasets - compare two datasets (in both, only in A, only in B)
    SUB_WORKFLOW,  // SubWorkflow - execute another workflow as a function (pass data in, receive results)
    RESPOND_TO_WEBHOOK,  // RespondToWebhook - control HTTP response returned to webhook caller
    SEND_EMAIL,  // SendEmail - send emails via SMTP with user-provided credentials
    EMAIL_INBOX,  // EmailInbox - read messages and act on a mailbox via IMAP with user-provided credentials
    CODE,  // Code - execute user code in sandboxed environment via Piston
    SET,  // Set - assign or transform fields on the input data (Set / Edit Fields)
    HTML_EXTRACT,  // HtmlExtract - parse HTML via CSS selectors using jsoup
    TASK,  // Task - CRUD operations on agent tasks (create, get, update, delete, list)
    STOP_ON_ERROR,  // StopOnError - immediately fails workflow with error message
    SSH,  // SSH - execute commands on remote servers via SSH
    SFTP,  // SFTP - file operations on remote servers via SFTP
    DATABASE,  // Database - execute SQL queries against databases (PostgreSQL, MySQL, MSSQL)
    BROWSER_AGENT  // BrowserAgent - autonomously navigate web pages with an LLM-driven browser agent
}
