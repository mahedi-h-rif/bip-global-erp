package com.fatoora.api.organization;

import com.fatoora.zatca.ZatcaEnvironment;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.Party;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** The seller: the client company that owns this deployment. */
@Entity
@Table(name = "organization")
@Getter
@Setter
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String legalNameAr;
    private String legalNameEn;
    private String vatNumber;
    private String otherId;
    private String otherIdScheme = "CRN";
    private String street;
    private String buildingNumber;
    private String additionalNumber;
    private String district;
    private String city;
    private String postalCode;
    private String countryCode = "SA";
    private String phone;
    private String email;
    private String website;
    private String brandColor = "#0B2A6F";
    private String logoUrl;
    /** Base64-encoded PNG or JPEG. */
    private String logo;
    /** Who signs agreements and quotations for the company. */
    private String signatoryName;
    private String signatoryTitle;
    private String logoContentType;
    private String invoicePrefix = "INV";
    private String creditNotePrefix = "CN";
    private String debitNotePrefix = "DN";

    @Enumerated(EnumType.STRING)
    private ZatcaEnvironment zatcaEnvironment = ZatcaEnvironment.SANDBOX;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** The seller as ZATCA sees it. The Arabic legal name is the registration name. */
    public Party toParty() {
        return new Party(legalNameAr, vatNumber, otherId, otherIdScheme,
                new Address(street, buildingNumber, additionalNumber, district, city, postalCode, countryCode));
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
