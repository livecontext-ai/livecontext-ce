package com.apimarketplace.publication.screening;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composes {@link ImageUrlExtractor} into a single
 * {@link ImageScreeningReport} for a Template's HTML / CSS / JS.
 *
 * <p>Every detected media resource (image, video, audio, anchor download,
 * CSS background) lands in the report, regardless of host. Reason: a
 * publisher can upload a third-party photo to their own CDN - the URL
 * looks "internal" but the bytes themselves can still be copyright-
 * infringing. Only the publisher can decide per-resource whether they
 * actually hold rights, so we present everything for review.
 *
 * <p>This report drives a frontend WARNING, never a block. The publisher
 * can always proceed with publish; the decisions log records whether
 * they attested rights or just acknowledged the warning. Auto-blocking
 * here would convert LiveContext from "hébergeur" (LCEN safe harbor) to
 * "éditeur" (full content liability).
 *
 * <p>Pure-function: no DB writes, no I/O. The audit-log decision
 * persistence + the publish-chokepoint integration live in the
 * controller / wizard flow.
 *
 * <p>Wave 2b boundary: this service only sees the static template
 * fields. It does NOT see resolved {@code items[].data} - so
 * {@code <img src="${item.photoUrl}">} where {@code photoUrl} comes from
 * per-item data is NOT surfaced here. Wave 2b's
 * {@code ItemDataResourceExtractor} closes that gap.
 */
@Service
public class ImageScreeningService {

    private final ItemDataResourceExtractor itemDataResourceExtractor;

    public ImageScreeningService(ItemDataResourceExtractor itemDataResourceExtractor) {
        this.itemDataResourceExtractor = itemDataResourceExtractor;
    }

    /**
     * Scan an Interface's three template fields. Each detected URL becomes
     * one {@link ImageScreeningReport.FlaggedImage} entry; cross-source
     * duplicates are deduplicated by URL (first source wins: HTML → CSS → JS).
     *
     * @param htmlTemplate the rendered HTML (nullable)
     * @param cssTemplate  the dedicated CSS block on the interface (nullable)
     * @param jsTemplate   the dedicated JS block on the interface (nullable)
     * @return a report listing every media resource referenced
     */
    public ImageScreeningReport scan(String htmlTemplate, String cssTemplate, String jsTemplate) {
        return scan(htmlTemplate, cssTemplate, jsTemplate, null);
    }

    /**
     * Scan an interface's templates AND its resolved render data
     * ({@code items[].data}). Template images (HTML/CSS/JS sources) take
     * precedence on dedup; images found only in the data layer are tagged
     * {@link ImageScreeningReport.Source#DATA}. This is the full surface the
     * marketplace will actually render - Wave 2b closed the data-layer gap
     * that let scraped CDN URLs and downloaded FileRefs ship unscreened.
     *
     * @param items the render result's {@code items} list (nullable); each
     *              entry is a map carrying a {@code data} sub-map
     */
    public ImageScreeningReport scan(String htmlTemplate, String cssTemplate, String jsTemplate,
                                     List<? extends Map<String, Object>> items) {
        List<ImageScreeningReport.FlaggedImage> flagged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        addAll(seen, flagged, ImageUrlExtractor.extractFromHtml(htmlTemplate),
                ImageScreeningReport.Source.HTML);
        addAll(seen, flagged, ImageUrlExtractor.extractFromCss(cssTemplate),
                ImageScreeningReport.Source.CSS);
        addAll(seen, flagged, ImageUrlExtractor.extractFromJs(jsTemplate),
                ImageScreeningReport.Source.JS);
        addAll(seen, flagged, itemDataResourceExtractor.extract(items),
                ImageScreeningReport.Source.DATA);

        return new ImageScreeningReport(List.copyOf(flagged));
    }

    private void addAll(Set<String> seen,
                        List<ImageScreeningReport.FlaggedImage> sink,
                        Set<String> candidates,
                        ImageScreeningReport.Source source) {
        for (String url : candidates) {
            if (!seen.add(url)) continue;
            sink.add(new ImageScreeningReport.FlaggedImage(url, source));
        }
    }
}
