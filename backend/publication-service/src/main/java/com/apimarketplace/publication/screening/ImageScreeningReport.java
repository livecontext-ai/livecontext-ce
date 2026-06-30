package com.apimarketplace.publication.screening;

import java.util.List;

/**
 * Result of a pre-publish image scan. Returned by
 * {@link ImageScreeningService#scan} and consumed by the publish wizard
 * (renders one row per flagged image with the replace/attest actions).
 *
 * <p>Empty {@code flagged} list means "clean - skip the modal, publish
 * straight through" (the wizard treats {@code flagged.isEmpty()} as the
 * zero-friction happy path).
 *
 * @param flagged ordered, deduplicated list of external image references
 *                that need a publisher decision before publish proceeds
 */
public record ImageScreeningReport(List<FlaggedImage> flagged) {

    public static ImageScreeningReport empty() {
        return new ImageScreeningReport(List.of());
    }

    public boolean isClean() {
        return flagged == null || flagged.isEmpty();
    }

    /**
     * One flagged image with provenance metadata so the wizard can show the
     * publisher exactly where the URL came from (template body vs CSS vs JS
     * literal). This helps publishers recognize "oh that's the hero image I
     * pasted, I'll swap it for stock".
     *
     * @param url    the raw URL as it appears in the template
     * @param source which template field surfaced it
     */
    public record FlaggedImage(String url, Source source) { }

    public enum Source {
        HTML,
        CSS,
        JS,
        /**
         * The image was found in the resolved interface render data
         * ({@code items[].data}) rather than in a static template field - e.g.
         * a scraped third-party CDN URL interpolated via {@code variableMapping},
         * or a downloaded/re-hosted FileRef. The static template only ever
         * carries the {@code {{placeholder}}}, so these images are invisible to
         * the HTML/CSS/JS extractors and must be surfaced from the data layer.
         */
        DATA
    }
}
