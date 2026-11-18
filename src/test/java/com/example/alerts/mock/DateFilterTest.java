package com.example.alerts.mock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DateFilterTest {

    private static boolean m(String filter, String instant) {
        return DateFilter.parse(filter, "p", true).matches(Instant.parse(instant));
    }

    @Test
    void notSentMeansNoFilter() {
        assertNull(DateFilter.parse(null, "p", true));
    }

    @Test
    void rangesAreHalfOpen() {
        String f = "2024-07-16T08:00:00Z...2024-07-16T12:00:00Z";
        assertTrue(m(f, "2024-07-16T08:00:00Z"), "start is inclusive");
        assertTrue(m(f, "2024-07-16T11:59:59Z"));
        assertFalse(m(f, "2024-07-16T12:00:00Z"), "end is exclusive");
        assertFalse(m(f, "2024-07-16T07:59:59Z"));
    }

    @Test
    void openEndedRanges() {
        assertTrue(m("2024-05-04T22:00:00Z...", "2030-01-01T00:00:00Z"));
        assertFalse(m("2024-05-04T22:00:00Z...", "2024-05-04T21:59:59Z"));
        assertTrue(m("...2024-01-01T00:00:00Z", "2023-12-31T23:59:59Z"));
        assertFalse(m("...2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z"));
    }

    @Test
    void offsetsAreHonoured() {
        // 08:00-06:00 is 14:00Z
        String f = "2024-07-16T08:00:00-06:00...2024-07-16T12:00:00-06:00";
        assertTrue(m(f, "2024-07-16T14:00:00Z"));
        assertFalse(m(f, "2024-07-16T13:59:59Z"));
        assertFalse(m(f, "2024-07-16T18:00:00Z"));
    }

    @Test
    void unencodedPlusArrivesAsSpace() {
        assertTrue(m("2024-07-16T08:00:00 06:00...", "2024-07-16T02:00:00Z"), "'08:00:00 06:00' means +06:00 = 02:00Z");
    }

    @Test
    void singleDateIsTheWholeUtcDay() {
        assertTrue(m("2024-02-01", "2024-02-01T00:00:00Z"));
        assertTrue(m("2024-02-01", "2024-02-01T23:59:59Z"));
        assertFalse(m("2024-02-01", "2024-02-02T00:00:00Z"));
        assertFalse(m("2024-02-01", "2024-01-31T23:59:59Z"));
    }

    @Test
    void singleDateTimeIsThatSecond() {
        assertTrue(m("2024-03-15T13:30:00Z", "2024-03-15T13:30:00.999Z"));
        assertFalse(m("2024-03-15T13:30:00Z", "2024-03-15T13:30:01Z"));
    }

    @Test
    void dateOnlyEndpointsInRanges() {
        assertTrue(m("2024-02-01...2024-02-03", "2024-02-02T12:00:00Z"));
        assertFalse(m("2024-02-01...2024-02-03", "2024-02-03T00:00:00Z"));
    }

    @Test
    void emptyValueMatchesOnlyNullWhereAllowed() {
        DateFilter f = DateFilter.parse("", "completed_at", true);
        assertTrue(f.matches(null));
        assertFalse(f.matches(Instant.now()));
        assertFalse(DateFilter.parse("2024-01-01...", "completed_at", true).matches(null));
        assertThrows(ApiException.class, () -> DateFilter.parse("", "created_at", false));
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(ApiException.class, () -> DateFilter.parse("yesterday", "p", true));
        assertThrows(ApiException.class, () -> DateFilter.parse("...", "p", true));
        assertThrows(ApiException.class, () -> DateFilter.parse("2024-13-45", "p", true));
    }
}
