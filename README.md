# Hummingbird Alerts – Backend

Spring Boot 4 (Java 21, H2 in-memory) server for working with **alerts** in the
[Hummingbird](https://docs.hummingbird.co) sandbox API. The React UI lives in
[hummingbird-ui](https://github.com/sergyst/hummingbird-ui).

## What it does

| Hummingbird API | Used for |
| --- | --- |
| `POST /oauth/token` (client credentials) | Access token, cached and refreshed ~1 min before expiry |
| `POST /alert_imports` | Submit an alert (+ optional individual and transaction) |
| `GET /alert_imports/{token}` | Poll processing state: `pending / processed / error / stuck / dismissed / suppressed`, plus `case_token` / `review_token` |
| `POST /alerts/id_lookup` (303 → `GET /alerts/{token}`) | Case-alert outcome and lifecycle events |
| `GET /cases` (e.g. `first=25&workflow=triage`) | List the cases created from alerts (pass-through) |
| `GET /cases/{token}` | Fetch one case (pass-through) |
| `GET /api/cases/summaries` (this app) | A page of cases **with workflow, stage and status**. The cases list has none of those, so each case is fetched (at most `hummingbird.case-detail-concurrency` = 4 in parallel) and summarised from its first still-active review, else its latest. Cached per case until its `updated_at` changes; `refresh=true` skips the cache. A case that fails to load comes back with `error` set instead of failing the page |
| `GET /reviews` (e.g. `case_token=…&page_size=25`) | List reviews of a case (pass-through) |
| Webhooks `alert.processed`, `alert.stuck` | Instant status updates, verified with HMAC-SHA256 (`X-Signature`) |
| Webhooks `case.created`, `case.merged`, `review.created`, `review.completed`, `action.decision_made`, `filing.status_updated` | Matched to our alerts by case token: activity log, case merges, filing status, and a re-check of the outcome |

Scenarios (`MonitoringRules`) play a fictional customer's activity through a small simulated bank monitoring system
and send the resulting alerts (normal activity → nothing, false alarm → `not_filed`, structuring → `filed`).
Each alert's case outcome (`opened`, `escalated`, `filed`, `not_filed`, `dismissed`) is stored locally and refreshed
on webhook arrival, or every 2 minutes while non-final if webhooks are not set up.

## Things worth knowing
* The API has **no "list alerts" endpoint**, so this app keeps its own copy in H2.
* Processing is asynchronous (usually < 5 min). After 3 failed attempts an alert is `stuck`; only Hummingbird support can un-stick it.
* Re-sending a *processed* alert id is a no-op duplicate; a *dismissed* id is dismissed again.
* Every call needs `?apiVersion=2026-02-05`. 429 responses are retried honouring `Retry-After`.
* Payloads over 2 MB need the Alert **Uploads** API (not implemented).

## Run it

### Credentials
In Hummingbird: *Settings → Organization Settings → Developers → API Keys → Add New* (the client secret is shown once).

```bash
export HUMMINGBIRD_CLIENT_ID=...
export HUMMINGBIRD_CLIENT_SECRET=...
# optional, for webhooks:
export HUMMINGBIRD_WEBHOOK_SECRET=...
```

### Start
```bash
mvn spring-boot:run          # http://localhost:8080
mvn test                     # signature-verification unit test
```

Run the UI from [hummingbird-ui](https://github.com/sergyst/hummingbird-ui); its dev server proxies `/api` to `:8080`.

### Single-jar mode
Build the UI (`npm run build` in hummingbird-ui), copy its `dist/` contents into `src/main/resources/static/`, then:

```bash
mvn package && java -jar target/hummingbird-alerts-0.1.0.jar    # everything on :8080
```

## Webhooks (optional)
Alerts are also polled every 30 s (`hummingbird.poll-interval`), so webhooks are optional. To use them:
1. Expose the backend publicly (e.g. `ngrok http 8080`).
2. In Hummingbird: *Developers → Webhooks → Add New*, URL `https://<host>/api/webhooks/hummingbird`, events **Alert Processed** and **Alert Stuck** (plus the case/review/filing events if you want them).
3. Copy the generated shared secret into `HUMMINGBIRD_WEBHOOK_SECRET` and restart.

Unsigned/invalid messages get `401`. For local experiments only, `hummingbird.webhook-allow-unsigned=true` disables the check.

## REST API
`GET /api/config` · `GET /api/stats` · `GET /api/alerts?state=&q=&page=&size=` · `GET /api/alerts/{id}` ·
`POST /api/alerts` · `POST /api/alerts/{id}/refresh` · `POST /api/alerts/refresh-pending` ·
`GET /api/alerts/{id}/lifecycle` (also stores the outcome) · `DELETE /api/alerts/{id}` (local copy only) ·
`GET /api/cases/summaries?workflow=&first=&after=&refresh=true` · `GET /api/cases?workflow=&first=…` · `GET /api/cases/{token}` · `GET /api/reviews?case_token=&page_size=…`
(cases/reviews forward every query parameter to Hummingbird and return its JSON unchanged; paging: cases use
`first` / `after` (cursor in `page_info.end_cursor`), reviews use `page_size` / `page_token` (`next_page_token`)) ·
`GET /api/scenarios` · `POST /api/scenarios/{normal|false_alarm|structuring}/run` · `POST /api/webhooks/hummingbird`

## Notes
* `POST /api/alerts` accepts optional `workflow` (e.g. `"triage"`), `cryptoAddresses` (`[{id?, address, blockchain}]`) and
  `otherInfo` (`[{label, value}]`), sent as `workflow`, `data.crypto_addresses` (+ `crypto_address` references) and `other_info`.
* The investigator side (decisions, filing) happens in the Hummingbird app; this server only reads cases and reviews.
* H2 is in-memory: alerts disappear on restart (alerts already in Hummingbird are unaffected).
* Use the **sandbox** with test data only. Production is `hummingbird.base-url=https://api.hummingbird.co`.
* `pom.xml` pins Spring Boot `4.0.0`; bump to the latest 4.0.x if you like.
