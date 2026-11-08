package com.fatoora.api.invoice;

import com.fatoora.zatca.model.InvoiceLine;
import com.fatoora.zatca.model.VatCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_line")
@Getter
@Setter
public class InvoiceLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    private int position;
    private String name;
    private BigDecimal quantity;
    private String unitCode = "PCE";
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    private VatCategory vatCategory = VatCategory.S;

    private BigDecimal vatRate = new BigDecimal("15");
    private String exemptionCode;
    private String exemptionReason;
    private Long productId;
    /** Set when the line was copied from a delivery: its stock already left on that delivery. */
    @jakarta.persistence.Column(name = "delivery_note_line_id")
    private Long deliveryLineId;

    public InvoiceLine toModel() {
        return new InvoiceLine(name, quantity, unitCode, unitPrice, vatCategory, vatRate, exemptionCode, exemptionReason);
    }
}
