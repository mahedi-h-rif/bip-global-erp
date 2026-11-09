package com.fatoora.api.quotation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "quotation_line")
@Getter
@Setter
public class QuotationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id")
    private Quotation quotation;

    private int position;
    private String name;
    private BigDecimal quantity;
    private String unitCode = "PCE";
    private BigDecimal unitPrice;
    private BigDecimal vatRate = new BigDecimal("15");
    private Long productId;
    /** Base units per unit of this line (dozen = 12). */
    private BigDecimal uomFactor = BigDecimal.ONE;
}
