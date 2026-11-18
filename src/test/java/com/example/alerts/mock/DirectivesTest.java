package com.example.alerts.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.alerts.mock.MockAlert.Plan;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirectivesTest {

    private static Directives of(String comments) {
        return Directives.parse(Map.of("rule", "Some rule", "comments", comments), "p");
    }

    @Test
    void plainFailWordFailsTheAlert() {
        for (String c : List.of("fail", "FAIL", "this should fail please", "failed", "Failure")) {
            assertEquals(Plan.ERROR, of(c).plan(), c);
        }
        assertEquals(Plan.NOT_FILED, of("nothing special").plan());
        assertEquals(Plan.NOT_FILED, of("a failsafe design").plan(), "'failsafe' is not the word 'fail'");
    }

    @Test
    void originalKeywordsStillWork() {
        assertEquals(Plan.STUCK, of("stuck").plan());
        assertEquals(Plan.DISMISSED, of("please dismiss").plan());
        assertEquals(Plan.SUPPRESSED, of("suppressed by rule").plan());
        assertEquals(Plan.ESCALATED, of("escalate this").plan());
        assertEquals(Plan.FILED, of("Structuring pattern").plan());
        assertEquals(Plan.FILED, of("mock:filed").plan());
        assertEquals(Plan.ERROR, of("mock:error").plan());
    }

    @Test
    void explicitDirectivesBeatKeywords() {
        assertEquals(Plan.NOT_FILED, of("structuring mock:not_filed").plan());
        assertEquals(Plan.FILING_REJECTED, of("mock:filing_rejected").plan());
        assertEquals(Plan.FILING_REJECTED, of("mock:filing-rejected").plan());
    }

    @Test
    void customErrorMessage() {
        Directives d = of("mock:error=bad_entity_data");
        assertEquals(Plan.ERROR, d.plan());
        assertEquals("bad entity data", d.errorMessage());
    }

    @Test
    void delayFormats() {
        assertEquals(Duration.ofSeconds(45), of("mock:delay=45s").delay());
        assertEquals(Duration.ofSeconds(45), of("mock:delay=45").delay());
        assertEquals(Duration.ofMinutes(2), of("mock:delay=2m").delay());
        assertEquals(Duration.ofMillis(500), of("mock:delay=500ms").delay());
        assertEquals(Duration.ofSeconds(30), of("mock:delay=PT30S").delay());
        assertEquals(Duration.ZERO, of("mock:instant").delay());
        assertNull(of("plain").delay());
        assertThrows(ApiException.class, () -> of("mock:delay=soon"));
        assertThrows(ApiException.class, () -> of("mock:delay=48h"));
    }

    @Test
    void httpDirectives() {
        assertEquals(503, of("mock:http=503").httpStatus());
        assertEquals(422, of("mock:reject").httpStatus());
        assertNull(of("plain").httpStatus());
        assertThrows(ApiException.class, () -> of("mock:http=200"));
        assertThrows(ApiException.class, () -> of("mock:http=abc"));
    }

    @Test
    void unknownDirectiveIsRejected() {
        ApiException e = assertThrows(ApiException.class, () -> of("mock:bogus"));
        assertTrue(e.getMessage().contains("mock:bogus"));
    }

    @Test
    void directivesInRuleTagsAndOtherInfo() {
        assertEquals(Plan.STUCK, Directives.parse(Map.of("rule", "mock:stuck"), "p").plan());
        assertEquals(Plan.ERROR, Directives.parse(Map.of("rule", "r", "review_tags", List.of("a", "mock:fail")), "p").plan());
        assertEquals(Plan.ERROR, Directives.parse(Map.of("rule", "r",
                "other_info", List.of(Map.of("label", "mock", "value", "fail"))), "p").plan());
        assertEquals(Plan.NOT_FILED, Directives.parse(Map.of("rule", "r",
                "other_info", List.of(Map.of("label", "whitelist_action", "value", "ADDED"))), "p").plan());
    }
}
