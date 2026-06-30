package com.apimarketplace.datasource.crud.domain;

/**
 * Enum representing the supported CRUD operations on datasources.
 */
public enum CrudOperation {
    CREATE_ROW("create-row"),
    CREATE_COLUMN("create-column"),
    READ_ROW("read-row"),
    UPDATE_ROW("update-row"),
    DELETE_ROW("delete-row");

    private final String value;

    CrudOperation(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Whether this operation mutates the datasource (rows or schema).
     *
     * <p>{@link #READ_ROW} is the only read; every other operation
     * ({@link #CREATE_ROW}, {@link #CREATE_COLUMN}, {@link #UPDATE_ROW},
     * {@link #DELETE_ROW}) writes. Used by the user-facing CRUD entry point to
     * decide whether a per-resource org write-restriction gate must run - read
     * operations are never blocked by it.
     */
    public boolean isWrite() {
        return this != READ_ROW;
    }

    /**
     * Parse a string value to CrudOperation.
     * @param value The string value (e.g., "create-row", "read-row")
     * @return The corresponding CrudOperation
     * @throws IllegalArgumentException if the value is not valid
     */
    public static CrudOperation fromValue(String value) {
        for (CrudOperation op : values()) {
            if (op.value.equals(value)) {
                return op;
            }
        }
        throw new IllegalArgumentException("Unknown CRUD operation: " + value);
    }

    /**
     * Check if a string represents a valid CRUD operation.
     */
    public static boolean isValid(String value) {
        for (CrudOperation op : values()) {
            if (op.value.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
