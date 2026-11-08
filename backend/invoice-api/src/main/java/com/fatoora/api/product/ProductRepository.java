package com.fatoora.api.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndOrganizationId(Long id, Long organizationId);

    @Query("select p from Product p where p.organizationId = :orgId and (:all = true or p.active = true) "
            + "and (:q is null or lower(p.name) like :q or lower(p.number) like :q or lower(p.barcode) like :q or lower(p.nameAr) like :q) order by lower(p.name)")
    List<Product> search(Long orgId, String q, boolean all);

    @Query("select count(p) > 0 from Product p where p.organizationId = :orgId and p.active = true "
            + "and lower(p.name) = lower(:name) and (:exceptId is null or p.id <> :exceptId)")
    boolean activeNameTaken(Long orgId, String name, Long exceptId);

    /** True if another product in the org already uses this item code (matches the uq_product_number index, case-sensitive). */
    @Query("select count(p) > 0 from Product p where p.organizationId = :orgId "
            + "and p.number = :number and (:exceptId is null or p.id <> :exceptId)")
    boolean numberTaken(Long orgId, String number, Long exceptId);

    /** Highest auto sequence used by an ITEM-###### code, ignoring any custom codes. Null when there are none yet. */
    @Query(value = "select max(cast(substring(number from 6) as integer)) from product "
            + "where organization_id = :orgId and number ~ '^ITEM-[0-9]+$'", nativeQuery = true)
    Integer maxItemSeq(Long orgId);

    @Query("select count(l) > 0 from InvoiceLineEntity l where l.productId = :productId")
    boolean usedOnInvoices(Long productId);
}
