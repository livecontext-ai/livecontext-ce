package com.apimarketplace.datasource.publicvideo;

import java.util.List;

/**
 * One product film, in the shape the public website's /videos pages read.
 *
 * <p>A deliberate PROJECTION of the library row, not the row: only the fields
 * named here ever leave the service, so a column added to that table for the
 * team's own use (a note, an owner, a status the site does not know) cannot
 * become public by accident.
 */
public record PublicVideoDto(
        String slug,
        String series,
        String title,
        String tagline,
        String youtubeId,
        int durationSeconds,
        String publishedAt,
        String posterUrl,
        String shareImageUrl,
        String posterAlt,
        List<String> problem,
        List<String> answer,
        List<String> highlights,
        List<Chapter> chapters,
        List<TranscriptLine> transcript,
        String marketplaceSlug,
        String marketplaceTitle) {

    /** One measured segment of the film. */
    public record Chapter(double start, double end, String title) {
    }

    /** One burned caption, at its position on the film's timeline. */
    public record TranscriptLine(double t, String text) {
    }
}
