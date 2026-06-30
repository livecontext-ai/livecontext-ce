package com.apimarketplace.interfaces.service;

import com.apimarketplace.interfaces.service.InterfaceTemplatePatcher.Edit;
import com.apimarketplace.interfaces.service.InterfaceTemplatePatcher.PatchException;
import com.apimarketplace.interfaces.service.InterfaceTemplatePatcher.PatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("InterfaceTemplatePatcher")
class InterfaceTemplatePatcherTest {

    @Nested
    @DisplayName("single edit")
    class SingleEdit {

        @Test
        @DisplayName("Replaces a unique match and reports one replacement")
        void replacesUniqueMatch() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "<h1>Hello</h1>", List.of(new Edit("Hello", "Welcome")), false);

            assertThat(result.content()).isEqualTo("<h1>Welcome</h1>");
            assertThat(result.replacements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Empty 'new' deletes the matched text")
        void emptyNewDeletes() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "<p class=\"old\">x</p>", List.of(new Edit(" class=\"old\"", "")), false);

            assertThat(result.content()).isEqualTo("<p>x</p>");
        }

        @Test
        @DisplayName("Treats 'old' as a literal string, not a regex")
        void treatsOldAsLiteral() {
            // ".*" and "[" would blow up a regex engine; here they must match literally.
            PatchResult result = InterfaceTemplatePatcher.apply(
                "value = .*[0]", List.of(new Edit(".*[0]", "42")), false);

            assertThat(result.content()).isEqualTo("value = 42");
        }

        @Test
        @DisplayName("Treats 'new' as literal - $ and backslashes are not group refs")
        void treatsNewAsLiteral() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "price: PLACEHOLDER", List.of(new Edit("PLACEHOLDER", "$10 \\ off")), false);

            assertThat(result.content()).isEqualTo("price: $10 \\ off");
        }
    }

    @Nested
    @DisplayName("not found / ambiguous")
    class NotFoundAmbiguous {

        @Test
        @DisplayName("Throws NOT_FOUND when 'old' is absent, with edit index 0")
        void throwsNotFound() {
            PatchException ex = catchThrowableOfType(
                () -> InterfaceTemplatePatcher.apply("abc", List.of(new Edit("xyz", "q")), false),
                PatchException.class);

            assertThat(ex.code()).isEqualTo(PatchException.Code.NOT_FOUND);
            assertThat(ex.editIndex()).isZero();
            assertThat(ex.matchCount()).isZero();
        }

        @Test
        @DisplayName("Throws AMBIGUOUS with the match count when 'old' is not unique (replace_all=false)")
        void throwsAmbiguous() {
            PatchException ex = catchThrowableOfType(
                () -> InterfaceTemplatePatcher.apply("a a a", List.of(new Edit("a", "b")), false),
                PatchException.class);

            assertThat(ex.code()).isEqualTo(PatchException.Code.AMBIGUOUS);
            assertThat(ex.matchCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("replace_all=true replaces every occurrence and counts them all")
        void replaceAllReplacesEveryOccurrence() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "a a a", List.of(new Edit("a", "b")), true);

            assertThat(result.content()).isEqualTo("b b b");
            assertThat(result.replacements()).isEqualTo(3);
        }

        @Test
        @DisplayName("replace_all where 'new' contains 'old' does not loop or double-count")
        void replaceAllNoReinjectionLoop() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "x x", List.of(new Edit("x", "xx")), true);

            assertThat(result.content()).isEqualTo("xx xx");
            assertThat(result.replacements()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("multiple edits")
    class MultipleEdits {

        @Test
        @DisplayName("Applies edits in order")
        void appliesInOrder() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "<h1>title</h1><p>body</p>",
                List.of(new Edit("title", "Welcome"), new Edit("body", "Hello there")), false);

            assertThat(result.content()).isEqualTo("<h1>Welcome</h1><p>Hello there</p>");
            assertThat(result.replacements()).isEqualTo(2);
        }

        @Test
        @DisplayName("A later edit can match text introduced by an earlier edit (sequential)")
        void laterEditSeesEarlierResult() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "A",
                List.of(new Edit("A", "B"), new Edit("B", "C")), false);

            assertThat(result.content()).isEqualTo("C");
        }

        @Test
        @DisplayName("All-or-nothing: a failing second edit aborts and reports its index")
        void allOrNothingAbortsOnFailure() {
            PatchException ex = catchThrowableOfType(
                () -> InterfaceTemplatePatcher.apply(
                    "keep this",
                    List.of(new Edit("keep", "kept"), new Edit("missing", "x")), false),
                PatchException.class);

            assertThat(ex.code()).isEqualTo(PatchException.Code.NOT_FOUND);
            assertThat(ex.editIndex()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("line-ending normalization")
    class LineEndings {

        @Test
        @DisplayName("Matches across a CRLF/LF difference between template and 'old'")
        void crlfTemplateMatchedByLfOld() {
            String template = "line1\r\nline2\r\nline3";
            PatchResult result = InterfaceTemplatePatcher.apply(
                template, List.of(new Edit("line1\nline2", "merged")), false);

            // Content is normalized to \n on the way out - harmless for html/css/js.
            assertThat(result.content()).isEqualTo("merged\nline3");
        }

        @Test
        @DisplayName("Matches a CRLF 'old' against an LF template")
        void crlfOldMatchedByLfTemplate() {
            PatchResult result = InterfaceTemplatePatcher.apply(
                "a\nb", List.of(new Edit("a\r\nb", "c")), false);

            assertThat(result.content()).isEqualTo("c");
        }
    }

    @Nested
    @DisplayName("validation guards")
    class ValidationGuards {

        @Test
        @DisplayName("Empty edit list throws EMPTY_EDITS")
        void emptyEditsThrows() {
            assertThatThrownBy(() -> InterfaceTemplatePatcher.apply("x", List.of(), false))
                .isInstanceOfSatisfying(PatchException.class,
                    ex -> assertThat(ex.code()).isEqualTo(PatchException.Code.EMPTY_EDITS));
        }

        @Test
        @DisplayName("Null edit list throws EMPTY_EDITS")
        void nullEditsThrows() {
            assertThatThrownBy(() -> InterfaceTemplatePatcher.apply("x", null, false))
                .isInstanceOfSatisfying(PatchException.class,
                    ex -> assertThat(ex.code()).isEqualTo(PatchException.Code.EMPTY_EDITS));
        }

        @Test
        @DisplayName("Empty 'old' throws EMPTY_OLD (won't match-everywhere)")
        void emptyOldThrows() {
            assertThatThrownBy(() -> InterfaceTemplatePatcher.apply("x", List.of(new Edit("", "y")), false))
                .isInstanceOfSatisfying(PatchException.class,
                    ex -> assertThat(ex.code()).isEqualTo(PatchException.Code.EMPTY_OLD));
        }

        @Test
        @DisplayName("Identical 'old' and 'new' throws NO_OP")
        void noOpThrows() {
            assertThatThrownBy(() -> InterfaceTemplatePatcher.apply("xy", List.of(new Edit("x", "x")), false))
                .isInstanceOfSatisfying(PatchException.class,
                    ex -> assertThat(ex.code()).isEqualTo(PatchException.Code.NO_OP));
        }

        @Test
        @DisplayName("Null original content is treated as empty → NOT_FOUND for any real 'old'")
        void nullOriginalIsEmpty() {
            assertThatThrownBy(() -> InterfaceTemplatePatcher.apply(null, List.of(new Edit("a", "b")), false))
                .isInstanceOfSatisfying(PatchException.class,
                    ex -> assertThat(ex.code()).isEqualTo(PatchException.Code.NOT_FOUND));
        }
    }
}
