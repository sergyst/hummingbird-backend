package com.example.alerts.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    List<AlertEvent> findByAlertIdOrderByAtDesc(Long alertId);

    void deleteByAlertId(Long alertId);
}
