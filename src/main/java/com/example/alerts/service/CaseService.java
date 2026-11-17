package com.example.alerts.service;

import com.example.alerts.client.HummingbirdApiException;
import com.example.alerts.client.HummingbirdClient;
import com.example.alerts.config.HummingbirdProperties;
import com.example.alerts.web.Dtos.CaseSummary;
import com.example.alerts.web.Dtos.CaseSummaryPage;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * {@link #listCases}, {@link #getCase} and {@link #listReviews} pass Hummingbird's JSON through unchanged.
 * {@link #listCaseSummaries} adds each case's workflow, stage and status, which the cases list does not carry.
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);
    private static final Set<String> DONE = Set.of("COMPLETED", "CANCELLED", "APPROVED");
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
        return client.listCases(withDefault(params, "first", "25"));
    }

    public Map<String, Object> getCase(String caseToken) {
        return client.getCase(caseToken);
    }

    /** GET /reviews. {@code params} are forwarded (case_token, page_size, ...). */
    public Map<String, Object> listReviews(Map<String, String> params) {
        return client.listReviews(withDefault(params, "page_size", "25"));
    }

    // -------------------------------------------------------------- summaries

    /**
     * A page of cases with workflow / stage / status. The list call returns only tokens and timestamps, so each
     * case is fetched (a few at a time) and summarised. A summary is cached until the case's {@code updated_at}
     * changes; pass {@code refresh=true} to ignore the cache. A case whose details fail to load gets a summary
     * with {@code error} set, so one bad case does not hide the rest; auth/config failures abort the request.
     */
    @SuppressWarnings("unchecked")
    public CaseSummaryPage listCaseSummaries(Map<String, String> params) {
        Map<String, String> query = new LinkedHashMap<>(params);
        boolean refresh = "true".equalsIgnoreCase(query.remove("refresh"));
        Map<String, Object> list = client.listCases(withDefault(query, "first", "25"));

        List<CompletableFuture<CaseSummary>> futures = new ArrayList<>();
        if (list != null && list.get("cases") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m && m.get("case") instanceof Map<?, ?> c) {
                    Map<String, Object> kase = (Map<String, Object>) c;
                    String token = str(kase.get("token"));
                    if (token != null) {
                        futures.add(CompletableFuture.supplyAsync(
                                () -> summarise(token, str(kase.get("created_at")), str(kase.get("updated_at")), refresh), pool));
                    }
                }
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

        Object info = list == null ? null : list.get("page_info");
        boolean hasNext = info instanceof Map<?, ?> i && Boolean.TRUE.equals(i.get("has_next_page"));
        String end = info instanceof Map<?, ?> i ? str(i.get("end_cursor")) : null;
        return new CaseSummaryPage(summaries, hasNext, hasNext ? end : null);
    }

    private CaseSummary summarise(String token, String createdAt, String updatedAt, boolean refresh) {
        if (!refresh) {
            CaseSummary cached = cache.get(token);
            if (cached != null && Objects.equals(cached.updatedAt(), updatedAt)) {
                return cached;
            }
        }
        gate.acquireUninterruptibly();
        try {
            Map<String, Object> res = client.getCase(token);
            CaseSummary summary = fromCase(token, createdAt, updatedAt, res);
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

    @SuppressWarnings("unchecked")
    static CaseSummary fromCase(String token, String createdAt, String updatedAt, Map<String, Object> res) {
        Map<String, Object> kase = res != null && res.get("case") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        List<Map<String, Object>> reviews = new ArrayList<>();
        if (kase.get("reviews") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) reviews.add((Map<String, Object>) m);
            }
        }
        Map<String, Object> pick = null;
        for (Map<String, Object> r : reviews) {
            if (!DONE.contains(String.valueOf(r.get("status")).toUpperCase(Locale.ROOT))) {
                pick = r;
                break;
            }
        }
        if (pick == null && !reviews.isEmpty()) pick = reviews.get(reviews.size() - 1);
        String status = pick == null || pick.get("status") == null ? null : String.valueOf(pick.get("status")).toUpperCase(Locale.ROOT);
        return new CaseSummary(token, str(kase.get("name")),
                str(kase.get("created_at")) != null ? str(kase.get("created_at")) : createdAt,
                updatedAt != null ? updatedAt : str(kase.get("updated_at")),
                pick == null ? null : str(pick.get("workflow")), pick == null ? null : str(pick.get("stage")),
                status, reviews.size(), null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, String> withDefault(Map<String, String> params, String key, String value) {
        Map<String, String> q = new LinkedHashMap<>(params);
        q.putIfAbsent(key, value);
        return q;
    }
}
