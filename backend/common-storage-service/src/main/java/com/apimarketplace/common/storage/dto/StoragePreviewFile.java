package com.apimarketplace.common.storage.dto;

/**
 * One preview child of a folder for the iOS-style 3x3 folder tile. Carries just enough to render a
 * tile WITHOUT a second round-trip: the file {@code id} (the frontend fetches inline bytes by id for
 * an image thumbnail), plus its {@code mimeType} / {@code fileName} so the frontend can pick a
 * file-type icon for everything that is not an image (pdf, csv, archives, ...). Newest-first; the
 * folder aggregates expose at most a handful (see {@code PREVIEW_FILES_PER_FOLDER}).
 */
public record StoragePreviewFile(
    String id,
    String mimeType,
    String fileName
) {}
