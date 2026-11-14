package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.Party;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssuedCompanyTest {

    @Test
    void crFromSettingsOtherDetailsAsIssued() {
        Organization org = new Organization();
        org.setLegalNameAr("شركة بي آي بي الخليج للخدمات اللوجستية");
        org.setLegalNameEn("BIP Gulf Logistics Services Company");
        org.setVatNumber("312798399100003");
        org.setOtherId("7000000001"); // changed in Settings after the invoice was issued
        org.setOtherIdScheme("CRN");
        org.setCity("Jeddah");
        org.setPhone("+966 11 000 0000");

        Party issued = new Party("شركة بي آي بي الخليج للخدمات اللوجستية", "312798399100003", "7043504286", "CRN",
                new Address("ابن مفلح", "3965", "6745", "الملز", "الرياض", "12836", "SA"));

        Party seller = InvoicePdfService.withCurrentCr(issued, org);
        assertThat(seller.otherId()).isEqualTo("7000000001"); // CR always from Settings, also in the Seller box
        assertThat(seller.vatNumber()).isEqualTo("312798399100003");
        Organization head = InvoicePdfService.asIssued(org, seller);
        assertThat(head.getOtherId()).isEqualTo("7000000001");
        assertThat(head.getCity()).isEqualTo("الرياض");
        assertThat(head.getPostalCode()).isEqualTo("12836");
        assertThat(head.getPhone()).isEqualTo("+966 11 000 0000"); // contact details stay current
        assertThat(head.getLegalNameEn()).isEqualTo("BIP Gulf Logistics Services Company");
        assertThat(head).isNotSameAs(org);
        assertThat(org.getOtherId()).isEqualTo("7000000001"); // the stored company is untouched
        assertThat(org.getCity()).isEqualTo("Jeddah");
    }
}
