package com.example.alerts.service;

import com.example.alerts.web.Dtos.TxView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Optional;

/**
 * A deliberately tiny stand-in for a bank's transaction-monitoring system, used by the demo
 * scenarios. It looks at a customer's raw transactions and either finds nothing (so nothing is
 * ever sent to Hummingbird) or produces a hit, which becomes an alert.
 */
public final class MonitoringRules {

    private MonitoringRules() {
    }

    public record Hit(String rule, String comments, List<String> tags) {
    }

    private static final long STRUCTURING_MIN = 9_000_00L;
    private static final long REPORTING_THRESHOLD = 10_000_00L;
    private static final Duration STRUCTURING_WINDOW = Duration.ofHours(48);
    private static final int STRUCTURING_MIN_COUNT = 3;
    private static final long SPIKE_MIN = 10_000_00L;
    private static final long SPIKE_MULTIPLE = 5;

    /** Evaluates all rules in priority order and returns the first hit, if any. */
    public static Optional<Hit> evaluate(List<TxView> txs, long avgMonthlyInboundMinor) {
        Optional<Hit> structuring = structuring(txs);
        if (structuring.isPresent()) {
            return structuring;
        }
        return volumeSpike(txs, avgMonthlyInboundMinor);
    }

    /** Several cash deposits just under the reporting threshold within 48 hours. */
    static Optional<Hit> structuring(List<TxView> txs) {
        List<TxView> deposits = txs.stream()
                .filter(t -> "CREDIT".equals(t.direction()) && "CASH".equals(t.channel()))
                .filter(t -> t.amountMinor() >= STRUCTURING_MIN && t.amountMinor() < REPORTING_THRESHOLD)
                .sorted(Comparator.comparing(TxView::at))
                .toList();
        for (int i = 0; i < deposits.size(); i++) {
            Instant start = deposits.get(i).at();
            List<TxView> window = new ArrayList<>();
            for (int j = i; j < deposits.size(); j++) {
                if (Duration.between(start, deposits.get(j).at()).compareTo(STRUCTURING_WINDOW) <= 0) {
                    window.add(deposits.get(j));
                }
            }
            if (window.size() >= STRUCTURING_MIN_COUNT) {
                long total = window.stream().mapToLong(TxView::amountMinor).sum();
                return Optional.of(new Hit(
                        "Structuring: multiple cash deposits just under reporting threshold",
                        window.size() + " cash deposits between $9,000 and $9,999 within 48 hours, totalling "
                                + money(total) + ".",
                        List.of("structuring", "cash")));
            }
        }
        return Optional.empty();
    }

    /** A single large inbound wire far above what the customer normally receives. */
    static Optional<Hit> volumeSpike(List<TxView> txs, long avgMonthlyInboundMinor) {
        return txs.stream()
                .filter(t -> "CREDIT".equals(t.direction()) && "WIRE".equals(t.channel()))
                .filter(t -> t.amountMinor() >= SPIKE_MIN && t.amountMinor() >= SPIKE_MULTIPLE * avgMonthlyInboundMinor)
                .findFirst()
                .map(t -> new Hit(
                        "Inbound wire far above customer monthly volume",
                        money(t.amountMinor()) + " inbound wire vs. typical " + money(avgMonthlyInboundMinor) + " per month.",
                        List.of("wire", "volume-spike")));
    }

    static String money(long minor) {
        return String.format(Locale.US, "$%,.2f", minor / 100.0);
    }
}
