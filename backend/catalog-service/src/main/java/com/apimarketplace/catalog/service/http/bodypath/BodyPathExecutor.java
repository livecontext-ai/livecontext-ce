package com.apimarketplace.catalog.service.http.bodypath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BodyPathExecutor {

    private static final Logger log = LoggerFactory.getLogger(BodyPathExecutor.class);

    private BodyPathExecutor() {}

    public static void apply(Map<String, Object> root, String path, Object value) {
        List<BodyPathToken> tokens = BodyPathParser.parse(path);
        apply(root, path, tokens, value);
    }

    static void apply(Map<String, Object> root, String path, List<BodyPathToken> tokens, Object value) {
        int mappedIdx = findMappedArrayIndex(tokens);
        if (mappedIdx < 0) {
            applyLiteral(root, path, tokens, value);
        } else {
            applyMapped(root, path, tokens, mappedIdx, value);
        }
    }

    private static int findMappedArrayIndex(List<BodyPathToken> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof BodyPathToken.MappedArray) {
                return i;
            }
        }
        return -1;
    }

    private static void applyLiteral(Map<String, Object> root, String path,
                                     List<BodyPathToken> tokens, Object value) {
        if (tokens.size() == 1) {
            writeTerminal(root, path, tokens.get(0), value);
            return;
        }
        Map<String, Object> current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            BodyPathToken t = tokens.get(i);
            current = descendInto(current, path, t);
        }
        writeTerminal(current, path, tokens.get(tokens.size() - 1), value);
    }

    private static void applyMapped(Map<String, Object> root, String path,
                                    List<BodyPathToken> tokens, int mappedIdx, Object value) {
        if (value == null) {
            log.warn("bodyPath '{}' received null for array-mapping segment - skipping param", path);
            return;
        }
        if (!(value instanceof List<?> incoming)) {
            throw new BodyPathException.Arity(path, tokens.get(mappedIdx).key() + "[]",
                    "List<?> (one element per array entry)",
                    value.getClass().getSimpleName());
        }

        BodyPathToken.MappedArray mapped = (BodyPathToken.MappedArray) tokens.get(mappedIdx);
        Map<String, Object> current = root;
        for (int i = 0; i < mappedIdx; i++) {
            current = descendInto(current, path, tokens.get(i));
        }
        List<Object> arr = ensureList(current, path, mapped.key());

        if (!arr.isEmpty() && arr.size() != incoming.size()) {
            throw new BodyPathException.SizeMismatch(path, mapped.key(), arr.size(), incoming.size());
        }

        List<BodyPathToken> suffix = tokens.subList(mappedIdx + 1, tokens.size());
        for (int i = 0; i < incoming.size(); i++) {
            Object item = incoming.get(i);
            if (suffix.isEmpty()) {
                if (i < arr.size()) {
                    arr.set(i, item);
                } else {
                    arr.add(item);
                }
            } else {
                Map<String, Object> slot;
                if (i < arr.size()) {
                    Object existing = arr.get(i);
                    if (existing == null) {
                        slot = new LinkedHashMap<>();
                        arr.set(i, slot);
                    } else if (existing instanceof Map<?, ?> existingMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) existingMap;
                        slot = typed;
                    } else {
                        throw new BodyPathException.Conflict(path, mapped.key() + "[" + i + "]",
                                "Map", existing.getClass().getSimpleName());
                    }
                } else {
                    slot = new LinkedHashMap<>();
                    arr.add(slot);
                }
                applyLiteral(slot, path, suffix, item);
            }
        }
    }

    private static Map<String, Object> descendInto(Map<String, Object> current,
                                                   String path, BodyPathToken token) {
        return switch (token) {
            case BodyPathToken.Literal lit -> ensureMap(current, path, lit.key());
            case BodyPathToken.IndexedArray idx -> {
                List<Object> arr = ensureList(current, path, idx.key());
                ensureCapacity(arr, idx.index() + 1, path, idx.key());
                Object slot = arr.get(idx.index());
                if (slot == null) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    arr.set(idx.index(), child);
                    yield child;
                }
                if (slot instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    yield typed;
                }
                throw new BodyPathException.Conflict(path,
                        idx.key() + "[" + idx.index() + "]",
                        "Map", slot.getClass().getSimpleName());
            }
            case BodyPathToken.MappedArray mapped ->
                throw new BodyPathException.Grammar(path,
                        "unexpected '[]' segment '" + mapped.key() + "' encountered during descent");
        };
    }

    private static void writeTerminal(Map<String, Object> current, String path,
                                      BodyPathToken token, Object value) {
        switch (token) {
            case BodyPathToken.Literal lit -> current.put(lit.key(), value);
            case BodyPathToken.IndexedArray idx -> {
                List<Object> arr = ensureList(current, path, idx.key());
                ensureCapacity(arr, idx.index() + 1, path, idx.key());
                arr.set(idx.index(), value);
            }
            case BodyPathToken.MappedArray mapped ->
                throw new BodyPathException.Grammar(path,
                        "'[]' segment '" + mapped.key() + "' cannot be terminal in literal mode");
        }
    }

    private static Map<String, Object> ensureMap(Map<String, Object> current,
                                                 String path, String key) {
        Object existing = current.get(key);
        if (existing == null) {
            Map<String, Object> child = new LinkedHashMap<>();
            current.put(key, child);
            return child;
        }
        if (existing instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new BodyPathException.Conflict(path, key, "Map", existing.getClass().getSimpleName());
    }

    private static List<Object> ensureList(Map<String, Object> current,
                                           String path, String key) {
        Object existing = current.get(key);
        if (existing == null) {
            List<Object> arr = new ArrayList<>();
            current.put(key, arr);
            return arr;
        }
        if (existing instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) list;
            return typed;
        }
        throw new BodyPathException.Conflict(path, key, "List", existing.getClass().getSimpleName());
    }

    private static void ensureCapacity(List<Object> arr, int size, String path, String key) {
        if (size <= 0) return;
        if (arr.size() >= size) return;
        if (arr.size() < size - 1) {
            throw new BodyPathException.Grammar(path,
                    "sparse index - '" + key + "[" + (size - 1)
                            + "]' requires preceding indices to be set first");
        }
        arr.add(null);
    }
}
