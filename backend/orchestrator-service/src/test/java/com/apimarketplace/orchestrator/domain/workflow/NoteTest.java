package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Note record.
 *
 * Note represents a visual annotation in the workflow canvas.
 */
@DisplayName("Note")
class NoteTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create note with all fields")
        void shouldCreateNoteWithAllFields() {
            Map<String, Object> position = Map.of("x", 100.0, "y", 200.0);
            Note note = new Note("n1", "note", "Documentation", "This is a note",
                "#FFFACD", "#FFD700", "#333333", 200, 100, position);

            assertEquals("n1", note.id());
            assertEquals("note", note.type());
            assertEquals("Documentation", note.label());
            assertEquals("This is a note", note.text());
            assertEquals("#FFFACD", note.color());
            assertEquals("#FFD700", note.borderColor());
            assertEquals("#333333", note.textColor());
            assertEquals(200, note.width());
            assertEquals(100, note.height());
            assertEquals(position, note.position());
        }

        @Test
        @DisplayName("Should create minimal note")
        void shouldCreateMinimalNote() {
            Note note = new Note("n1", null, null, "Simple note",
                null, null, null, null, null, null);

            assertEquals("n1", note.id());
            assertEquals("note", note.type());
            assertEquals("Simple note", note.text());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Should throw for null or blank id")
        void shouldThrowForNullOrBlankId(String id) {
            assertThrows(IllegalArgumentException.class,
                () -> new Note(id, "note", null, "text", null, null, null, null, null, null));
        }

        @Test
        @DisplayName("Should default type to 'note'")
        void shouldDefaultTypeToNote() {
            Note note = new Note("n1", null, null, "text", null, null, null, null, null, null);

            assertEquals("note", note.type());
        }

        @Test
        @DisplayName("Should normalize type to lowercase")
        void shouldNormalizeTypeToLowercase() {
            Note note = new Note("n1", "NOTE", null, "text", null, null, null, null, null, null);

            assertEquals("note", note.type());
        }
    }

    @Nested
    @DisplayName("Visual properties")
    class VisualPropertiesTests {

        @Test
        @DisplayName("Should accept color codes")
        void shouldAcceptColorCodes() {
            Note note = new Note("n1", "note", null, "text",
                "#FF0000", "#00FF00", "#0000FF", null, null, null);

            assertEquals("#FF0000", note.color());
            assertEquals("#00FF00", note.borderColor());
            assertEquals("#0000FF", note.textColor());
        }

        @Test
        @DisplayName("Should accept dimensions")
        void shouldAcceptDimensions() {
            Note note = new Note("n1", "note", null, "text",
                null, null, null, 300, 150, null);

            assertEquals(300, note.width());
            assertEquals(150, note.height());
        }

        @Test
        @DisplayName("Should accept position")
        void shouldAcceptPosition() {
            Map<String, Object> position = Map.of("x", 50.0, "y", 100.0);
            Note note = new Note("n1", "note", null, "text",
                null, null, null, null, null, position);

            assertNotNull(note.position());
            assertEquals(50.0, note.position().get("x"));
            assertEquals(100.0, note.position().get("y"));
        }
    }

    @Nested
    @DisplayName("Record equality")
    class RecordEqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            Note note1 = new Note("n1", "note", "Label", "text", null, null, null, null, null, null);
            Note note2 = new Note("n1", "note", "Label", "text", null, null, null, null, null, null);

            assertEquals(note1, note2);
            assertEquals(note1.hashCode(), note2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different id")
        void shouldNotBeEqualForDifferentId() {
            Note note1 = new Note("n1", "note", "Label", "text", null, null, null, null, null, null);
            Note note2 = new Note("n2", "note", "Label", "text", null, null, null, null, null, null);

            assertNotEquals(note1, note2);
        }
    }
}
