package com.apimarketplace.catalog.mapping.service;

import com.apimarketplace.catalog.mapping.SourceFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Detect the input payload format.
 * Priority (revisitee) :
 *   1) Content-Type (autoritatif SEULEMENT si specifique : json/xml/html/csv/ndjson)
 *   2) Magic Bytes (PDF/ZIP/PNG/JPG/GIF/WEBP/GZIP/others)
 *   3) Decompression (GZIP) puis heuristiques texte (JSON / NDJSON / XML / HTML / CSV / TEXT)
 *   => Sinon BINARY.
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    private final ObjectMapper objectMapper;

    public DetectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // --- Magic bytes (subset) ---
    private static final byte[] PDF_MAGIC   = {0x25, 0x50, 0x44, 0x46};             // %PDF
    private static final byte[] ZIP_MAGIC   = {0x50, 0x4B, 0x03, 0x04};             // PK\003\004
    private static final byte[] PNG_MAGIC   = {(byte)0x89, 0x50, 0x4E, 0x47};       // \x89PNG
    private static final byte[] JPG_MAGIC   = {(byte)0xFF, (byte)0xD8, (byte)0xFF}; // JPEG SOI
    private static final byte[] GIF_MAGIC   = {0x47, 0x49, 0x46, 0x38};             // GIF8
    private static final byte[] WEBP_MAGIC  = {0x52, 0x49, 0x46, 0x46};             // RIFF....WEBP
    private static final byte[] GZIP_MAGIC  = {(byte)0x1F, (byte)0x8B};             // GZIP

    // --- Text patterns (loose) ---
    private static final Pattern EMAIL_RE = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern URL_RE   = Pattern.compile("https?://\\S+");
    private static final Pattern DATE_RE  = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{2}/\\d{2}/\\d{4}\\b|\\b\\d{2}-\\d{2}-\\d{4}\\b");

    // --- HTML hints ---
    private static final Pattern HTML_DOCTYPE = Pattern.compile("(?i)<!DOCTYPE\\s+html");
    private static final Pattern HTML_TAG_HINT = Pattern.compile("(?is)<(html|head|body|meta|script|title)\\b");

    /**
     * Detect format from payload and optional Content-Type header.
     */
    public SourceFormat detect(byte[] payload, String contentType) {
        if (payload == null || payload.length == 0) return SourceFormat.BINARY;

        // 1) Content-Type (autoritatif seulement s'il est specifique)
        SourceFormat byCt = detectFromContentType(contentType);
        if (byCt != null) {
            log.debug("Detected by specific Content-Type: {}", byCt);
            return byCt;
        }

        // 2) Magic bytes (quick wins)
        SourceFormat magic = detectFromMagicBytes(payload);
        if (magic != null) {
            // Si GZIP, on decompresse puis on recommence la detection texte.
            if (magic == SourceFormat.BINARY && startsWith(payload, GZIP_MAGIC)) {
                byte[] unzipped = tryGunzip(payload);
                if (unzipped != null && unzipped.length > 0) {
                    log.debug("GZIP detected → retrying detection on decompressed content");
                    // Rejouer CT specifique (si initialement generique), puis heuristiques
                    SourceFormat inner = detectTextOrBinary(unzipped);
                    if (inner != null) return inner;
                }
            }
            return magic; // PDF/ZIP/PNG/JPG/GIF/WEBP → BINARY
        }

        // 3) Heuristiques texte (avec BOM/encodage)
        SourceFormat detected = detectTextOrBinary(payload);
        return detected != null ? detected : SourceFormat.BINARY;
    }

    // ----- Content-Type -----
    private SourceFormat detectFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) return null;
        String ct = contentType.toLowerCase(Locale.ROOT).trim();

        // Types specifiques (autoritatifs)
        if (ct.contains("application/json") || ct.contains("text/json")) return SourceFormat.JSON;
        if (ct.contains("application/x-ndjson") || ct.contains("application/ndjson")) return SourceFormat.JSON; // NDJSON traite comme JSON
        if (ct.contains("application/xml") || ct.contains("text/xml")
            || ct.contains("application/xhtml+xml")
            || ct.contains("application/rss+xml")
            || ct.contains("application/atom+xml")) return SourceFormat.XML;
        if (ct.contains("text/html")) return SourceFormat.HTML;
        if (ct.contains("text/csv") || ct.contains("application/csv")) return SourceFormat.CSV;

        // Types generiques → on NE conclut PAS, on laisse les heuristiques decider
        if (ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/")
            || ct.contains("application/pdf")
            || ct.contains("application/zip")
            || ct.contains("application/octet-stream")
            || ct.contains("text/plain")
            || ct.contains("application/vnd.ms-excel") // souvent CSV mais pas sûr
            || ct.contains("application/vnd.openxmlformats-officedocument")) {
            log.debug("Generic Content-Type '{}': falling back to magic/heuristics", contentType);
            return null;
        }

        return null;
    }

    // ----- Magic bytes -----
    private SourceFormat detectFromMagicBytes(byte[] buf) {
        if (startsWith(buf, PDF_MAGIC))  return SourceFormat.BINARY;
        if (startsWith(buf, ZIP_MAGIC))  return SourceFormat.BINARY;
        if (startsWith(buf, PNG_MAGIC))  return SourceFormat.BINARY;
        if (startsWith(buf, JPG_MAGIC))  return SourceFormat.BINARY;
        if (startsWith(buf, GIF_MAGIC))  return SourceFormat.BINARY;
        if (startsWith(buf, WEBP_MAGIC)) return SourceFormat.BINARY;
        if (startsWith(buf, GZIP_MAGIC)) return SourceFormat.BINARY; // On traitera GZIP plus bas
        return null;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    // ----- Text path (decode + heuristics) -----
    private SourceFormat detectTextOrBinary(byte[] payload) {
        // Detecter BOM / encodage de base
        Decoded decoded = decodeWithBom(payload);
        if (decoded == null) return SourceFormat.BINARY;

        String text = decoded.text;
        if (text == null) return SourceFormat.BINARY;

        // Trop de caracteres de contrôle → probablement binaire
        if (looksBinary(text)) return SourceFormat.BINARY;

        // NDJSON en premier (plus discriminant que JSON simple)
        if (isValidNdjson(text)) return SourceFormat.JSON;

        // JSON strict
        if (isValidJson(text)) return SourceFormat.JSON;

        // XML vs HTML : on essaie les deux puis on tranche avec des hints HTML
        boolean xml = isValidXml(text);
        boolean html = isValidHtml(text);

        if (xml && html) {
            // Heuristique : doctype HTML, balises html/head/body/meta/script…
            if (HTML_DOCTYPE.matcher(text).find() || HTML_TAG_HINT.matcher(text).find()) {
                return SourceFormat.HTML;
            }
            // Pas de signaux HTML forts → XML
            return SourceFormat.XML;
        }
        if (xml)  return SourceFormat.XML;
        if (html) return SourceFormat.HTML;

        // CSV/TSV
        if (isLikelyCsv(text)) return SourceFormat.CSV;

        // Texte structure / brut
        if (isStructuredText(text)) return SourceFormat.TEXT;

        return SourceFormat.TEXT;
    }

    // ----- JSON / NDJSON -----
    private boolean isValidJson(String s) {
        String trimmed = stripBom(s).trim();
        // Heuristique rapide de forme pour eviter Jsoup/HTML emboîte
        if (!( (trimmed.startsWith("{") && trimmed.endsWith("}"))
               || (trimmed.startsWith("[") && trimmed.endsWith("]")) )) {
            // Permet quand meme du JSON “pretty” mais s’assure de la forme globale
            // Si ce pre-test echoue, on tente quand meme Jackson (certains JSON peuvent avoir des BOM ou espaces)
        }
        try {
            objectMapper.readTree(trimmed);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isValidNdjson(String s) {
        // NDJSON : plusieurs lignes, chaque ligne JSON valide (on autorise lignes vides/espaces)
        String[] lines = s.split("\\r?\\n");
        int parsed = 0, total = 0;
        for (int i = 0; i < Math.min(lines.length, 200); i++) {
            String line = stripBom(lines[i]).trim();
            if (line.isEmpty()) continue;
            total++;
            if (line.startsWith("{") || line.startsWith("[")) {
                try {
                    objectMapper.readTree(line);
                    parsed++;
                } catch (Exception ignored) { /* not this line */ }
            }
        }
        // ≥3 lignes JSON valides ou ≥60% des lignes non vides parsees
        return total >= 3 && (parsed >= 3 || (total > 0 && parsed >= Math.max(1, (int)Math.round(total * 0.6))));
    }

    // ----- XML / HTML -----
    private boolean isValidXml(String s) {
        try {
            String trimmed = stripBom(s).trim();
            // Exclusion rapide : si doctype HTML explicite, ne pas considerer XML
            if (HTML_DOCTYPE.matcher(trimmed).find()) return false;

            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // XXE hardening
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {}
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);

            DocumentBuilder b = f.newDocumentBuilder();
            b.parse(new InputSource(new StringReader(trimmed)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isValidHtml(String s) {
        try {
            String trimmed = stripBom(s).trim();
            // Hints forts
            if (HTML_DOCTYPE.matcher(trimmed).find()) return true;
            if (HTML_TAG_HINT.matcher(trimmed).find()) return true;

            // Jsoup parse (tolerant). On exige ≥1 des elements structurants.
            Document doc = Jsoup.parse(trimmed);
            return !doc.select("html, head, body").isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Heuristic CSV detector:
     * - ≥ 2 lignes non vides
     * - Delimiteur parmi , ; \\t |
     * - Compte de colonnes stable (faible variance)
     * - Gestion des quotes / champs vides
     */
    private boolean isLikelyCsv(String s) {
        String[] rawLines = s.split("\\r?\\n", -1);
        // Keep up to 200 non-empty lines
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String t = l.trim();
            if (!t.isEmpty()) lines.add(l);
            if (lines.size() >= 200) break;
        }
        if (lines.size() < 2) return false;

        char[] candidates = new char[]{',', ';', '\t', '|'};
        char bestDelim = 0;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (char d : candidates) {
            int[] cols = new int[lines.size()];
            int i = 0;
            int quotedMismatches = 0;
            for (String line : lines) {
                cols[i++] = splitCsvLike(line, d).length;
                if (!balancedQuotes(line)) quotedMismatches++;
            }
            double mean = Arrays.stream(cols).average().orElse(0);
            double var = 0;
            for (int c : cols) var += (c - mean) * (c - mean);
            var /= Math.max(1, cols.length);

            // score: + colonnes, - variance, - desequilibre de quotes
            double score = mean - Math.sqrt(var) - (quotedMismatches * 0.25);
            if (score > bestScore) {
                bestScore = score;
                bestDelim = d;
            }
        }
        if (bestDelim == 0) return false;

        // Verifier l’entete : ≥ 2 colonnes “lisibles”
        String[] headerCols = splitCsvLike(lines.get(0), bestDelim);
        if (headerCols.length < 2) return false;
        int humanish = 0;
        for (String c : headerCols) {
            String t = c.replaceAll("^\"|\"$", "").trim();
            if (t.matches("[\\p{Alnum}_ .()/#-]{1,64}")) humanish++;
        }
        if (humanish < Math.max(1, headerCols.length - 1)) return false;

        // Quelques lignes doivent avoir nbColonnes ≈ entete
        int ok = 0;
        int ref = headerCols.length;
        for (int i = 1; i < Math.min(lines.size(), 10); i++) {
            int n = splitCsvLike(lines.get(i), bestDelim).length;
            if (Math.abs(n - ref) <= 1) ok++;
        }
        return ok >= Math.min(2, Math.max(1, Math.min(lines.size() - 1, 3)));
    }

    private String[] splitCsvLike(String line, char delim) {
        // Split CSV minimaliste avec gestion des quotes double
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                // double quote -> escape
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (ch == delim && !inQuote) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private boolean balancedQuotes(String line) {
        int quotes = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '"') quotes++;
        }
        return (quotes % 2) == 0;
    }

    // ----- Structured text -----
    private boolean isStructuredText(String s) {
        boolean hasEmail = EMAIL_RE.matcher(s).find();
        boolean hasUrl   = URL_RE.matcher(s).find();
        boolean hasDate  = DATE_RE.matcher(s).find();
        boolean hasKv    = s.contains(":") && s.contains("\n");
        return hasEmail || hasUrl || hasDate || hasKv;
    }

    // ----- Decode / BOM / Binary look -----
    private static class Decoded {
        final String text;
        final Charset charset;
        Decoded(String text, Charset charset) { this.text = text; this.charset = charset; }
    }

    private Decoded decodeWithBom(byte[] payload) {
        if (payload.length >= 3 && (payload[0] & 0xFF) == 0xEF && (payload[1] & 0xFF) == 0xBB && (payload[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(payload, 3, payload.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        // UTF-16 LE/BE
        if (payload.length >= 2) {
            int b0 = payload[0] & 0xFF;
            int b1 = payload[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return new Decoded(new String(payload, 2, payload.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE);
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return new Decoded(new String(payload, 2, payload.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE);
            }
        }
        // UTF-32 (tres rare, mais on evite les faux positifs binaires)
        if (payload.length >= 4) {
            int bom = ByteBuffer.wrap(Arrays.copyOfRange(payload, 0, 4)).order(ByteOrder.BIG_ENDIAN).getInt();
            if (bom == 0x0000FEFF) {
                return new Decoded(new String(payload, 4, payload.length - 4, Charset.forName("UTF-32BE")), Charset.forName("UTF-32BE"));
            }
            if (bom == 0xFFFE0000) {
                return new Decoded(new String(payload, 4, payload.length - 4, Charset.forName("UTF-32LE")), Charset.forName("UTF-32LE"));
            }
        }
        // Essai UTF-8 puis ISO-8859-1 (fallback)
        try {
            return new Decoded(new String(payload, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            try {
                String iso = new String(payload, StandardCharsets.ISO_8859_1);
                if (looksBinary(iso)) return null;
                return new Decoded(iso, StandardCharsets.ISO_8859_1);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private String stripBom(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private boolean looksBinary(String s) {
        // Heuristique plus stricte : NUL, contrôle non whitespace, tres faible proportion de lettres
        int ctrl = 0, nul = 0, alpha = 0;
        int len = Math.min(s.length(), 8192);
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (ch == 0) nul++;
            if (Character.isISOControl(ch) && !Character.isWhitespace(ch)) ctrl++;
            if (Character.isLetter(ch)) alpha++;
        }
        if (nul > 0) return true;
        if (ctrl > len / 20) return true;         // >5% contrôles non blancs
        return alpha < len / 20 && len > 200; // <5% lettres sur >200 chars
    }

    private int countOccurrences(String str, char ch) {
        int c = 0;
        for (int i = 0; i < str.length(); i++) if (str.charAt(i) == ch) c++;
        return c;
    }

    private byte[] tryGunzip(byte[] gz) {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = gis.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        } catch (Exception e) {
            log.debug("GZIP decompression failed: {}", e.getMessage());
            return null;
        }
    }
}
