package com.apimarketplace.orchestrator.stepdata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Defines a column for step data display in the frontend.
 * The backend is the source of truth for column order and rendering.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnDefinition(
        String field,
        String header,
        ColumnType type,
        RenderType renderType,
        Integer width,
        boolean sortable,
        boolean filterable,
        Boolean expandable
) {

    public enum ColumnType {
        STRING,
        NUMBER,
        BOOLEAN,
        DATETIME,
        JSON
    }

    public enum RenderType {
        TEXT,
        CODE,
        STATUS_BADGE,
        BRANCH_BADGE,
        HTTP_STATUS_BADGE,
        HTTP_METHOD_BADGE,
        BOOLEAN_BADGE,
        BADGE,
        DURATION,
        RELATIVE_TIME,
        PERCENTAGE,
        PROGRESS_BAR,
        JSON_PREVIEW,
        JSON_NAVIGABLE,
        EVALUATIONS_TABLE,
        CASES_TABLE,
        STRING_LIST,
        TEXT_PREVIEW,
        HTML_PREVIEW,
        LOOP_PROGRESS,
        SPLIT_PROGRESS
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String field;
        private String header;
        private ColumnType type = ColumnType.STRING;
        private RenderType renderType = RenderType.TEXT;
        private Integer width;
        private boolean sortable = true;
        private boolean filterable = true;
        private Boolean expandable;

        public Builder field(String field) {
            this.field = field;
            return this;
        }

        public Builder header(String header) {
            this.header = header;
            return this;
        }

        public Builder type(ColumnType type) {
            this.type = type;
            return this;
        }

        public Builder renderType(RenderType renderType) {
            this.renderType = renderType;
            return this;
        }

        public Builder width(Integer width) {
            this.width = width;
            return this;
        }

        public Builder sortable(boolean sortable) {
            this.sortable = sortable;
            return this;
        }

        public Builder filterable(boolean filterable) {
            this.filterable = filterable;
            return this;
        }

        public Builder expandable(Boolean expandable) {
            this.expandable = expandable;
            return this;
        }

        public ColumnDefinition build() {
            return new ColumnDefinition(field, header, type, renderType, width, sortable, filterable, expandable);
        }
    }
}
