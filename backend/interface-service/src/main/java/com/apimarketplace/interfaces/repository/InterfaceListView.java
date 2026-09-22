package com.apimarketplace.interfaces.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight closed projection for the paged-list whole-set load. Carries ONLY the columns the
 * server needs to filter (type / table-attached / visibility), sort (name / lastModified) and slice
 * a page out of the full tenant set.
 *
 * <p>Crucially it EXCLUDES the heavy columns of {@code InterfaceEntity}: the
 * {@code html_template} / {@code css_template} / {@code js_template} text and the {@code data}
 * JSONB. Loading the whole tenant set as this projection (for sort + slice) therefore never
 * reads those columns - the page's handful of full entities are fetched separately by id
 * ({@link org.springframework.data.repository.CrudRepository#findAllById}). This is what keeps the
 * list endpoint fast on workspaces with many large interfaces (the old path loaded every row's full
 * templates just to return one 25-item page).
 */
public interface InterfaceListView {
    UUID getId();
    String getName();
    String getDescription();
    String getInterfaceType();
    Long getDataSourceId();
    Instant getCreatedAt();
    Instant getUpdatedAt();

    /** Folder this page is filed under (V450), or null at the top level. */
    UUID getFolderId();

    /** Preset name or "WIDTHxHEIGHT" - the shape a folder tile draws for this page. */
    String getFormat();
}
