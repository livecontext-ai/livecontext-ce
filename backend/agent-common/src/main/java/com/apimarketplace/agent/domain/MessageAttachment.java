package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.Map;

/**
 * Represents a file attachment within a chat message.
 * Used for multimodal LLM interactions (images, PDFs, text files).
 */
@Builder
public record MessageAttachment(
    /**
     * Type classification of the attachment
     */
    AttachmentType type,

    /**
     * MIME type of the file (e.g., "image/png", "application/pdf")
     */
    String mimeType,

    /**
     * Binary content of the file
     */
    byte[] data,

    /**
     * Original filename
     */
    String fileName,

    /**
     * Extracted text content for PDFs/text files.
     * Used as fallback for providers that don't support the native format.
     */
    String extractedText,

    /**
     * Canonical FileRef ({@code {_type:"file", path, name, mimeType, size, id}}) when this
     * attachment is backed by a durable, tenant-scoped S3 object - null for a legacy DB-blob
     * row (pre S3 migration) that has no storage key to reference from another tool.
     *
     * <p>Set only by {@code AttachmentService.loadAttachments} (conversation-service), the
     * single place a chat attachment is resolved from storage. Everything downstream
     * (direct-API {@code AgentLoopService}, the bridge's {@code attachmentPrompt.mjs}) reads
     * this to tell the model the attachment can be passed VERBATIM as a tool's file-shaped
     * argument (e.g. {@code generation}'s {@code input_image}), instead of only being visible
     * to the model as inline vision/text content. Without it, an agent can SEE an attached
     * image but can never REFERENCE it in a tool call, because {@code input_image} requires
     * "the whole file object another tool returned" and a bare chat attachment never produced
     * one.
     */
    Map<String, Object> fileRef
) {

    /**
     * Create an image attachment
     */
    public static MessageAttachment image(byte[] data, String mimeType, String fileName) {
        return MessageAttachment.builder()
            .type(AttachmentType.IMAGE)
            .mimeType(mimeType)
            .data(data)
            .fileName(fileName)
            .build();
    }

    /**
     * Create a PDF attachment
     */
    public static MessageAttachment pdf(byte[] data, String fileName, String extractedText) {
        return MessageAttachment.builder()
            .type(AttachmentType.PDF)
            .mimeType("application/pdf")
            .data(data)
            .fileName(fileName)
            .extractedText(extractedText)
            .build();
    }

    /**
     * Create a text file attachment
     */
    public static MessageAttachment text(byte[] data, String mimeType, String fileName) {
        return MessageAttachment.builder()
            .type(AttachmentType.TEXT)
            .mimeType(mimeType)
            .data(data)
            .fileName(fileName)
            .extractedText(new String(data))
            .build();
    }

    /**
     * Check if this attachment can be sent as native binary to the provider
     */
    public boolean supportsNativeBinary(String providerName) {
        return switch (type) {
            case IMAGE -> true; // All major providers support images
            case PDF -> "anthropic".equals(providerName) || "google".equals(providerName);
            case TEXT, OTHER -> false;
        };
    }

    /**
     * Get the content as text (for text-based sending)
     */
    public String getTextContent() {
        if (extractedText != null) {
            return extractedText;
        }
        if (type == AttachmentType.TEXT && data != null) {
            return new String(data);
        }
        return "[Binary file: " + fileName + "]";
    }
}
