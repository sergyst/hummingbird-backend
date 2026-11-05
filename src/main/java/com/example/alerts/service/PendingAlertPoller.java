package com.example.alerts.service;

import com.example.alerts.config.HummingbirdProperties;
import com.example.alerts.web.Dtos.RefreshSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hummingbird processes alerts asynchronously (usually within ~5 minutes) and cases then move on
 * (opened, escalated, filed...). Webhooks are the primary signal; this poller is the safety net when
 * webhooks are not registered or are missed.
 */
@Component
public class PendingAlertPoller {

    private static final Logger log = LoggerFactory.getLogger(PendingAlertPoller.class);

    private final AlertService service;
    private final HummingbirdProperties props;

    public PendingAlertPoller(AlertService service, HummingbirdProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${hummingbird.poll-interval:PT30S}", initialDelayString = "PT15S")
    public void poll() {
        if (!props.hasCredentials()) {
            return;
        }
        RefreshSummary s = service.refreshAllPending();
        if (s.checked() > 0) {
            log.debug("Polled {} pending alerts: {} changed, {} failed", s.checked(), s.changed(), s.failed());
        }
        RefreshSummary o = service.syncOutcomesDue();
        if (o.checked() > 0) {
            log.debug("Checked outcome of {} processed alerts: {} changed, {} failed", o.checked(), o.changed(), o.failed());
        }
    }
}
