package com.apimarketplace.datasource.client;

/**
 * A publication copy of a table ({@link DataSourceClient#copyAllItems}) could not be completed:
 * a page failed, came back without the RESTRICTED-excluded or keyset confirmation, or the paging
 * stopped making progress. Nothing was copied. Usually transient (a rolling update, a slow
 * datasource-service), so the publish that needed the copy can be retried.
 */
public class TableCopyException extends RuntimeException {

    private final Long dataSourceId;

    public TableCopyException(Long dataSourceId, String message, Throwable cause) {
        super(message, cause);
        this.dataSourceId = dataSourceId;
    }

    public Long getDataSourceId() {
        return dataSourceId;
    }
}
