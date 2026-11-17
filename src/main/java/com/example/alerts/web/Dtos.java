package com.example.alerts.web;

import com.example.alerts.domain.AlertEvent;
import com.example.alerts.domain.AlertRecord;
import com.example.alerts.domain.AlertState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Request/response shapes of this app's own REST API (what the React UI talks to). */
public final class Dtos {

    private Dtos() {
    }

    /** One transaction to include in an alert. */
    public record TransactionInput(
            @PositiveOrZero Long amountMinor,
            @Size(min = 3, max = 8) String currency,
            /** CREDIT or DEBIT. Defaults to DEBIT. */
            String direction,
            @Size(max = 500) String description,
            Instant initiatedAt) {
    }

    /**
     * Payload to submit a new alert to Hummingbird. Either use the single-transaction shortcut
     * ({@code amountMinor}/{@code currency}/{@code direction}) or a full {@code transactions} list.
     */
    public record SubmitAlertRequest(
            @Size(max = 256) String id,
            @NotBlank @Size(max = 256) String rule,
            @Size(max = 49000) String comments,
            Instant triggeredAt,
            List<@Size(max = 100) String> reviewTags,
            @Size(max = 256) String subjectName,
            @PositiveOrZero Long amountMinor,
            @Size(min = 3, max = 8) String currency,
            String direction,
            List<@Valid TransactionInput> transactions,
            /** Hummingbird workflow the alert is routed to, e.g. "triage". Omitted when blank. */
            @Size(max = 100) String workflow,
            List<@Valid CryptoAddressInput> cryptoAddresses,
            List<@Valid OtherInfoInput> otherInfo) {
    }

    /** A crypto address referenced by the alert. {@code id} is generated when blank. */
    public record CryptoAddressInput(
            @Size(max = 256) String id,
            @NotBlank @Size(max = 512) String address,
            /** Hummingbird blockchain enum, e.g. ETHEREUM, BITCOIN. */
            @NotBlank @Size(max = 64) String blockchain) {
    }

    /** Free-form label/value pair sent as the alert's {@code other_info}. */
    public record OtherInfoInput(
            @NotBlank @Size(max = 256) String label,
            @NotBlank @Size(max = 4000) String value) {
    }

    public record AlertView(
            Long id,
            String externalId,
            String processingToken,
            String alertToken,
            String rule,
            String comments,
            Instant triggeredAt,
            List<String> reviewTags,
            String subjectName,
            Long amountMinor,
            Integer txCount,
            String currency,
            AlertState state,
            String outcome,
            String filingStatus,
            String caseToken,
            String reviewToken,
            String errorMessage,
            String source,
            Instant createdAt,
            Instant processedAt,
            Instant lastCheckedAt) {

        public static AlertView of(AlertRecord a) {
            List<String> tags = a.getReviewTags() == null || a.getReviewTags().isBlank()
                    ? List.of()
                    : Arrays.stream(a.getReviewTags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            return new AlertView(a.getId(), a.getExternalId(), a.getProcessingToken(), a.getAlertToken(),
                    a.getRule(), a.getComments(), a.getTriggeredAt(), tags, a.getSubjectName(), a.getAmountMinor(),
                    a.getTxCount(), a.getCurrency(), a.getState(), a.getOutcome(), a.getFilingStatus(),
                    a.getCaseToken(), a.getReviewToken(), a.getErrorMessage(), a.getSource().name(),
                    a.getCreatedAt(), a.getProcessedAt(), a.getLastCheckedAt());
        }
    }

    public record EventView(Long id, String type, String detail, Instant at) {
        public static EventView of(AlertEvent e) {
            return new EventView(e.getId(), e.getType(), e.getDetail(), e.getAt());
        }
    }

    public record AlertDetail(AlertView alert, List<EventView> events) {
    }

    public record PageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }

    public record DayCount(LocalDate date, long count) {
    }

    public record Stats(long total, Map<String, Long> byState, Map<String, Long> byOutcome,
                        List<DayCount> last14Days, Double avgProcessingSeconds) {
    }

    public record RefreshSummary(int checked, int changed, int failed) {
    }

    public record AppConfig(String baseUrl, boolean sandbox, boolean credentialsConfigured,
                            boolean webhookSecretConfigured, String apiVersion, String dashboardUrl) {
    }

    // ------------------------------------------------------------- scenarios

    public record ScenarioInfo(String key, String title, String summary, boolean suspicious, boolean sendsAlert) {
    }

    /** A transaction as it appears in the (simulated) bank's own ledger. */
    public record TxView(String id, String description, long amountMinor, String currency,
                         String direction, String channel, Instant at) {
    }

    public record ScenarioResult(String key, String title, String customer, long customerAvgMonthlyInboundMinor,
                                 List<TxView> transactions, boolean alertSent, String ruleFired,
                                 String explanation, AlertView alert) {
    }
}
