package com.apimarketplace.conversation.dto;

import java.util.List;

/**
 * Paginated response for the public shared-conversation messages endpoint.
 *
 * <p>Deliberately minimal: only {@code items} (sorted oldest-to-newest within
 * the page) and {@code hasMore}. We do NOT expose totalElements / totalPages /
 * pageNumber because:
 * <ul>
 *     <li>The frontend only needs to know whether to keep paginating up.</li>
 *     <li>Returning a total count to anonymous callers leaks volume metadata
 *         on every shared link, enabling a scraper to estimate corpus size.</li>
 * </ul>
 */
public record PublicMessagePageDto(
        List<PublicMessageDto> items,
        boolean hasMore
) {
}
