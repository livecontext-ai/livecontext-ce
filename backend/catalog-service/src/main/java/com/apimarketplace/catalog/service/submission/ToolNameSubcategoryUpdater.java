package com.apimarketplace.catalog.service.submission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles tool_names.subcategory_id updates in an independent transaction.
 *
 * <p>During parallel API imports, multiple threads may UPDATE the same tool_names row,
 * causing PostgreSQL deadlocks. By running in REQUIRES_NEW, a deadlock here does NOT
 * poison the caller's transaction (which inserts the API + tools). The subcategory_id
 * update is best-effort - it is not critical for the import to succeed.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolNameSubcategoryUpdater {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSubcategory(String toolNameId, UUID subcategoryId) {
        try {
            UUID toolNameUuid = UUID.fromString(toolNameId);
            long currentTime = System.currentTimeMillis();

            int rowsUpdated = jdbcTemplate.update(
                    "UPDATE tool_names SET subcategory_id = ?, updated_at = ? WHERE id = ? AND (subcategory_id IS DISTINCT FROM ?)",
                    subcategoryId, currentTime, toolNameUuid, subcategoryId);

            if (rowsUpdated > 0) {
                log.info("Tool name {} updated with subcategory_id: {}", toolNameId, subcategoryId);
            }
        } catch (Exception e) {
            log.warn("Non-critical: could not update subcategory for tool name {} (likely concurrent update): {}",
                    toolNameId, e.getMessage());
        }
    }
}
