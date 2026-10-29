package com.fatoora.api.customer;

import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.Party;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A buyer. Full details (VAT or other ID, national address) are required for standard invoices. */
@Entity
@Table(name = "customer")
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String nameAr;
    private String nameEn;
    private String vatNumber;
    private String otherId;
    private String otherIdScheme;
    /** Free-text address; used as the street when no structured national address is filled in. */
    private String address;
    private String street;
    private String buildingNumber;
    private String additionalNumber;
    private String district;
    private String city;
    private String postalCode;
    private String countryCode = "SA";
    private String email;
    private String phone;
    private boolean remindersEnabled = true;
    private Instant lastReminderAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Party toParty() {
        String name = nameAr != null && !nameAr.isBlank() ? nameAr : nameEn;
        return new Party(name, blankToNull(vatNumber), blankToNull(otherId), blankToNull(otherIdScheme),
                new Address(blankToNull(street) == null ? blankToNull(address) : street, buildingNumber, additionalNumber,
                        district, city, postalCode, countryCode));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
