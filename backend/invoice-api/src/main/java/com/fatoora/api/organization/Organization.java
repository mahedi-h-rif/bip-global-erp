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
    /**
     * Letterhead design: CLASSIC (the original), MODERN (themed backdrop) or SKYLINE (line-art skyline,
     * also used on invoices).
     */
    private String letterheadStyle = "CLASSIC";
    /** A short slogan under the Arabic name on the SKYLINE letterhead, e.g. "Engineering Tomorrow, Building Trust Today". */
    private String tagline;
    private String logoUrl;
    /** Base64-encoded PNG or JPEG. */
    private String logo;
    /** Who signs agreements and quotations for the company. */
    private String signatoryName;
    private String bankName;
    private String bankAccountName;
    private String bankAccountNo;
    private String bankIban;
    private String invoiceFooter;
    private String signatoryTitle;
    private String logoContentType;
    private String invoicePrefix = "INV";
    private String creditNotePrefix = "CN";
    private String debitNotePrefix = "DN";

    /** Send payment reminders (automatic and "Send reminder") on WhatsApp too, when a number is linked. */
    private boolean whatsappReminders = true;
    /** Send document expiry reminders to the worker on WhatsApp, when a number is linked. */
    private boolean whatsappDocumentReminders = false;

    @Enumerated(EnumType.STRING)
    private ZatcaEnvironment zatcaEnvironment = ZatcaEnvironment.SANDBOX;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** Empty input is stored as null, so "no Arabic name" is one state, not two. */
    public void setLegalNameAr(String legalNameAr) {
        this.legalNameAr = blankToNull(legalNameAr);
    }

    public void setLegalNameEn(String legalNameEn) {
        this.legalNameEn = blankToNull(legalNameEn);
    }

    /** The name for English text: the English legal name, else the Arabic one (either may be left empty). */
    public String displayName() {
        return legalNameEn != null ? legalNameEn : legalNameAr;
    }

    /** The registration name for ZATCA: the Arabic legal name, else the English one. */
    public String registrationName() {
        return legalNameAr != null ? legalNameAr : legalNameEn;
    }

    /** The seller as ZATCA sees it. The Arabic legal name is the registration name when there is one. */
    public Party toParty() {
        return new Party(registrationName(), vatNumber, otherId, otherIdScheme,
                new Address(street, buildingNumber, additionalNumber, district, city, postalCode, countryCode));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
