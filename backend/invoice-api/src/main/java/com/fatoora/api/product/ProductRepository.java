package com.fatoora.api.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdAndOrganizationId(Long id, Long organizationId);

    @Query("select p from Product p where p.organizationId = :orgId and (:all = true or p.active = true) "
            + "and (:q is null or lower(p.name) like :q) order by lower(p.name)")
    List<Product> search(Long orgId, String q, boolean all);

    @Query("select count(p) > 0 from Product p where p.organizationId = :orgId and p.active = true "
            + "and lower(p.name) = lower(:name) and (:exceptId is null or p.id <> :exceptId)")
    boolean activeNameTaken(Long orgId, String name, Long exceptId);

    @Query("select count(l) > 0 from InvoiceLineEntity l where l.productId = :productId")
    boolean usedOnInvoices(Long productId);
}
