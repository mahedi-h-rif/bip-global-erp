package com.fatoora.api.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Page<NotificationLog> findByOrganizationId(Long organizationId, Pageable pageable);

    Page<NotificationLog> findByOrganizationIdAndCustomerId(Long organizationId, Long customerId, Pageable pageable);
}
