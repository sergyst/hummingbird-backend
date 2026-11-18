package com.example.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.alerts.client.HummingbirdApiException;
import com.example.alerts.client.HummingbirdClient;
import com.example.alerts.client.model.CaseDetailResponse;
import com.example.alerts.client.model.CaseList;
import com.example.alerts.client.model.PageInfo;
import com.example.alerts.config.HummingbirdProperties;
import com.example.alerts.web.Dtos.CaseSummary;
import com.example.alerts.web.Dtos.CaseSummaryPage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CaseServiceTest {

    private final HummingbirdClient client = mock(HummingbirdClient.class);
    private final CaseService service = new CaseService(client,
            new HummingbirdProperties("http://x", "2026-02-05", "", "", "", false, Duration.ofSeconds(30), 2));

    private static Instant ts(int n) {
        return Instant.parse("2026-11-01T00:00:00Z").plusSeconds(n);
    }

    /** A cases page; arguments after the cursor are token / updated-at pairs. */
    private static CaseList listOf(boolean hasNext, String cursor, Object... tokenAndUpdated) {
        List<CaseList.Item> cases = new ArrayList<>();
        for (int i = 0; i < tokenAndUpdated.length; i += 2) {
            String token = (String) tokenAndUpdated[i];
            cases.add(new CaseList.Item(token, new CaseList.Ref(token, ts(0), (Instant) tokenAndUpdated[i + 1])));
        }
        return new CaseList(true, cases, new PageInfo(hasNext, false, null, cursor));
    }

    private static CaseDetailResponse.Review review(String workflow, String stage, String status) {
        return new CaseDetailResponse.Review("rev_" + stage, workflow, stage, status);
    }

    private static CaseDetailResponse detail(String token, CaseDetailResponse.Review... reviews) {
        return new CaseDetailResponse(true,
                new CaseDetailResponse.Case(token, null, "Case " + token, ts(0), ts(1), List.of(reviews)));
    }

    @Test
    void summaryComesFromTheFirstActiveReview() {
        when(client.listCases(anyMap())).thenReturn(listOf(true, "cur-1", "t1", ts(1)));
        when(client.getCase("t1")).thenReturn(detail("t1",
                review("triage", "Complete", "completed"), review("aml-investigation", "Escalation", "opened")));

        CaseSummaryPage page = service.listCaseSummaries(Map.of());

        CaseSummary s = page.cases().get(0);
        assertEquals("t1", s.token());
        assertEquals("Case t1", s.name());
        assertEquals("aml-investigation", s.workflow());
        assertEquals("Escalation", s.stage());
        assertEquals("OPENED", s.status());
        assertEquals(2, s.reviewCount());
        assertNull(s.error());
        assertTrue(page.hasNextPage());
        assertEquals("cur-1", page.endCursor());
    }

    @Test
    void whenEverythingIsDoneTheLatestReviewWins() {
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, "t1", ts(1)));
        when(client.getCase("t1")).thenReturn(detail("t1",
                review("triage", "Triage", "completed"), review("filing", "Filing", "approved")));

        CaseSummaryPage page = service.listCaseSummaries(Map.of());

        assertEquals("filing", page.cases().get(0).workflow());
        assertEquals("APPROVED", page.cases().get(0).status());
        assertFalse(page.hasNextPage());
        assertNull(page.endCursor(), "no cursor when there is no next page");
    }

    @Test
    void caseWithoutReviewsHasNoStatus() {
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, "t1", ts(1)));
        when(client.getCase("t1")).thenReturn(detail("t1"));

        CaseSummary s = service.listCaseSummaries(Map.of()).cases().get(0);

        assertNull(s.status());
        assertNull(s.workflow());
        assertEquals(0, s.reviewCount());
    }

    @Test
    void summariesAreCachedUntilTheCaseChanges() {
        when(client.listCases(anyMap()))
                .thenReturn(listOf(false, null, "t1", ts(1)))
                .thenReturn(listOf(false, null, "t1", ts(1)))
                .thenReturn(listOf(false, null, "t1", ts(2)));
        when(client.getCase("t1")).thenReturn(detail("t1", review("triage", "Triage", "opened")));

        service.listCaseSummaries(Map.of());
        service.listCaseSummaries(Map.of());
        verify(client, times(1)).getCase("t1");

        service.listCaseSummaries(Map.of()); // updated_at changed -> fetched again
        verify(client, times(2)).getCase("t1");
    }

    @Test
    void refreshBypassesTheCacheAndIsNotForwarded() {
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, "t1", ts(1)));
        when(client.getCase("t1")).thenReturn(detail("t1", review("triage", "Triage", "opened")));

        service.listCaseSummaries(Map.of());
        service.listCaseSummaries(Map.of("refresh", "true", "workflow", "triage"));

        verify(client, times(2)).getCase("t1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(client, times(2)).listCases(params.capture());
        Map<String, String> second = params.getAllValues().get(1);
        assertEquals("triage", second.get("workflow"));
        assertEquals("25", second.get("first"), "default page size");
        assertFalse(second.containsKey("refresh"));
    }

    @Test
    void oneFailingCaseDoesNotHideTheOthers() {
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, "good", ts(1), "bad", ts(2)));
        when(client.getCase("good")).thenReturn(detail("good", review("triage", "Triage", "opened")));
        when(client.getCase("bad")).thenThrow(new HummingbirdApiException(404, "NOT_FOUND", "Case not found", null));

        List<CaseSummary> cases = service.listCaseSummaries(Map.of()).cases();

        assertEquals(List.of("good", "bad"), cases.stream().map(CaseSummary::token).toList(), "order is kept");
        assertNull(cases.get(0).error());
        assertEquals("Case not found", cases.get(1).error());
        assertNull(cases.get(1).status());
    }

    @Test
    void authFailuresAbortTheRequest() {
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, "t1", ts(1)));
        when(client.getCase("t1")).thenThrow(new HummingbirdApiException(401, "AUTH_ERROR", "bad token", null));

        HummingbirdApiException e = assertThrows(HummingbirdApiException.class, () -> service.listCaseSummaries(Map.of()));
        assertEquals(401, e.getStatus());
    }

    @Test
    void parallelDetailCallsAreLimited() {
        Object[] six = {"t1", ts(1), "t2", ts(1), "t3", ts(1), "t4", ts(1), "t5", ts(1), "t6", ts(1)};
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, six));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(client.getCase(anyString())).thenAnswer(inv -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            Thread.sleep(40);
            running.decrementAndGet();
            return detail(inv.<String>getArgument(0), review("triage", "Triage", "opened"));
        });

        assertEquals(6, service.listCaseSummaries(Map.of()).cases().size());
        assertTrue(peak.get() <= 2, "at most 2 in flight, saw " + peak.get());
        assertTrue(peak.get() >= 1);
    }

    @Test
    void anUndocumentedReviewStatusIsTreatedAsStillActiveAndPassedThrough() {
        when(client.listCases(anyMap())).thenReturn(listOf(false, null, "t1", ts(1)));
        when(client.getCase("t1")).thenReturn(detail("t1", review("triage", "Triage", "brand_new_status")));

        CaseSummary s = service.listCaseSummaries(Map.of()).cases().get(0);

        assertEquals("BRAND_NEW_STATUS", s.status());
        assertEquals("Triage", s.stage());
    }
}
