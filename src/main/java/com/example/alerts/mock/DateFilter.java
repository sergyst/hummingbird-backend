package com.example.alerts.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * The API's flexible date filter, half-open ({@code start <= x < end}):
 * <ul>
 *   <li>{@code a...b} between, {@code a...} on or after, {@code ...b} before</li>
 *   <li>{@code 2024-02-01} the whole UTC day</li>
 *   <li>{@code 2024-03-15T13:30:00-06:00} that second</li>
 *   <li>empty value: matches only a {@code null} timestamp (only where the parameter allows it)</li>
 * </ul>
 * Date-times without an offset are read as UTC.
 */
record DateFilter(Instant start, Instant end, boolean nullOnly) {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    // an unencoded "+" in a query string arrives as a space: "...T08:00:00 06:00"
    private static final Pattern SPACE_OFFSET = Pattern.compile("(T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?) (\\d{2}:\\d{2})$");

    /** Returns null when the parameter was not sent. */
    static DateFilter parse(String raw, String param, boolean allowEmpty) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) {
            if (allowEmpty) return new DateFilter(null, null, true);
            throw bad(param, "must not be empty");
        }
        int dots = v.indexOf("...");
        if (dots >= 0) {
            String a = v.substring(0, dots).trim();
            String b = v.substring(dots + 3).trim();
            if (a.isEmpty() && b.isEmpty()) throw bad(param, "needs a start, an end, or both");
            return new DateFilter(a.isEmpty() ? null : point(a, param), b.isEmpty() ? null : point(b, param), false);
        }
        if (DATE.matcher(v).matches()) {
            Instant day = point(v, param);
            return new DateFilter(day, day.plus(1, ChronoUnit.DAYS), false);
        }
        Instant second = point(v, param).truncatedTo(ChronoUnit.SECONDS);
        return new DateFilter(second, second.plusSeconds(1), false);
    }

    boolean matches(Instant x) {
        if (nullOnly) return x == null;
        if (x == null) return false;
        return (start == null || !x.isBefore(start)) && (end == null || x.isBefore(end));
    }

    private static Instant point(String s, String param) {
        String v = SPACE_OFFSET.matcher(s).replaceFirst("$1+$2");
        try {
            if (DATE.matcher(v).matches()) return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            throw bad(param, "'" + s + "' is not an ISO 8601 date or date-time");
        }
    }

    private static ApiException bad(String param, String why) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", param + ": " + why, param);
    }
}
