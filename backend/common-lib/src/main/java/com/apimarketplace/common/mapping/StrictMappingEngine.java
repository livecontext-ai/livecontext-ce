package com.apimarketplace.common.mapping;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moteur de mapping strict & generique.
 *
 * Entrees :
 *   - rawJson  : reponse JSON (String ou bytes -> ici String)
 *   - mapping  : JSON du mapping strict (voir StrictMappingSpec ci-dessous)
 *
 * Sortie :
 *   - MappingOutcome : items mappes, nombre d'items, champs requis non resolus
 *
 * Points cles :
 *   - items_path : $.a.b[*].c[*]
 *   - Prefixes : "@." (relatif), "$." (absolu), "^^." (ascendant)
 *   - Chemins stricts (pas de filtres ni $..)
 *   - Fallbacks ordonnes par champ (candidates)
 *   - Conversions : string, integer, long, number, boolean, array<string>, array<number>
 */
public class StrictMappingEngine {

    // ---------- API publique ----------

    public static MappingOutcome apply(String rawJson, String mappingJson) throws IOException {
        ObjectMapper om = new ObjectMapper(new JsonFactory());

        StrictMappingSpec spec = om.readValue(mappingJson, StrictMappingSpec.class);
        JsonNode root = om.readTree(rawJson);

        // Resolution des items via items_path OU root_alternatives/racine
        List<JsonCursor> cursors = collectCursors(root, spec.source);

        // Pre-compile & cache tous les chemins candidats pour perf
        PathCache cache = PathCache.compile(spec);

        List<Map<String, Object>> items = new ArrayList<>(Math.max(16, cursors.size()));
        Set<String> unresolvedUnion = new LinkedHashSet<>();

        for (JsonCursor cur : cursors) {
            Map<String, Object> out = new LinkedHashMap<>();
            List<String> unresolved = new ArrayList<>();

            for (Map.Entry<String, FieldSpec> e : spec.fields.entrySet()) {
                String fieldName = e.getKey();
                FieldSpec fs = e.getValue();

                Object value = resolveField(cur, fs, cache);
                if (value == null) {
                    if (fs.defaultValue != null) {
                        value = fs.defaultValue;
                    }
                }
                if (value == null && Boolean.TRUE.equals(fs.required)) {
                    unresolved.add(fieldName);
                } else if (value != null) {
                    out.put(fieldName, value);
                }
            }

            if (!unresolved.isEmpty()) {
                unresolvedUnion.addAll(unresolved);
            }
            // On ajoute meme si incomplet : a toi de filtrer si besoin
            items.add(out);
        }

        MappingOutcome outcome = new MappingOutcome();
        outcome.items = items;
        outcome.itemCount = cursors.size();
        outcome.unresolvedFields = new ArrayList<>(unresolvedUnion);
        return outcome;
    }

    // ---------- Modeles de mapping (JSON -> POJO) ----------

    public static class StrictMappingSpec {
        public SourceSpec source = new SourceSpec();
        public Map<String, FieldSpec> fields = new LinkedHashMap<>();
        public MetaSpec meta; // optionnel (non utilise ici) - laisser null pour eviter les problemes de serialisation
    }

    public static class SourceSpec {
        public String format = "JSON";
        public String items_path; // ex: $.data.reels_media[*].items[*]
        public List<String> root_alternatives; // ex: ["$.data.items[*]", "$.items[*]"]
        public String root; // fallback si pas d'items_path
        public Map<String, Object> root_match; // non utilise ici, reserve

        // getters facultatifs si besoin d'integration
    }

    public static class FieldSpec {
        public List<String> candidates = List.of(); // chemins stricts ordonnes
        public String to = "string";               // type cible
        public Boolean required = false;
        public Object defaultValue;

        // Pour compat JSON : "default" dans ton mapping
        public void setDefault(Object d) { this.defaultValue = d; }
        public Object getDefault() { return defaultValue; }
    }

    public static class MetaSpec { }

    public static class MappingOutcome {
        public List<Map<String, Object>> items;
        public int itemCount;
        public List<String> unresolvedFields;
    }

    // ---------- Curseur et collecte d'items ----------

    /** Curseur = noeud courant + pile d'ancetres pour gerer ^^. */
    static final class JsonCursor {
        final JsonNode root;
        final JsonNode node;
        final Deque<JsonNode> ancestors; // parent direct en tete

        JsonCursor(JsonNode root, JsonNode node, Deque<JsonNode> ancestors) {
            this.root = root;
            this.node = node;
            this.ancestors = ancestors;
        }

        JsonNode ascend(int up) {
            if (up <= 0) return node;
            Iterator<JsonNode> it = ancestors.iterator();
            JsonNode cur = node;
            int count = 0;
            while (count < up && it.hasNext()) {
                cur = it.next();
                count++;
            }
            return cur;
        }
    }

    /** Encapsule un noeud + parent chain (pour items_path traversal). */
    static final class NodeFrame {
        final JsonNode node;
        final NodeFrame parent;
        NodeFrame(JsonNode node, NodeFrame parent) {
            this.node = node;
            this.parent = parent;
        }
    }

    /** Collecte des items selon items_path ou alternatives/root. */
    static List<JsonCursor> collectCursors(JsonNode root, SourceSpec src) {
        List<NodeFrame> frames;

        if (notBlank(src.items_path)) {
            frames = walkAbsolute(root, src.items_path);
        } else if (src.root_alternatives != null && !src.root_alternatives.isEmpty()) {
            frames = new ArrayList<>();
            for (String alt : src.root_alternatives) {
                List<NodeFrame> r = walkAbsolute(root, alt);
                if (!r.isEmpty()) { frames = r; break; }
            }
            if (frames.isEmpty() && notBlank(src.root)) {
                frames = walkAbsolute(root, src.root);
            }
        } else if (notBlank(src.root)) {
            frames = walkAbsolute(root, src.root);
        } else {
            // fallback: si root est un array, chaque element est un item, sinon unique item
            frames = root.isArray()
                    ? arrayToFrames(root, null)
                    : List.of(new NodeFrame(root, null));
        }

        List<JsonCursor> out = new ArrayList<>(frames.size());
        for (NodeFrame f : frames) {
            Deque<JsonNode> ancestors = new ArrayDeque<>();
            NodeFrame p = f.parent;
            while (p != null) { 
                ancestors.addLast(p.node); // Add parent first, then grandparent, etc.
                p = p.parent; 
            }
            out.add(new JsonCursor(root, f.node, ancestors));
        }
        return out;
    }

    static List<NodeFrame> arrayToFrames(JsonNode array, NodeFrame parent) {
        List<NodeFrame> out = new ArrayList<>(array.size());
        for (JsonNode el : array) out.add(new NodeFrame(el, parent));
        return out;
    }

    /** Parcours un chemin absolu strict ($.a.b[*].c[0]...). */
    static List<NodeFrame> walkAbsolute(JsonNode root, String absolutePath) {
        if (!notBlank(absolutePath) || !absolutePath.startsWith("$"))
            throw new IllegalArgumentException("Chemin absolu invalide: " + absolutePath);

        List<String> tokens = tokenize(trimDollar(absolutePath));
        List<NodeFrame> frames = List.of(new NodeFrame(root, null));

        for (String token : tokens) {
            List<NodeFrame> next = new ArrayList<>();
            for (NodeFrame f : frames) {
                next.addAll(step(f, token));
            }
            frames = next;
            if (frames.isEmpty()) break;
        }
        return frames;
    }

    /** etape : "field", "field[*]", "field[0]" */
    static List<NodeFrame> step(NodeFrame frame, String token) {
        List<NodeFrame> out = new ArrayList<>();
        JsonNode base = frame.node;

        String field = token;
        String bracket = null;
        int b = token.indexOf('[');
        if (b >= 0) {
            field = token.substring(0, b);
            bracket = token.substring(b + 1, token.length() - 1);
        }

        JsonNode target = base;
        if (!field.isEmpty()) {
            target = base.get(field);
            if (target == null) return out;
        }

        if (bracket == null) {
            out.add(new NodeFrame(target, frame));
            return out;
        }

        if (!target.isArray()) return out;

        if ("*".equals(bracket)) {
            NodeFrame arrayFrame = new NodeFrame(target, frame);
            for (JsonNode el : target) {
                out.add(new NodeFrame(el, arrayFrame));
            }
        } else {
            int idx = parseIndex(bracket);
            if (idx >= 0 && idx < target.size()) {
                out.add(new NodeFrame(target.get(idx), new NodeFrame(target, frame)));
            }
        }
        return out;
    }

    static int parseIndex(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    static String trimDollar(String p) { return p.startsWith("$.") ? p.substring(2) : p.substring(1); }
    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    // ---------- Resolution de champs (fallbacks + conversions) ----------

    static Object resolveField(JsonCursor cur, FieldSpec fs, PathCache cache) {
        if (fs == null || fs.candidates == null || fs.candidates.isEmpty()) return null;

        // array<*> → on collecte potentiellement plusieurs resultats
        boolean wantArray = fs.to != null && fs.to.startsWith("array<");

        if (wantArray) {
            List<JsonNode> agg = new ArrayList<>();
            for (String cand : fs.candidates) {
                List<JsonNode> nodes = resolvePath(cur, cand, cache);
                if (!nodes.isEmpty()) agg.addAll(nodes);
            }
            if (agg.isEmpty()) return null;
            return castArray(agg, fs.to);
        } else {
            for (String cand : fs.candidates) {
                List<JsonNode> nodes = resolvePath(cur, cand, cache);
                JsonNode n = firstNonNull(nodes);
                if (n != null && !n.isNull()) {
                    Object v = castScalar(n, fs.to);
                    if (v != null) return v;
                }
            }
            return null;
        }
    }

    static JsonNode firstNonNull(List<JsonNode> nodes) {
        for (JsonNode n : nodes) if (n != null && !n.isNull() && !n.isMissingNode()) return n;
        return null;
    }

    // ---------- Resolution d'un chemin generique (avec @, ^^, $) ----------

    static List<JsonNode> resolvePath(JsonCursor cur, String path, PathCache cache) {
        if (!notBlank(path)) return List.of();

        StartRef start = parseStart(path);
        String body = start.body;

        List<String> tokens = cache.tokens(body);
        List<JsonNode> current;

        switch (start.kind) {
            case RELATIVE: current = List.of(cur.node); break;
            case ABSOLUTE: current = List.of(cur.root); break;
            case ASCEND:   current = List.of(cur.ascend(start.up)); break;
            default:       current = List.of(cur.node);
        }

        for (String token : tokens) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode base : current) {
                stepNodes(base, token, next);
            }
            current = next;
            if (current.isEmpty()) break;
        }
        return current;
    }

    static void stepNodes(JsonNode base, String token, List<JsonNode> out) {
        String field = token;
        String bracket = null;
        int b = token.indexOf('[');
        if (b >= 0) {
            field = token.substring(0, b);
            bracket = token.substring(b + 1, token.length() - 1);
        }

        JsonNode target = base;
        if (!field.isEmpty()) {
            target = base.get(field);
            if (target == null) return;
        }

        if (bracket == null) {
            out.add(target);
            return;
        }

        if (!target.isArray()) return;

        if ("*".equals(bracket)) {
            for (JsonNode el : target) out.add(el);
        } else {
            int idx = parseIndex(bracket);
            if (idx >= 0 && idx < target.size()) out.add(target.get(idx));
        }
    }

    // ---------- Parsing du prefixe (@, $, ^^) ----------

    enum StartKind { RELATIVE, ABSOLUTE, ASCEND }

    static final class StartRef {
        final StartKind kind;
        final int up;     // pour ASCEND
        final String body;
        StartRef(StartKind kind, int up, String body) {
            this.kind = kind; this.up = up; this.body = body;
        }
    }

    static StartRef parseStart(String p) {
        if (p.startsWith("@.")) {
            return new StartRef(StartKind.RELATIVE, 0, p.substring(2));
        }
        if (p.startsWith("$.")) {
            return new StartRef(StartKind.ABSOLUTE, 0, p.substring(2));
        }
        if (p.startsWith("$")) { // "$[...]" ou "$field"
            return new StartRef(StartKind.ABSOLUTE, 0, trimDollar(p));
        }
        // ^^.^^.field  → compter les "^^."
        int up = 0;
        String rest = p;
        while (rest.startsWith("^^.")) {
            up++;
            rest = rest.substring(3);
        }
        if (up > 0) return new StartRef(StartKind.ASCEND, up, rest);

        // Par defaut, considerer relatif
        return new StartRef(StartKind.RELATIVE, 0, p.startsWith(".") ? p.substring(1) : p);
    }

    // ---------- Token cache for performance ----------

    static final class PathCache {
        private final Map<String, List<String>> tokenCache = new ConcurrentHashMap<>();

        List<String> tokens(String body) {
            return tokenCache.computeIfAbsent(body, StrictMappingEngine::tokenize);
        }

        static PathCache compile(StrictMappingSpec spec) {
            PathCache cache = new PathCache();
            // Pre-chauffer : items_path / root_alternatives / root deja traites par collectCursors
            for (FieldSpec fs : spec.fields.values()) {
                if (fs.candidates != null) {
                    for (String cand : fs.candidates) {
                        String body = parseStart(cand).body;
                        cache.tokens(body);
                    }
                }
            }
            return cache;
        }
    }

    static List<String> tokenize(String pathBody) {
        if (!notBlank(pathBody)) return List.of();
        // split par '.' mais conserver [..]
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int bracket = 0;
        for (int i = 0; i < pathBody.length(); i++) {
            char c = pathBody.charAt(i);
            if (c == '[') bracket++;
            if (c == ']') bracket--;
            if (c == '.' && bracket == 0) {
                tokens.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    // ---------- Conversions ----------

    static Object castScalar(JsonNode n, String to) {
        if (n == null || n.isNull()) return null;
        String t = (to == null) ? "string" : to.toLowerCase(Locale.ROOT);

        switch (t) {
            case "string":
                if (n.isTextual()) return n.asText();
                if (n.isNumber() || n.isBoolean()) return n.asText();
                return n.isValueNode() ? n.asText() : n.toString();

            case "integer":
                if (n.isInt() || n.isLong() || n.isNumber()) return n.intValue();
                return tryParseInt(n.asText());

            case "long":
                if (n.isLong() || n.isInt() || n.isNumber()) return n.longValue();
                return tryParseLong(n.asText());

            case "number":
                if (n.isNumber()) return n.doubleValue();
                return tryParseDouble(n.asText());

            case "boolean":
                if (n.isBoolean()) return n.booleanValue();
                String s = n.asText();
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
                return null;

            default:
                // types array<...> geres ailleurs
                return null;
        }
    }

    static Object castArray(List<JsonNode> nodes, String to) {
        String t = to == null ? "" : to.toLowerCase(Locale.ROOT);
        if (t.startsWith("array<string")) {
            List<String> out = new ArrayList<>(nodes.size());
            for (JsonNode n : nodes) {
                if (n == null || n.isNull()) continue;
                if (n.isArray()) { // aplatir si un sous-niveau reste array
                    for (JsonNode el : n) if (!el.isNull()) out.add(el.asText());
                } else {
                    out.add(n.asText());
                }
            }
            return out;
        }
        if (t.startsWith("array<number")) {
            List<Double> out = new ArrayList<>(nodes.size());
            for (JsonNode n : nodes) {
                if (n == null || n.isNull()) continue;
                if (n.isNumber()) out.add(n.doubleValue());
                else {
                    Double v = tryParseDouble(n.asText());
                    if (v != null) out.add(v);
                }
            }
            return out;
        }
        // fallback generique → array de string
        List<String> out = new ArrayList<>(nodes.size());
        for (JsonNode n : nodes) if (n != null && !n.isNull()) out.add(n.asText());
        return out;
    }

    static Integer tryParseInt(String s) { try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; } }
    static Long    tryParseLong(String s){ try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; } }
    static Double  tryParseDouble(String s){ try { return Double.valueOf(s.trim()); } catch (Exception e) { return null; } }

}
