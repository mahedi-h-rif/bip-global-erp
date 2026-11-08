package com.fatoora.api.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A catalogue item. Invoice lines copy its name and price, so changing a product never alters an issued invoice. */
@Entity
@Table(name = "product")
@Getter
@Setter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    /** Incremental catalogue number, e.g. ITEM-000001; never reused. */
    private String number;
    private String name;
    private BigDecimal unitPrice;
    private boolean active = true;
    /** Goods carry stock per warehouse; services (the default) never do. */
    private boolean trackStock;
    private BigDecimal costPrice;
    private BigDecimal lowStockThreshold;
    private String unitCode = "PCE";
    /** Moving-average cost (SAR), maintained by stock movements. */
    private BigDecimal avgCost;
    private String nameAr;
    private String category;
    private String brand;
    private String barcode;
    private boolean isSalesItem = true;
    private boolean isPurchaseItem = true;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
