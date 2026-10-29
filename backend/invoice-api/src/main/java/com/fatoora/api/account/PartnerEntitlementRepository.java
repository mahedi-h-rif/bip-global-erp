package com.fatoora.api.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartnerEntitlementRepository extends JpaRepository<PartnerEntitlement, Long> {

    List<PartnerEntitlement> findByPartnerIdInAndYear(Collection<Long> partnerIds, int year);

    Optional<PartnerEntitlement> findByPartnerIdAndYear(Long partnerId, int year);
}
