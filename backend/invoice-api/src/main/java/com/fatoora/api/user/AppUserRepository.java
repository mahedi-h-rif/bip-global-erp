package com.fatoora.api.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);

    List<AppUser> findByOrganizationIdAndRoleAndEnabledTrue(Long organizationId, AppUser.Role role);

    List<AppUser> findByOrganizationId(Long organizationId);
}
