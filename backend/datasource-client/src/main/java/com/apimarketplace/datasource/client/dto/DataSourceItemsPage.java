package com.apimarketplace.datasource.client.dto;

import java.util.List;

/**
 * A page of table rows, with whether any of them is RESTRICTED (CASA LC-066): Gmail / Drive
 * content a workflow stored. A run that loads such a page holds that content, so it is restricted.
 */
public record DataSourceItemsPage(List<DataSourceItemDto> items, boolean restricted) {

    public DataSourceItemsPage {
        items = items != null ? items : List.of();
    }
}
