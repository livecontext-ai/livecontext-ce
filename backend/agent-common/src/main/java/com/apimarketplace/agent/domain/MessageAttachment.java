package com.apimarketplace.agent.domain;

import lombok.Builder;

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
    String extractedText
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
