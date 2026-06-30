package com.apimarketplace.publication.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Admin PUT body for replacing the full highlight order of a display_mode.
 * Cap at 100 entries per display_mode (DoS protection + product cap - a curation
 * row of more than 100 items is an admin-side mistake, not a use case).
 */
public record HighlightOrderRequest(
        @NotNull
        @Size(min = 0, max = 100, message = "orderedIds must contain between 0 and 100 entries")
        List<UUID> orderedIds
) {
}
