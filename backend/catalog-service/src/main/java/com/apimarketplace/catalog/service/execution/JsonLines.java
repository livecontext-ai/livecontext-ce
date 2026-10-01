package com.apimarketplace.catalog.service.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * The records of a JSON Lines ({@code ndjson} / {@code jsonl}) answer, which also remembers the
 * text they were read from.
 *
 * <p>It is an ordinary list everywhere (serialisation, shaping, a schema that describes one
 * record), so a caller that wants records gets records. The text is kept for the one schema that
 * wants the body WHOLE: a single string field such as Typesense {@code export_documents}
 * ({@code [{"documents": "JSONL string"}]}). Projecting the records against that schema would give
 * {@code [{}, {}, ...]}; {@link OutputProjector} puts the text under the field instead.
 */
public final class JsonLines extends ArrayList<Object> {

    private final String text;

    public JsonLines(List<Object> records, String text) {
        super(records);
        this.text = text;
    }

    /** The body exactly as the provider sent it. */
    public String text() {
        return text;
    }
}
