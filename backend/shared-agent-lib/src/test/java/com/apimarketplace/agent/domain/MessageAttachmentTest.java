package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MessageAttachment record.
 */
@DisplayName("MessageAttachment")
class MessageAttachmentTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("image() should create image attachment")
        void shouldCreateImageAttachment() {
            byte[] data = {1, 2, 3};
            MessageAttachment att = MessageAttachment.image(data, "image/png", "photo.png");

            assertThat(att.type()).isEqualTo(AttachmentType.IMAGE);
            assertThat(att.mimeType()).isEqualTo("image/png");
            assertThat(att.data()).isEqualTo(data);
            assertThat(att.fileName()).isEqualTo("photo.png");
            assertThat(att.extractedText()).isNull();
        }

        @Test
        @DisplayName("pdf() should create PDF attachment with extracted text")
        void shouldCreatePdfAttachment() {
            byte[] data = {4, 5, 6};
            MessageAttachment att = MessageAttachment.pdf(data, "doc.pdf", "Page 1 content");

            assertThat(att.type()).isEqualTo(AttachmentType.PDF);
            assertThat(att.mimeType()).isEqualTo("application/pdf");
            assertThat(att.data()).isEqualTo(data);
            assertThat(att.fileName()).isEqualTo("doc.pdf");
            assertThat(att.extractedText()).isEqualTo("Page 1 content");
        }

        @Test
        @DisplayName("text() should create text attachment with extracted text from data")
        void shouldCreateTextAttachment() {
            byte[] data = "Hello World".getBytes();
            MessageAttachment att = MessageAttachment.text(data, "text/plain", "readme.txt");

            assertThat(att.type()).isEqualTo(AttachmentType.TEXT);
            assertThat(att.mimeType()).isEqualTo("text/plain");
            assertThat(att.data()).isEqualTo(data);
            assertThat(att.fileName()).isEqualTo("readme.txt");
            assertThat(att.extractedText()).isEqualTo("Hello World");
        }
    }

    @Nested
    @DisplayName("supportsNativeBinary()")
    class SupportsNativeBinaryTests {

        @ParameterizedTest
        @ValueSource(strings = {"anthropic", "google", "openai", "unknown"})
        @DisplayName("IMAGE should support native binary for all providers")
        void imageShouldSupportAllProviders(String provider) {
            MessageAttachment att = MessageAttachment.image(new byte[]{}, "image/png", "img.png");
            assertThat(att.supportsNativeBinary(provider)).isTrue();
        }

        @Test
        @DisplayName("PDF should support native binary for anthropic")
        void pdfShouldSupportAnthropic() {
            MessageAttachment att = MessageAttachment.pdf(new byte[]{}, "doc.pdf", "text");
            assertThat(att.supportsNativeBinary("anthropic")).isTrue();
        }

        @Test
        @DisplayName("PDF should support native binary for google")
        void pdfShouldSupportGoogle() {
            MessageAttachment att = MessageAttachment.pdf(new byte[]{}, "doc.pdf", "text");
            assertThat(att.supportsNativeBinary("google")).isTrue();
        }

        @Test
        @DisplayName("PDF should not support native binary for openai")
        void pdfShouldNotSupportOpenai() {
            MessageAttachment att = MessageAttachment.pdf(new byte[]{}, "doc.pdf", "text");
            assertThat(att.supportsNativeBinary("openai")).isFalse();
        }

        @Test
        @DisplayName("TEXT should not support native binary for any provider")
        void textShouldNotSupportAnyProvider() {
            MessageAttachment att = MessageAttachment.text("hi".getBytes(), "text/plain", "f.txt");
            assertThat(att.supportsNativeBinary("anthropic")).isFalse();
            assertThat(att.supportsNativeBinary("openai")).isFalse();
        }
    }

    @Nested
    @DisplayName("getTextContent()")
    class GetTextContentTests {

        @Test
        @DisplayName("should return extractedText when available")
        void shouldReturnExtractedText() {
            MessageAttachment att = MessageAttachment.pdf(new byte[]{}, "doc.pdf", "Extracted content");
            assertThat(att.getTextContent()).isEqualTo("Extracted content");
        }

        @Test
        @DisplayName("should return data as string for TEXT type without extractedText")
        void shouldReturnDataAsStringForText() {
            MessageAttachment att = MessageAttachment.builder()
                    .type(AttachmentType.TEXT)
                    .data("file content".getBytes())
                    .fileName("test.txt")
                    .build();

            assertThat(att.getTextContent()).isEqualTo("file content");
        }

        @Test
        @DisplayName("should return fallback message for binary without extractedText")
        void shouldReturnFallbackForBinary() {
            MessageAttachment att = MessageAttachment.builder()
                    .type(AttachmentType.IMAGE)
                    .data(new byte[]{1, 2, 3})
                    .fileName("photo.jpg")
                    .build();

            assertThat(att.getTextContent()).isEqualTo("[Binary file: photo.jpg]");
        }
    }
}
