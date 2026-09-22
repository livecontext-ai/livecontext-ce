package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Writes the {@link ApiCatalogSignedBundle} envelope straight into a response
 * stream, encoding {@code payloadBase64} incrementally instead of building it.
 *
 * <p><b>Why this exists.</b> Serving the bundle as a returned record made one
 * request hold three copies of the catalog at once: the ~24 MB GZIP
 * {@code byte[]}, the ~32 MB base64 {@code String} built by
 * {@code Base64.getEncoder().encodeToString(...)}, and Jackson's serialisation
 * buffer. Three CE installs polling on the same cron tick were measured adding
 * ~339 MiB to the heap in a single minute, which G1 then kept committed. Here
 * {@link JsonGenerator#writeBinary(java.io.InputStream, int)} emits base64 in
 * fixed-size chunks, and the stream it reads is fed one database slice at a
 * time, so nothing here holds the payload.
 *
 * <p><b>The wire format is unchanged.</b> Field names and order match the record
 * components of {@link ApiCatalogSignedBundle}, and Jackson's default base64
 * variant ({@code MIME_NO_LINEFEEDS}) produces the same characters as
 * {@code java.util.Base64.getEncoder()}: padded, no line breaks. Already-deployed
 * CE instances parse the response exactly as before.
 */
final class ApiCatalogBundleJsonWriter {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private ApiCatalogBundleJsonWriter() {
    }

    /**
     * Serialise {@code bundle} to {@code out}. The caller owns {@code out} and
     * closes it; the generator is configured not to close the target so an
     * error mid-write cannot truncate-and-close a response the container still
     * needs to finish.
     */
    static void write(ApiCatalogBundleService.RawBundle bundle, OutputStream out) throws IOException {
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            gen.writeStartObject();
            gen.writeNumberField("version", bundle.version());
            gen.writeNumberField("schemaVersion", bundle.schemaVersion());
            gen.writeStringField("checksum", bundle.checksum());
            gen.writeStringField("signature", bundle.signature());
            gen.writeStringField("signingKeyId", bundle.signingKeyId());
            gen.writeStringField("issuer", bundle.issuer());
            gen.writeNumberField("apiCount", bundle.apiCount());
            gen.writeNumberField("toolCount", bundle.toolCount());
            gen.writeNumberField("rawBytesSize", bundle.rawBytesSize());
            gen.writeFieldName("payloadBase64");
            // The stream is fed one slice at a time, so this encodes a payload
            // of any size without ever holding it.
            try (InputStream payload = bundle.payload().get()) {
                gen.writeBinary(payload, (int) bundle.payloadLength());
            }
            gen.writeEndObject();
        }
    }
}
