package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.ColumnType;
import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.RenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ColumnDefinition record and its Builder.
 */
@DisplayName("ColumnDefinition")
class ColumnDefinitionTest {

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build column definition with all fields")
        void shouldBuildWithAllFields() {
            ColumnDefinition col = ColumnDefinition.builder()
                .field("status")
                .header("Status")
                .type(ColumnType.STRING)
                .renderType(RenderType.STATUS_BADGE)
                .width(110)
                .sortable(true)
                .filterable(true)
                .expandable(false)
                .build();

            assertThat(col.field()).isEqualTo("status");
            assertThat(col.header()).isEqualTo("Status");
            assertThat(col.type()).isEqualTo(ColumnType.STRING);
            assertThat(col.renderType()).isEqualTo(RenderType.STATUS_BADGE);
            assertThat(col.width()).isEqualTo(110);
            assertThat(col.sortable()).isTrue();
            assertThat(col.filterable()).isTrue();
            assertThat(col.expandable()).isFalse();
        }

        @Test
        @DisplayName("Should use default values when not set")
        void shouldUseDefaults() {
            ColumnDefinition col = ColumnDefinition.builder()
                .field("test")
                .header("Test")
                .build();

            assertThat(col.type()).isEqualTo(ColumnType.STRING);
            assertThat(col.renderType()).isEqualTo(RenderType.TEXT);
            assertThat(col.sortable()).isTrue();
            assertThat(col.filterable()).isTrue();
            assertThat(col.expandable()).isNull();
            assertThat(col.width()).isNull();
        }

        @Test
        @DisplayName("Should build JSON expandable column")
        void shouldBuildJsonExpandableColumn() {
            ColumnDefinition col = ColumnDefinition.builder()
                .field("output")
                .header("Output")
                .type(ColumnType.JSON)
                .renderType(RenderType.JSON_NAVIGABLE)
                .width(300)
                .sortable(false)
                .filterable(false)
                .expandable(true)
                .build();

            assertThat(col.type()).isEqualTo(ColumnType.JSON);
            assertThat(col.renderType()).isEqualTo(RenderType.JSON_NAVIGABLE);
            assertThat(col.sortable()).isFalse();
            assertThat(col.filterable()).isFalse();
            assertThat(col.expandable()).isTrue();
        }
    }

    @Nested
    @DisplayName("record constructor")
    class RecordTests {

        @Test
        @DisplayName("Should create column definition directly")
        void shouldCreateDirectly() {
            ColumnDefinition col = new ColumnDefinition(
                "durationMs", "Duration", ColumnType.NUMBER,
                RenderType.DURATION, 90, true, false, null
            );

            assertThat(col.field()).isEqualTo("durationMs");
            assertThat(col.header()).isEqualTo("Duration");
            assertThat(col.type()).isEqualTo(ColumnType.NUMBER);
            assertThat(col.renderType()).isEqualTo(RenderType.DURATION);
        }
    }

    @Nested
    @DisplayName("ColumnType enum")
    class ColumnTypeTests {

        @Test
        @DisplayName("Should have all expected column types")
        void shouldHaveAllTypes() {
            assertThat(ColumnType.values()).containsExactlyInAnyOrder(
                ColumnType.STRING, ColumnType.NUMBER, ColumnType.BOOLEAN,
                ColumnType.DATETIME, ColumnType.JSON
            );
        }
    }

    @Nested
    @DisplayName("RenderType enum")
    class RenderTypeTests {

        @Test
        @DisplayName("Should have expected render types")
        void shouldHaveExpectedTypes() {
            RenderType[] values = RenderType.values();
            assertThat(values).hasSizeGreaterThanOrEqualTo(15);
            assertThat(values).contains(
                RenderType.TEXT, RenderType.CODE, RenderType.STATUS_BADGE,
                RenderType.BADGE, RenderType.DURATION, RenderType.RELATIVE_TIME,
                RenderType.JSON_PREVIEW, RenderType.JSON_NAVIGABLE,
                RenderType.BOOLEAN_BADGE, RenderType.HTTP_STATUS_BADGE,
                RenderType.TEXT_PREVIEW, RenderType.LOOP_PROGRESS, RenderType.SPLIT_PROGRESS
            );
        }
    }
}
