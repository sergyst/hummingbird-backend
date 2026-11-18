package com.example.alerts.mock;

import com.example.alerts.mock.MockAlert.Plan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Instructions an alert can carry to steer the mock. They are read from the alert's {@code rule},
 * {@code comments}, {@code review_tags} and {@code other_info} (an entry labelled {@code mock} counts as
 * {@code mock:<value>}), case-insensitively.
 *
 * <p>Explicit {@code mock:<command>[=<arg>]} tokens win over the plain keywords. An unknown
 * {@code mock:} token is rejected with 422 so typos are noticed.
 */
record Directives(Plan plan, Duration delay, Integer httpStatus, String errorMessage, List<String> applied) {

    private static final Pattern TOKEN = Pattern.compile("mock:([a-z0-9_\\-]+)(?:=([^\\s,;]+))?");
    private static final Pattern FAIL_WORD = Pattern.compile("\\bfail(ed|s|ure)?\\b");
    private static final Duration MAX_DELAY = Duration.ofHours(24);

    /** Cheat sheet served at {@code GET /mock/directives}: directive -> effect. */
    static final List<Map<String, String>> HELP = List.of(
            help("fail  |  mock:fail", "Alert ends in state `error` (re-sending the same id with different text re-processes it)"),
            help("mock:error=<message>", "Like fail, with your own `error` text (use _ for spaces)"),
            help("stuck  |  mock:stuck", "Alert ends `stuck` and fires the alert.stuck webhook"),
            help("dismiss  |  mock:dismissed", "Alert is `dismissed`"),
            help("suppress  |  mock:suppressed", "Alert is `suppressed`"),
            help("escalat  |  mock:escalated", "processed -> case outcome `escalated`, then stops"),
            help("structuring  |  mock:filed", "processed -> escalated -> `filed` (filing submitted -> accepted)"),
            help("mock:filing_rejected", "Like filed, but the filing ends `rejected`"),
            help("mock:not_filed", "processed -> `not_filed` (this is also the default)"),
            help("mock:delay=<duration>", "Processing delay for this alert: 45s, 2m, 500ms or ISO PT30S (max 24h)"),
            help("mock:instant", "Process on the next tick (delay 0)"),
            help("mock:http=<status>", "POST /alert_imports answers this 4xx/5xx and creates nothing (429 sends Retry-After)"),
            help("mock:reject", "Shortcut for mock:http=422"));

    private static Map<String, String> help(String directive, String effect) {
        return Map.of("directive", directive, "effect", effect);
    }

    /** Parses one alert object (as sent in {@code alerts[i]}). */
    @SuppressWarnings("unchecked")
    static Directives parse(Map<String, Object> alert, String param) {
        StringBuilder sb = new StringBuilder();
        sb.append(alert.get("rule")).append(' ').append(alert.getOrDefault("comments", ""));
        if (alert.get("review_tags") instanceof List<?> tags) tags.forEach(t -> sb.append(' ').append(t));
        if (alert.get("other_info") instanceof List<?> infos) {
            for (Object o : infos) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> info = (Map<String, Object>) m;
                    String label = String.valueOf(info.get("label")).trim();
                    String value = String.valueOf(info.get("value")).trim();
                    sb.append(' ').append("mock".equalsIgnoreCase(label) ? "mock:" + value : value);
                }
            }
        }
        return parse(sb.toString(), param);
    }

    static Directives parse(String rawText, String param) {
        String text = rawText.toLowerCase(Locale.ROOT);
        Plan plan = null;
        Duration delay = null;
        Integer http = null;
        String error = null;
        List<String> applied = new ArrayList<>();

        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String cmd = m.group(1).replace('-', '_');
            String arg = m.group(2);
            applied.add(m.group().trim());
            switch (cmd) {
                case "fail" -> { if (plan == null) plan = Plan.ERROR; error = orElse(error, "Alert failed to process (simulated by mock:fail)"); }
                case "error" -> {
                    if (plan == null) plan = Plan.ERROR;
                    error = arg == null ? orElse(error, null) : arg.replace('_', ' ');
                }
                case "stuck" -> plan = firstSet(plan, Plan.STUCK);
                case "dismissed", "dismiss" -> plan = firstSet(plan, Plan.DISMISSED);
                case "suppressed", "suppress" -> plan = firstSet(plan, Plan.SUPPRESSED);
                case "escalated", "escalate" -> plan = firstSet(plan, Plan.ESCALATED);
                case "filed" -> plan = firstSet(plan, Plan.FILED);
                case "filing_rejected" -> plan = firstSet(plan, Plan.FILING_REJECTED);
                case "not_filed", "notfiled" -> plan = firstSet(plan, Plan.NOT_FILED);
                case "instant" -> delay = Duration.ZERO;
                case "delay" -> delay = parseDelay(arg, param);
                case "reject" -> http = 422;
                case "http" -> http = parseHttp(arg, param);
                default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request",
                        "Unknown mock directive 'mock:" + m.group(1) + "'. See GET /mock/directives.", param);
            }
        }

        if (plan == null) { // plain keywords, in priority order (unchanged from the original mock)
            if (text.contains("stuck")) plan = Plan.STUCK;
            else if (FAIL_WORD.matcher(text).find()) {
                plan = Plan.ERROR;
                error = orElse(error, "Alert failed to process (simulated by 'fail')");
                applied.add("fail");
            }
            else if (text.contains("dismiss")) plan = Plan.DISMISSED;
            else if (text.contains("suppress")) plan = Plan.SUPPRESSED;
            else if (text.contains("escalat")) plan = Plan.ESCALATED;
            else if (text.contains("structuring")) plan = Plan.FILED;
            else plan = Plan.NOT_FILED; // e.g. volume spike -> false alarm
        }
        return new Directives(plan, delay, http, error, List.copyOf(applied));
    }

    private static Plan firstSet(Plan current, Plan wanted) {
        return current == null ? wanted : current;
    }

    private static String orElse(String current, String fallback) {
        return current != null ? current : fallback;
    }

    private static int parseHttp(String arg, String param) {
        try {
            int s = Integer.parseInt(arg == null ? "" : arg);
            if (s >= 400 && s <= 599) return s;
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request",
                "mock:http needs a status between 400 and 599, e.g. mock:http=503", param);
    }

    static Duration parseDelay(String arg, String param) {
        try {
            if (arg == null) throw new IllegalArgumentException();
            Matcher n = Pattern.compile("(\\d+)(ms|s|m|h)?").matcher(arg);
            Duration d;
            if (n.matches()) {
                long v = Long.parseLong(n.group(1));
                d = switch (n.group(2) == null ? "s" : n.group(2)) {
                    case "ms" -> Duration.ofMillis(v);
                    case "m" -> Duration.ofMinutes(v);
                    case "h" -> Duration.ofHours(v);
                    default -> Duration.ofSeconds(v);
                };
            } else {
                d = Duration.parse(arg.toUpperCase(Locale.ROOT));
            }
            if (d.isNegative() || d.compareTo(MAX_DELAY) > 0) throw new IllegalArgumentException();
            return d;
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request",
                    "mock:delay needs a duration like 45s, 2m, 500ms or PT30S (max 24h)", param);
        }
    }
}
