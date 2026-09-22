package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.storage.client.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.Map;

/**
 * Turns an input file into what one specific provider wants it as.
 *
 * <p>Every generation surface hands a reference image, voice or clip over as a
 * FileRef: the whole output of an upstream node, a file the agent already has,
 * a file the user just picked. That handle is the platform's, and no provider
 * has ever heard of it. Providers instead want a data URL, bare base64 beside a
 * media type, a link they fetch themselves, or a multipart part. This class is
 * the one place that conversion happens, driven entirely by the encoding the
 * descriptor declares.
 *
 * <p><b>It runs before the money moves.</b> Everything it can refuse (an
 * unreadable handle, a file that is gone, one too large to inline) is refused
 * while the call still costs nothing. Converting after the reservation would
 * turn a fixable mistake into a charge for a call that was never dispatched.
 */
@Slf4j
@Service
public class GenerationInputResolver {

    /** Field every FileRef carries, and the only one this class needs to find the bytes. */
    private static final String STORAGE_KEY = "path";

    private final StorageClient storageClient;
    private final long maxInlineBytes;

    public GenerationInputResolver(
            @Autowired(required = false) StorageClient storageClient,
            @Value("${generation.input.max-inline-bytes:20971520}") long maxInlineBytes) {
        this.storageClient = storageClient;
        this.maxInlineBytes = maxInlineBytes;
    }

    /**
     * Outcome of preparing the input assets of one call.
     *
     * @param errors what the caller must fix; when non-empty the request must
     *               not be dispatched. Worded for whoever supplied the file,
     *               since that is who can supply a different one.
     */
    public record Prepared(List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Rewrite every input asset in a built request into the provider's form.
     *
     * <p>Mutates {@code request} in place, which is the shape the caller already
     * has from {@link GenerationRequestBuilder#build}. A call with no input
     * asset touches nothing and costs nothing.
     *
     * @param spec     descriptor of the endpoint being called
     * @param request  the built upstream request, rewritten in place
     * @param tenantId owner whose storage the files are read from
     */
    public Prepared prepare(GenerationSpec spec, Map<String, Object> request, String tenantId) {
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, GenerationSpec.ParamBinding> entry : spec.paramMap().entrySet()) {
            GenerationSpec.ParamBinding binding = entry.getValue();
            if (binding.encoding() == null) continue;

            Object present = GenerationRequestBuilder.getByPath(request, binding.path());
            if (present == null) continue;   // the caller did not send this asset

            // Several files in one slot. The path names where the FIRST goes and
            // the rest walk forward from it, so each lands somewhere of its own
            // rather than overwriting the one before.
            if (present instanceof List<?> several) {
                prepareSeveral(binding, entry.getKey(), several, request, tenantId, errors);
                continue;
            }

            // Checked for EVERY encoding, including the multipart one that needs
            // no conversion. The multipart encoder further down logs and drops a
            // part it cannot read, so the request goes out without the image and
            // the provider answers "image is required": an error that points
            // nowhere near the caller's actual mistake, on a call that has been
            // dispatched. Refusing here is the promise this class makes.
            String storageKey = storageKeyOf(present);
            if (storageKey == null) {
                errors.add("'" + entry.getKey() + "' must be a file, given as the whole file object "
                        + "another step produced. A path or a URL on its own is not one, because the "
                        + "platform has to read the bytes to hand them to this provider.");
                continue;
            }

            // Past the check, the multipart encoder downloads the bytes itself,
            // so the FileRef has to survive this far untouched: converting it
            // here would send the same file twice. What still has to happen is
            // asking whether the bytes are THERE. The encoder logs and drops a
            // part it cannot read, so a handle whose file is gone, empty or
            // owned by another workspace goes out as a request without the
            // image, and the provider answers "image is required" on a call
            // already dispatched. The two inline encodings learn this from the
            // download they do anyway; this one has to ask.
            if (binding.encoding() == GenerationSpec.AssetEncoding.FILE_REF) {
                if (!readable(storageKey, tenantId, entry.getKey(), errors)) continue;
                continue;
            }

            // The size the FileRef recorded at upload, read BEFORE the download.
            // Refusing afterwards means a 100 MB file has already been pulled
            // into the heap to be told it is too big.
            //
            // Below the multipart branch on purpose: this cap is about putting a
            // file INSIDE a request body, and a multipart part is streamed as
            // its own part instead. Applying it there would refuse a file the
            // provider would have accepted.
            if (tooLargeToInline(present, entry.getKey(), errors)) continue;

            // The element's own metadata travels with the file here too. A one-file slot on an
            // array endpoint is still an element, and an element without its type is refused.
            writeItemConstants(binding, 0, request, errors);

            switch (binding.encoding()) {
                case DATA_URL -> download(storageKey, tenantId, entry.getKey(), errors)
                        .ifPresent(bytes -> GenerationRequestBuilder.setByPath(request, binding.path(),
                                "data:" + mimeOf(present, bytes) + ";base64,"
                                        + Base64.getEncoder().encodeToString(bytes),
                                errors));
                case BASE64 -> download(storageKey, tenantId, entry.getKey(), errors)
                        .ifPresent(bytes -> {
                            GenerationRequestBuilder.setByPath(request, binding.path(),
                                    Base64.getEncoder().encodeToString(bytes), errors);
                            if (binding.mimePath() != null) {
                                GenerationRequestBuilder.setByPath(request, binding.mimePath(),
                                        mimeOf(present, bytes), errors);
                            }
                        });
                default -> { /* FILE_REF returned above */ }
            }
        }

        // Last, because everything above is still writing into the request: the
        // holes this closes are made by the very slots it just filled, and by the
        // ones it found nothing for. Run unconditionally - a call with no input
        // asset at all can still carry an array whose scaffolding outlived the
        // value it was built for.
        GenerationRequestBuilder.pruneEmpty(request);

        return new Prepared(errors);
    }

    /**
     * Convert a list of files into the consecutive positions the provider reads.
     *
     * <p>The declared path names the first one; each subsequent file moves the
     * LAST index in that path forward by one. That is why no new syntax was
     * invented for this: Gemini's images sit after its text part, so an
     * expansion that always began at zero would overwrite the prompt, while
     * {@code contents[0].parts[1]...} says both where to start and where to go
     * next.
     *
     * <p>The whole list is refused as a unit if any single file cannot be read.
     * Sending three of four images and calling it a success would produce a
     * result the caller paid for and did not ask for.
     */
    private void prepareSeveral(GenerationSpec.ParamBinding binding, String param,
                                 List<?> files, Map<String, Object> request,
                                 String tenantId, List<String> errors) {
        if (files.isEmpty()) {
            // Nothing to send, and nothing to complain about: an empty list is
            // how a surface says "no file here" when the field takes several.
            GenerationRequestBuilder.setByPath(request, binding.path(), null, errors);
            return;
        }
        if (files.size() > binding.maxItems()) {
            errors.add("'" + param + "' takes at most " + binding.maxItems()
                    + " file" + (binding.maxItems() == 1 ? "" : "s") + " on this model, and "
                    + files.size() + " were given.");
            return;
        }

        List<Object> converted = new ArrayList<>(files.size());
        for (Object file : files) {
            Object one = convertOne(binding, param, file, tenantId, errors);
            if (one == NOT_CONVERTED) return;
            converted.add(one);
        }
        // Written only once every file has converted, so a half-filled request
        // never reaches the provider.
        for (int i = 0; i < converted.size(); i++) {
            String path = shiftLastIndex(binding.path(), i);
            GenerationRequestBuilder.setByPath(request, path, converted.get(i), errors);
            if (binding.mimePath() != null) {
                GenerationRequestBuilder.setByPath(request, shiftLastIndex(binding.mimePath(), i),
                        mimeOf(files.get(i), null), errors);
            }
            writeItemConstants(binding, i, request, errors);
        }
    }

    /**
     * Write the fields that belong BESIDE one file, moved to that file's position.
     *
     * <p>Some providers take an object per array element rather than a bare value, and the object
     * carries fields the file cannot supply - the element's type, and what the file is FOR. Those
     * cannot be the endpoint's ordinary constants: those are written once, at a fixed path, whether
     * or not a file was given, so an absent file would leave an element with a type and no value.
     * Written from here they exist only for files that are actually present, and they shift exactly
     * as the file's own path does, so element 2's type lands beside element 2's value.
     */
    private void writeItemConstants(GenerationSpec.ParamBinding binding, int index,
                                     Map<String, Object> request, List<String> errors) {
        if (binding.itemConstants().isEmpty()) return;
        for (Map.Entry<String, Object> constant : binding.itemConstants().entrySet()) {
            GenerationRequestBuilder.setByPath(request,
                    shiftLastIndex(constant.getKey(), index), constant.getValue(), errors);
        }
    }

    /** Marker for "this file did not convert", distinct from a legitimate null. */
    private static final Object NOT_CONVERTED = new Object();

    /** One file, in the form this binding asks for, or {@link #NOT_CONVERTED}. */
    private Object convertOne(GenerationSpec.ParamBinding binding, String param,
                               Object file, String tenantId, List<String> errors) {
        String storageKey = storageKeyOf(file);
        if (storageKey == null) {
            errors.add("'" + param + "' must be a file, given as the whole file object another "
                    + "step produced. A path or a URL on its own is not one, because the platform "
                    + "has to read the bytes to hand them to this provider.");
            return NOT_CONVERTED;
        }
        if (binding.encoding() == GenerationSpec.AssetEncoding.FILE_REF) {
            return readable(storageKey, tenantId, param, errors) ? file : NOT_CONVERTED;
        }
        if (tooLargeToInline(file, param, errors)) return NOT_CONVERTED;
        java.util.Optional<byte[]> bytes = download(storageKey, tenantId, param, errors);
        if (bytes.isEmpty()) return NOT_CONVERTED;
        String encoded = Base64.getEncoder().encodeToString(bytes.get());
        return binding.encoding() == GenerationSpec.AssetEncoding.DATA_URL
                ? "data:" + mimeOf(file, bytes.get()) + ";base64," + encoded
                : encoded;
    }

    /**
     * The same path with its LAST index moved forward by {@code by}.
     *
     * <p>The last one rather than the first: {@code contents[0].parts[1]} keeps
     * the single conversation and walks the parts, which is the shape every
     * multi-image provider here uses.
     */
    static String shiftLastIndex(String path, int by) {
        if (by == 0) return path;
        Matcher last = null;
        Matcher m = GenerationSpec.INDEXED_SEGMENT.matcher(path);
        int start = -1, end = -1, value = 0;
        while (m.find()) {
            start = m.start();
            end = m.end();
            value = Integer.parseInt(m.group(1));
            last = m;
        }
        if (last == null) return path;
        return path.substring(0, start) + "[" + (value + by) + "]" + path.substring(end);
    }

    /**
     * Is the file actually there, under the tenant asking for it? Used by the
     * encoding that never downloads, so its refusal happens here rather than as
     * a provider error on a dispatched call.
     */
    private boolean readable(String storageKey, String tenantId, String param, List<String> errors) {
        if (storageClient == null) {
            errors.add("'" + param + "' could not be read: file storage is unavailable on this install.");
            return false;
        }
        boolean there;
        try {
            there = storageClient.exists(tenant(tenantId), storageKey);
        } catch (RuntimeException e) {
            log.error("GenerationInputResolver: existence check failed for {} ({}): {}",
                    storageKey, param, e.getMessage());
            there = false;
        }
        if (!there) {
            errors.add(unreadable(param));
        }
        return there;
    }

    private java.util.Optional<byte[]> download(String storageKey, String tenantId,
                                                 String param, List<String> errors) {
        if (storageClient == null) {
            log.error("GenerationInputResolver: no storage client, cannot read '{}' for {}",
                    storageKey, param);
            errors.add("'" + param + "' could not be read: file storage is unavailable on this install.");
            return java.util.Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = storageClient.download(tenant(tenantId), storageKey);
        } catch (RuntimeException e) {
            log.error("GenerationInputResolver: download failed for {} ({}): {}",
                    storageKey, param, e.getMessage());
            errors.add("'" + param + "' could not be read from storage.");
            return java.util.Optional.empty();
        }
        if (bytes == null || bytes.length == 0) {
            // Three causes reach this line and the client cannot tell them
            // apart: the file is gone, it is empty, or storage refused because
            // the key belongs to another workspace (a 403 that StorageClient
            // turns into nothing). Naming one of them would send the reader
            // looking in the wrong place, so the message names what is true of
            // all three.
            errors.add(unreadable(param));
            return java.util.Optional.empty();
        }
        if (bytes.length > maxInlineBytes) {
            // A PLATFORM ceiling, not the provider's: inlining puts the whole
            // file in the request body, and one number guards every provider.
            // Said plainly, because a provider may well refuse a smaller file
            // than this and the reader must not read this number as a promise.
            errors.add(oversized(param, bytes.length));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(bytes);
    }

    /**
     * The provider is told what the file IS, preferring what the FileRef
     * recorded at upload over anything guessed here: a stored mime type came
     * from the producer, while sniffing sees only the first few bytes.
     */
    private static String mimeOf(Object fileRef, byte[] bytes) {
        if (fileRef instanceof Map<?, ?> map) {
            Object declared = map.get("mimeType");
            if (declared instanceof String s && !s.isBlank()) return s.trim();
        }
        return com.apimarketplace.catalog.service.execution.BinaryResponseHandler.sniffMime(bytes);
    }

    /**
     * Refuse an oversized file from what the FileRef already says, before any
     * bytes move. The recorded size is advisory (a FileRef can arrive without
     * one), so {@link #download} still checks what actually came back; this only
     * spares the heap the obvious cases.
     */
    private boolean tooLargeToInline(Object fileRef, String param, List<String> errors) {
        if (!(fileRef instanceof Map<?, ?> map)) return false;
        Object declared = map.get("size");
        if (!(declared instanceof Number size) || size.longValue() <= maxInlineBytes) return false;
        errors.add(oversized(param, size.longValue()));
        return true;
    }

    /**
     * One wording for a file that cannot be read, shared by the two paths that
     * discover it. Three causes reach here and the client cannot tell them
     * apart: gone, empty, or refused because the key belongs to another
     * workspace (a 403 that StorageClient turns into nothing). Naming one would
     * send the reader looking in the wrong place.
     */
    private static String unreadable(String param) {
        return "'" + param + "' could not be read: it is empty, no longer stored, belongs to "
                + "another workspace, or storage did not answer. Try again, and if it repeats "
                + "supply the file again.";
    }

    /** One wording for the cap, so the two places that refuse it cannot drift. */
    private String oversized(String param, long bytes) {
        // Rounded UP, and to one decimal. Truncating integer division reported a
        // 20.4 MB file as "20 MB, above the 20 MB limit", a refusal that
        // contradicts itself and reads like a platform fault.
        return "'" + param + "' is " + megabytes(bytes) + " MB, above the "
                + megabytes(maxInlineBytes) + " MB this platform will inline into a "
                + "request. Use a smaller file; the provider may cap it lower still.";
    }

    private static String megabytes(long bytes) {
        return java.math.BigDecimal.valueOf(bytes)
                .divide(java.math.BigDecimal.valueOf(1_048_576), 1, java.math.RoundingMode.UP)
                .stripTrailingZeros().toPlainString();
    }

    /** A FileRef is recognised by the one field that locates the bytes. */
    private static String storageKeyOf(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Object key = map.get(STORAGE_KEY);
        return key instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "anonymous" : tenantId;
    }
}
