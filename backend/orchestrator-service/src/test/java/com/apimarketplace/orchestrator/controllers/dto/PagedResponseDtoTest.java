package com.apimarketplace.orchestrator.controllers.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PagedResponseDto}.
 */
@DisplayName("PagedResponseDto")
class PagedResponseDtoTest {

    @Nested
    @DisplayName("Constructor from Page")
    class ConstructorFromPage {

        @Test
        @DisplayName("Should map all fields from a non-empty first page")
        void shouldMapAllFieldsFromNonEmptyFirstPage() {
            List<String> content = List.of("a", "b", "c");
            Page<String> page = new PageImpl<>(content, PageRequest.of(0, 3), 10);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getContent()).containsExactly("a", "b", "c");
            assertThat(dto.getPage()).isZero();
            assertThat(dto.getSize()).isEqualTo(3);
            assertThat(dto.getTotalElements()).isEqualTo(10);
            assertThat(dto.getTotalPages()).isEqualTo(4); // ceil(10/3)
            assertThat(dto.isFirst()).isTrue();
            assertThat(dto.isLast()).isFalse();
            assertThat(dto.isHasNext()).isTrue();
            assertThat(dto.isHasPrevious()).isFalse();
            assertThat(dto.getNumberOfElements()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should map all fields from a middle page")
        void shouldMapAllFieldsFromMiddlePage() {
            List<String> content = List.of("d", "e", "f");
            Page<String> page = new PageImpl<>(content, PageRequest.of(1, 3), 10);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getPage()).isEqualTo(1);
            assertThat(dto.isFirst()).isFalse();
            assertThat(dto.isLast()).isFalse();
            assertThat(dto.isHasNext()).isTrue();
            assertThat(dto.isHasPrevious()).isTrue();
        }

        @Test
        @DisplayName("Should map all fields from the last page")
        void shouldMapAllFieldsFromLastPage() {
            List<String> content = List.of("j");
            Page<String> page = new PageImpl<>(content, PageRequest.of(3, 3), 10);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getPage()).isEqualTo(3);
            assertThat(dto.isFirst()).isFalse();
            assertThat(dto.isLast()).isTrue();
            assertThat(dto.isHasNext()).isFalse();
            assertThat(dto.isHasPrevious()).isTrue();
            assertThat(dto.getNumberOfElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should handle empty page")
        void shouldHandleEmptyPage() {
            Page<String> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getContent()).isEmpty();
            assertThat(dto.getTotalElements()).isZero();
            assertThat(dto.getTotalPages()).isZero();
            assertThat(dto.isFirst()).isTrue();
            assertThat(dto.isLast()).isTrue();
            assertThat(dto.isHasNext()).isFalse();
            assertThat(dto.isHasPrevious()).isFalse();
            assertThat(dto.getNumberOfElements()).isZero();
        }

        @Test
        @DisplayName("Should handle single-page result")
        void shouldHandleSinglePageResult() {
            List<String> content = List.of("x", "y");
            Page<String> page = new PageImpl<>(content, PageRequest.of(0, 10), 2);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getTotalPages()).isEqualTo(1);
            assertThat(dto.isFirst()).isTrue();
            assertThat(dto.isLast()).isTrue();
            assertThat(dto.isHasNext()).isFalse();
            assertThat(dto.isHasPrevious()).isFalse();
        }
    }

    @Nested
    @DisplayName("Default constructor and setters")
    class DefaultConstructor {

        @Test
        @DisplayName("Should allow setting fields via setters")
        void shouldAllowSettingFieldsViaSetters() {
            PagedResponseDto<String> dto = new PagedResponseDto<>();
            dto.setContent(List.of("a"));
            dto.setPage(2);
            dto.setSize(5);
            dto.setTotalElements(50);
            dto.setTotalPages(10);
            dto.setFirst(false);
            dto.setLast(false);
            dto.setHasNext(true);
            dto.setHasPrevious(true);
            dto.setNumberOfElements(5);

            assertThat(dto.getContent()).containsExactly("a");
            assertThat(dto.getPage()).isEqualTo(2);
            assertThat(dto.getSize()).isEqualTo(5);
            assertThat(dto.getTotalElements()).isEqualTo(50);
            assertThat(dto.getTotalPages()).isEqualTo(10);
            assertThat(dto.isFirst()).isFalse();
            assertThat(dto.isLast()).isFalse();
            assertThat(dto.isHasNext()).isTrue();
            assertThat(dto.isHasPrevious()).isTrue();
            assertThat(dto.getNumberOfElements()).isEqualTo(5);
        }
    }
}
