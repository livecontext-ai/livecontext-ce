package com.apimarketplace.agent.domain;

/**
 * Type classification for message attachments.
 * Determines how the attachment is processed and sent to LLM providers.
 */
public enum AttachmentType {
    /**
     * Image files (JPEG, PNG, GIF, WebP).
     * Supported natively by: Claude, OpenAI, Gemini
     */
    IMAGE,

    /**
     * PDF documents.
     * Supported natively by: Claude, Gemini
     * Fallback (text extraction) for: OpenAI, Mistral
     */
    PDF,

    /**
     * Text-based files (TXT, MD, CSV, JSON, code files).
     * Supported by all providers as text content.
     */
    TEXT,

    /**
     * Other file types not directly processable.
     * Will be mentioned but not sent to LLM.
     */
    OTHER
}
