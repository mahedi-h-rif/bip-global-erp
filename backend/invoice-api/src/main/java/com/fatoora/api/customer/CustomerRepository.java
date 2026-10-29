package com.fatoora.api.customer;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<Customer> findByOrganizationId(Long organizationId, Sort sort);

    Optional<Customer> findByIdAndOrganizationId(Long id, Long organizationId);
}
