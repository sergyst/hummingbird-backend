package com.example.alerts.service;

import com.example.alerts.domain.AlertRecord;
import com.example.alerts.service.MonitoringRules.Hit;
import com.example.alerts.web.Dtos.AlertView;
import com.example.alerts.web.Dtos.ScenarioInfo;
import com.example.alerts.web.Dtos.ScenarioResult;
import com.example.alerts.web.Dtos.SubmitAlertRequest;
import com.example.alerts.web.Dtos.TransactionInput;
import com.example.alerts.web.Dtos.TxView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Runs the three demo scenarios. Each one plays a customer's activity through the (simulated) bank
 * monitoring rules; an alert is sent to Hummingbird only when a rule fires. All people and amounts are fictional.
 */
@Service
public class ScenarioService {

    private record Scenario(String key, String title, String summary, boolean suspicious,
                            String customer, long avgMonthlyInboundMinor, List<TxView> txs) {
    }

    private final AlertService alerts;

    public ScenarioService(AlertService alerts) {
        this.alerts = alerts;
    }

    public List<ScenarioInfo> list() {
        return List.of(build("normal"), build("false_alarm"), build("structuring")).stream()
                .map(s -> new ScenarioInfo(s.key(), s.title(), s.summary(), s.suspicious(),
                        MonitoringRules.evaluate(s.txs(), s.avgMonthlyInboundMinor()).isPresent()))
                .toList();
    }

    public ScenarioResult run(String key) {
        Scenario s = build(key);
        Optional<Hit> hit = MonitoringRules.evaluate(s.txs(), s.avgMonthlyInboundMinor());

        if (hit.isEmpty()) {
            return new ScenarioResult(s.key(), s.title(), s.customer(), s.avgMonthlyInboundMinor(), s.txs(), false, null,
                    "No monitoring rule fired, so nothing is sent to Hummingbird. Clean activity stays in the bank's own systems.",
                    null);
        }

        Hit h = hit.get();
        // Only the transactions that matter go with the alert.
        List<TxView> relevant = "structuring".equals(key) ? s.txs() : s.txs().stream()
                .filter(t -> "WIRE".equals(t.channel()) && "CREDIT".equals(t.direction())).toList();
        List<TransactionInput> inputs = relevant.stream()
                .map(t -> new TransactionInput(t.amountMinor(), t.currency(), t.direction(), t.description(), t.at()))
                .toList();

        AlertRecord rec = alerts.submit(new SubmitAlertRequest(
                null, h.rule(), h.comments(), Instant.now(), h.tags(), s.customer(), null, null, null, inputs));

        return new ScenarioResult(s.key(), s.title(), s.customer(), s.avgMonthlyInboundMinor(), s.txs(), true, h.rule(),
                "Rule fired → alert sent to Hummingbird with " + inputs.size() + " transaction(s). "
                        + "Watch it move from pending to processed, then to its final outcome.",
                AlertView.of(rec));
    }

    // ------------------------------------------------------------------ data

    private Scenario build(String key) {
        Instant now = Instant.now();
        return switch (key) {
            case "normal" -> new Scenario("normal", "Normal deposit and withdrawal",
                    "Anna’s salary arrives and she withdraws some cash. Nothing unusual, so nothing is sent.", false,
                    "Anna Cohen", 3_000_00L, List.of(
                    tx("N-1", "Salary deposit", 2_500_00L, "CREDIT", "WIRE", now.minus(Duration.ofHours(6))),
                    tx("N-2", "ATM cash withdrawal", 300_00L, "DEBIT", "CASH", now.minus(Duration.ofHours(2)))));
            case "false_alarm" -> new Scenario("false_alarm", "False alarm: large but legitimate wire",
                    "Anna receives $18,000 from the sale of her flat. A rule fires, but the investigator finds it legitimate.", false,
                    "Anna Cohen", 3_000_00L, List.of(
                    tx("F-1", "Incoming wire (property sale proceeds)", 18_000_00L, "CREDIT", "WIRE", now.minus(Duration.ofHours(1)))));
            case "structuring" -> new Scenario("structuring", "Suspicious: structuring then wire out",
                    "Five cash deposits just under $10,000 over two days, then almost all of it wired to a new overseas account.", true,
                    "Mark Dalton", 2_000_00L, List.of(
                    tx("S-1", "Cash deposit, Branch 1", 9_400_00L, "CREDIT", "CASH", now.minus(Duration.ofHours(30))),
                    tx("S-2", "Cash deposit, Branch 2", 9_100_00L, "CREDIT", "CASH", now.minus(Duration.ofHours(26))),
                    tx("S-3", "Cash deposit, Branch 1", 9_700_00L, "CREDIT", "CASH", now.minus(Duration.ofHours(20))),
                    tx("S-4", "Cash deposit, Branch 3", 9_300_00L, "CREDIT", "CASH", now.minus(Duration.ofHours(8))),
                    tx("S-5", "Cash deposit, Branch 3", 9_900_00L, "CREDIT", "CASH", now.minus(Duration.ofHours(4))),
                    tx("S-6", "Outbound wire to new overseas account", 46_000_00L, "DEBIT", "WIRE", now.minus(Duration.ofHours(1)))));
            default -> throw new NoSuchElementException("Unknown scenario '" + key + "'");
        };
    }

    private static TxView tx(String id, String description, long amountMinor, String direction, String channel, Instant at) {
        return new TxView(id, description, amountMinor, "USD", direction, channel, at);
    }
}
