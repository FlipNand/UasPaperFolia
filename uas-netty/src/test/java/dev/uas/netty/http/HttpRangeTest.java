package dev.uas.netty.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpRangeTest {

    private static final long SIZE = 1000;

    @Test
    @DisplayName("absent and non-byte-unit headers mean 'serve everything'")
    void absentRanges() {
        for (String header : new String[] {null, "", "   ", "items=0-5", "bytes", "bytes=", "bytes=abc-def"}) {
            assertEquals(HttpRange.Kind.ABSENT, HttpRange.parse(header, SIZE).kind(),
                "header: " + header);
        }
    }

    @Test
    @DisplayName("closed, open-ended and suffix forms are all satisfied")
    void satisfiedForms() {
        HttpRange closed = HttpRange.parse("bytes=100-299", SIZE).range();
        assertEquals(100, closed.start());
        assertEquals(299, closed.endInclusive());
        assertEquals(200, closed.length());
        assertEquals("bytes 100-299/1000", closed.contentRange(SIZE));

        HttpRange open = HttpRange.parse("bytes=900-", SIZE).range();
        assertEquals(900, open.start());
        assertEquals(999, open.endInclusive());

        HttpRange suffix = HttpRange.parse("bytes=-50", SIZE).range();
        assertEquals(950, suffix.start());
        assertEquals(999, suffix.endInclusive());

        // A suffix longer than the entity clamps to the whole entity rather than failing.
        HttpRange overSuffix = HttpRange.parse("bytes=-5000", SIZE).range();
        assertEquals(0, overSuffix.start());
        assertEquals(999, overSuffix.endInclusive());
    }

    @Test
    @DisplayName("an end past the entity is clamped, not rejected")
    void endIsClamped() {
        HttpRange clamped = HttpRange.parse("bytes=990-999999", SIZE).range();
        assertEquals(990, clamped.start());
        assertEquals(999, clamped.endInclusive());
        assertTrue(clamped.length() == 10);
    }

    @Test
    @DisplayName("only the first of several ranges is honoured")
    void multiRangeTakesFirst() {
        HttpRange first = HttpRange.parse("bytes=0-99,200-299", SIZE).range();
        assertEquals(0, first.start());
        assertEquals(99, first.endInclusive());
    }

    @Test
    @DisplayName("a start past the entity is unsatisfiable and reports bytes */length")
    void unsatisfiable() {
        HttpRange.Request request = HttpRange.parse("bytes=1000-", SIZE);
        assertEquals(HttpRange.Kind.UNSATISFIABLE, request.kind());
        assertEquals("bytes */1000", request.unsatisfiedContentRange(SIZE));

        assertEquals(HttpRange.Kind.UNSATISFIABLE, HttpRange.parse("bytes=1500-1600", SIZE).kind());
        assertEquals(HttpRange.Kind.UNSATISFIABLE, HttpRange.parse("bytes=-0", SIZE).kind());
        assertEquals(HttpRange.Kind.UNSATISFIABLE, HttpRange.parse("bytes=500-400", SIZE).kind());
    }

    @Test
    @DisplayName("a range over an empty entity is unsatisfiable")
    void emptyEntity() {
        assertEquals(HttpRange.Kind.UNSATISFIABLE, HttpRange.parse("bytes=-10", 0).kind());
    }

    @Test
    @DisplayName("whitespace and casing are tolerated")
    void lenientSyntax() {
        HttpRange range = HttpRange.parse("  BYTES = 10 - 20 ", SIZE).range();
        assertEquals(10, range.start());
        assertEquals(20, range.endInclusive());
    }

    @Test
    @DisplayName("coversWholeEntity recognises a full-length range")
    void coversWholeEntity() {
        assertTrue(HttpRange.parse("bytes=0-999", SIZE).range().coversWholeEntity(SIZE));
        assertFalse(HttpRange.parse("bytes=0-998", SIZE).range().coversWholeEntity(SIZE));
    }

    @Test
    @DisplayName("the record itself rejects inverted or negative bounds")
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new HttpRange(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new HttpRange(10, 5));
    }
}
