package com.fatoora.api.config;

import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.user.AppUser;
import com.fatoora.api.user.AppUserRepository;
import com.fatoora.zatca.ZatcaEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First boot of a new client deployment: creates the organization and the first admin from
 * environment variables. Does nothing once they exist, so it is safe to leave configured.
 */
@Component
public class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final FatooraProperties props;
    private final OrganizationRepository orgs;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public Bootstrap(FatooraProperties props, OrganizationRepository orgs, AppUserRepository users, PasswordEncoder encoder) {
        this.props = props;
        this.orgs = orgs;
        this.users = users;
        this.encoder = encoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void run() {
        var b = props.bootstrap();
        if (orgs.count() == 0) {
            var o = b.organization();
            if (o == null || isBlank(o.vatNumber()) || (isBlank(o.legalNameAr()) && isBlank(o.legalNameEn()))) {
                log.warn("No organization yet. Set ORG_* environment variables to bootstrap one.");
                return;
            }
            Organization org = new Organization();
            org.setLegalNameAr(o.legalNameAr());
            org.setLegalNameEn(o.legalNameEn());
            org.setVatNumber(o.vatNumber());
            org.setOtherId(o.otherId());
            org.setStreet(o.street());
            org.setBuildingNumber(o.buildingNumber());
            org.setAdditionalNumber(o.additionalNumber());
            org.setDistrict(o.district());
            org.setCity(o.city());
            org.setPostalCode(o.postalCode());
            org.setEmail(o.email());
            org.setPhone(o.phone());
            org.setZatcaEnvironment(ZatcaEnvironment.valueOf(o.zatcaEnvironment()));
            orgs.save(org);
            log.info("Bootstrapped organization '{}' (VAT {})", org.getLegalNameEn(), org.getVatNumber());
        }

        if (users.count() == 0 && !isBlank(b.adminEmail()) && !isBlank(b.adminPassword())) {
            if (b.adminPassword().length() < 12)
                throw new IllegalStateException("FATOORA_ADMIN_PASSWORD must be at least 12 characters");
            AppUser admin = new AppUser();
            admin.setOrganizationId(orgs.findAll().getFirst().getId());
            admin.setEmail(b.adminEmail().toLowerCase());
            admin.setFullName(b.adminName());
            admin.setPasswordHash(encoder.encode(b.adminPassword()));
            admin.setRole(AppUser.Role.ADMIN);
            users.save(admin);
            log.info("Bootstrapped admin user {}", admin.getEmail());
        }

        // The provider's operator account; created once, independently of the client's admin.
        createOnce(b.operatorEmail(), b.operatorPassword(), b.operatorName(), AppUser.Role.OPERATOR, "FATOORA_OPERATOR_PASSWORD");
        // The client's normal staff account (no dashboard or sales report).
        createOnce(b.userEmail(), b.userPassword(), b.userName(), AppUser.Role.ACCOUNTANT, "FATOORA_USER_PASSWORD");
        // Branch logins (e.g. asthaAluminium): only their own invoices, quotations, agreements and cash-book entries.
        if (!isBlank(b.branchUsers())) {
            for (String entry : b.branchUsers().split(";")) {
                if (entry.isBlank()) continue;
                String[] f = entry.strip().split(":", 3);
                if (f.length < 2) throw new IllegalStateException("FATOORA_BRANCH_USERS entries are login:password:Full name");
                createOnce(f[0].strip(), f[1], f.length > 2 ? f[2].strip() : f[0].strip(), AppUser.Role.BRANCH, "FATOORA_BRANCH_USERS password");
            }
        }
    }

    private void createOnce(String email, String password, String name, AppUser.Role role, String passwordVar) {
        if (isBlank(email) || isBlank(password) || orgs.count() == 0 || users.findByEmailIgnoreCase(email).isPresent()) return;
        if (password.length() < 12) throw new IllegalStateException(passwordVar + " must be at least 12 characters");
        AppUser u = new AppUser();
        u.setOrganizationId(orgs.findAll().getFirst().getId());
        u.setEmail(email.toLowerCase());
        u.setFullName(isBlank(name) ? email : name);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        users.save(u);
        log.info("Bootstrapped {} user {}", role, u.getEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
