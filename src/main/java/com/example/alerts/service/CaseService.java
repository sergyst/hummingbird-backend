package com.example.alerts.service;

import com.example.alerts.client.HummingbirdApiException;
import com.example.alerts.client.HummingbirdClient;
import com.example.alerts.client.model.CaseDetailResponse;
import com.example.alerts.client.model.CaseList;
import com.example.alerts.config.HummingbirdProperties;
import com.example.alerts.web.Dtos.CaseSummary;
import com.example.alerts.web.Dtos.CaseSummaryPage;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-only access to the cases and reviews Hummingbird created from our alerts.
 * {@link #listCases}, {@link #getCase} and {@link #listReviews} pass Hummingbird's JSON through unchanged (they back
 * the console's raw views). {@link #listCaseSummaries} works on the typed client responses and adds each case's
 * workflow, stage and status, which the cases list does not carry.
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);
    private static final int CACHE_SIZE = 1000;

    private final HummingbirdClient client;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore gate;

    /** token -> the summary built when the case had that updated_at (access-ordered, bounded). */
    private final Map<String, CaseSummary> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CaseSummary> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public CaseService(HummingbirdClient client, HummingbirdProperties props) {
        this.client = client;
        this.gate = new Semaphore(Math.max(1, props.caseDetailConcurrency()));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /** GET /cases. {@code params} are forwarded as query parameters (first, workflow, cursor, ...). */
    public Map<String, Object> listCases(Map<String, String> params) {
        return client.listCasesJson(withDefault(params, "first", "25"));
    }

    public Map<String, Object> getCase(String caseToken) {
        return client.getCaseJson(caseToken);
    }

    /** GET /reviews. {@code params} are forwarded (case_token, page_size, ...). */
    public Map<String, Object> listReviews(Map<String, String> params) {
        return client.listReviewsJson(withDefault(params, "page_size", "25"));
    }

    // -------------------------------------------------------------- summaries

    /**
     * A page of cases with workflow / stage / status. The list call returns only tokens and timestamps, so each
     * case is fetched (a few at a time) and summarised. A summary is cached until the case's {@code updated_at}
     * changes; pass {@code refresh=true} to ignore the cache. A case whose details fail to load gets a summary
     * with {@code error} set, so one bad case does not hide the rest; auth/config failures abort the request.
     */
    public CaseSummaryPage listCaseSummaries(Map<String, String> params) {
        Map<String, String> query = new LinkedHashMap<>(params);
        boolean refresh = "true".equalsIgnoreCase(query.remove("refresh"));
        CaseList list = client.listCases(withDefault(query, "first", "25"));

        List<CompletableFuture<CaseSummary>> futures = new ArrayList<>();
        for (CaseList.Item item : list.cases()) {
            CaseList.Ref ref = item.kase();
            if (ref != null && ref.token() != null) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> summarise(ref.token(), ref.createdAt(), ref.updatedAt(), refresh), pool));
            }
        }

        List<CaseSummary> summaries = new ArrayList<>();
        for (CompletableFuture<CaseSummary> f : futures) {
            try {
                summaries.add(f.join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof HummingbirdApiException api) {
                    throw api; // not configured / bad credentials: the whole request is pointless
                }
                throw e;
            }
        }

        boolean hasNext = list.pageInfo() != null && list.pageInfo().hasNext();
        return new CaseSummaryPage(summaries, hasNext, hasNext ? list.pageInfo().endCursor() : null);
    }

    private CaseSummary summarise(String token, Instant createdAt, Instant updatedAt, boolean refresh) {
        if (!refresh) {
            CaseSummary cached = cache.get(token);
            if (cached != null && Objects.equals(cached.updatedAt(), updatedAt)) {
                return cached;
            }
        }
        gate.acquireUninterruptibly();
        try {
            CaseSummary summary = fromCase(token, createdAt, updatedAt, client.getCase(token));
            cache.put(token, summary);
            return summary;
        } catch (HummingbirdApiException e) {
            if (e.getStatus() == 401 || e.getStatus() == 403 || e.getStatus() == 503) {
                throw e;
            }
            log.warn("Could not load case {}: {} ({})", token, e.getMessage(), e.getCode());
            return new CaseSummary(token, null, createdAt, updatedAt, null, null, null, 0, e.getMessage());
        } finally {
            gate.release();
        }
    }

    /**
     * Summarises a case by one of its reviews: the first one that is still active, else the latest. The list's
     * {@code createdAt}/{@code updatedAt} are the fallback when the detail response has none.
     */
    static CaseSummary fromCase(String token, Instant createdAt, Instant updatedAt, CaseDetailResponse res) {
        CaseDetailResponse.Case kase = res == null ? null : res.kase();
        List<CaseDetailResponse.Review> reviews = kase == null ? List.of() : kase.reviews();
        CaseDetailResponse.Review pick = reviews.stream()
                .filter(r -> r.reviewStatus() == null || !r.reviewStatus().isDone())
                .findFirst()
                .orElse(reviews.isEmpty() ? null : reviews.get(reviews.size() - 1));
        return new CaseSummary(token,
                kase == null ? null : kase.name(),
                kase != null && kase.createdAt() != null ? kase.createdAt() : createdAt,
                updatedAt != null ? updatedAt : kase == null ? null : kase.updatedAt(),
                pick == null ? null : pick.workflow(),
                pick == null ? null : pick.stage(),
                pick == null || pick.status() == null ? null : pick.status().toUpperCase(Locale.ROOT),
                reviews.size(), null);
    }

    private static Map<String, String> withDefault(Map<String, String> params, String key, String value) {
        Map<String, String> q = new LinkedHashMap<>(params);
        q.putIfAbsent(key, value);
        return q;
    }
}
