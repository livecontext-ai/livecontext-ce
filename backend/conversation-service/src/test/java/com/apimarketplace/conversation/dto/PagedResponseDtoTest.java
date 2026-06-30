package com.apimarketplace.conversation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PagedResponseDto")
class PagedResponseDtoTest {

    @Nested
    @DisplayName("Constructor from Page")
    class FromPage {

        @Test
        @DisplayName("should create from Spring Page with content")
        void shouldCreateFromPageWithContent() {
            List<String> content = List.of("a", "b", "c");
            Page<String> page = new PageImpl<>(content, PageRequest.of(0, 10), 3);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getContent()).containsExactly("a", "b", "c");
            assertThat(dto.getPage()).isZero();
            assertThat(dto.getSize()).isEqualTo(10);
            assertThat(dto.getTotalElements()).isEqualTo(3);
            assertThat(dto.getTotalPages()).isEqualTo(1);
            assertThat(dto.isFirst()).isTrue();
            assertThat(dto.isLast()).isTrue();
            assertThat(dto.isHasNext()).isFalse();
            assertThat(dto.isHasPrevious()).isFalse();
            assertThat(dto.getNumberOfElements()).isEqualTo(3);
        }

        @Test
        @DisplayName("should create from Page with multiple pages")
        void shouldCreateFromMultiPageResult() {
            List<String> content = List.of("a", "b");
            Page<String> page = new PageImpl<>(content, PageRequest.of(1, 2), 6);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getPage()).isEqualTo(1);
            assertThat(dto.getSize()).isEqualTo(2);
            assertThat(dto.getTotalElements()).isEqualTo(6);
            assertThat(dto.getTotalPages()).isEqualTo(3);
            assertThat(dto.isFirst()).isFalse();
            assertThat(dto.isLast()).isFalse();
            assertThat(dto.isHasNext()).isTrue();
            assertThat(dto.isHasPrevious()).isTrue();
        }

        @Test
        @DisplayName("should create from empty Page")
        void shouldCreateFromEmptyPage() {
            Page<String> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

            PagedResponseDto<String> dto = new PagedResponseDto<>(page);

            assertThat(dto.getContent()).isEmpty();
            assertThat(dto.getTotalElements()).isZero();
            assertThat(dto.getNumberOfElements()).isZero();
        }
    }

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("should create with default constructor")
        void shouldCreateWithDefault() {
            PagedResponseDto<String> dto = new PagedResponseDto<>();
            assertThat(dto.getContent()).isNull();
            assertThat(dto.getPage()).isZero();
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should set all fields")
        void shouldSetAllFields() {
            PagedResponseDto<String> dto = new PagedResponseDto<>();
            dto.setContent(List.of("x"));
            dto.setPage(2);
            dto.setSize(20);
            dto.setTotalElements(100);
            dto.setTotalPages(5);
            dto.setFirst(false);
            dto.setLast(false);
            dto.setHasNext(true);
            dto.setHasPrevious(true);
            dto.setNumberOfElements(20);

            assertThat(dto.getContent()).containsExactly("x");
            assertThat(dto.getPage()).isEqualTo(2);
            assertThat(dto.getSize()).isEqualTo(20);
            assertThat(dto.getTotalElements()).isEqualTo(100);
            assertThat(dto.getTotalPages()).isEqualTo(5);
            assertThat(dto.isFirst()).isFalse();
            assertThat(dto.isLast()).isFalse();
            assertThat(dto.isHasNext()).isTrue();
            assertThat(dto.isHasPrevious()).isTrue();
            assertThat(dto.getNumberOfElements()).isEqualTo(20);
        }
    }
}
