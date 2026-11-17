package com.example.alerts.web;

import com.example.alerts.service.CaseService;
import com.example.alerts.web.Dtos.CaseSummaryPage;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Pass-through of Hummingbird's cases and reviews, for polling the results of submitted alerts. */
@RestController
@RequestMapping("/api")
public class CaseController {

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    /** e.g. {@code GET /api/cases?workflow=triage&first=25}; any other query parameter is forwarded. */
    @GetMapping("/cases")
    public Map<String, Object> list(@RequestParam Map<String, String> params) {
        return cases.listCases(params);
    }

    /**
     * Like {@code /cases}, but each case comes with its workflow, stage and status (see
     * {@link CaseService#listCaseSummaries}). Accepts the same parameters plus {@code refresh=true} to bypass the cache.
     */
    @GetMapping("/cases/summaries")
    public CaseSummaryPage summaries(@RequestParam Map<String, String> params) {
        return cases.listCaseSummaries(params);
    }

    @GetMapping("/cases/{token}")
    public Map<String, Object> get(@PathVariable String token) {
        return cases.getCase(token);
    }

    /** e.g. {@code GET /api/reviews?case_token=...&page_size=25}; any other query parameter is forwarded. */
    @GetMapping("/reviews")
    public Map<String, Object> reviews(@RequestParam Map<String, String> params) {
        return cases.listReviews(params);
    }
}
