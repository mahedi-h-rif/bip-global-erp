package com.fatoora.api.product;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    /**
     * {@code number} is the item code. On create it defaults to the next server sequence
     * (ITEM-000001, ...) when left blank, and the client may override it with its own code.
     */
    public record ProductDto(
            Long id,
            @Size(max = 30) String number,
            @NotBlank @Size(max = 1000) String name,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            Boolean active,
            Instant updatedAt) {

        static ProductDto of(Product p) {
            return new ProductDto(p.getId(), p.getNumber(), p.getName(), p.getUnitPrice(), p.isActive(), p.getUpdatedAt());
        }
    }

    public record DeleteResult(String result) {}

    /** The item code the create form pre-fills, which the user may edit before saving. */
    public record NextNumber(String number) {}

    private final ProductRepository repo;
    private final AuditService audit;

    public ProductController(ProductRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Active products by default; {@code all=true} includes deactivated ones. */
    @GetMapping
    List<ProductDto> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean all) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return repo.search(CurrentUser.orgId(), like, all).stream().map(ProductDto::of).toList();
    }

    /** The next auto item code, so the create form can pre-fill it (editable). */
    @GetMapping("/next-number")
    NextNumber nextNumber() {
        return new NextNumber(generateNumber(CurrentUser.orgId()));
    }

    @GetMapping("/{id}")
    ProductDto get(@PathVariable Long id) {
        return ProductDto.of(load(id));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ProductDto create(@Valid @RequestBody ProductDto dto) {
        Long orgId = CurrentUser.orgId();
        String name = dto.name().trim();
        requireUniqueName(orgId, name, null);
        String number = dto.number() == null || dto.number().isBlank() ? generateNumber(orgId) : dto.number().trim();
        requireUniqueNumber(orgId, number, null);
        Product p = new Product();
        p.setOrganizationId(orgId);
        p.setNumber(number);
        p.setName(name);
        p.setUnitPrice(dto.unitPrice());
        repo.save(p);
        audit.record(orgId, "PRODUCT_CREATED", "product", p.getId(), p.getNumber() + " " + name + " @ " + p.getUnitPrice());
        return ProductDto.of(p);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    ProductDto update(@PathVariable Long id, @Valid @RequestBody ProductDto dto) {
        Product p = load(id);
        String name = dto.name().trim();
        boolean active = dto.active() == null ? p.isActive() : dto.active();
        if (active) requireUniqueName(p.getOrganizationId(), name, p.getId());
        if (dto.number() != null && !dto.number().isBlank()) {
            String number = dto.number().trim();
            requireUniqueNumber(p.getOrganizationId(), number, p.getId());
            p.setNumber(number);
        }
        p.setName(name);
        p.setUnitPrice(dto.unitPrice());
        p.setActive(active);
        audit.record(p.getOrganizationId(), "PRODUCT_UPDATED", "product", p.getId(), name + " @ " + p.getUnitPrice());
        return ProductDto.of(p);
    }

    /** Deletes an unused product. One that appears on invoices is deactivated instead, to keep the history. */
    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    DeleteResult delete(@PathVariable Long id) {
        Product p = load(id);
        if (repo.usedOnInvoices(p.getId())) {
            p.setActive(false);
            audit.record(p.getOrganizationId(), "PRODUCT_DEACTIVATED", "product", p.getId(), p.getName());
            return new DeleteResult("deactivated");
        }
        repo.delete(p);
        audit.record(p.getOrganizationId(), "PRODUCT_DELETED", "product", p.getId(), p.getName());
        return new DeleteResult("deleted");
    }

    private String generateNumber(Long orgId) {
        Integer max = repo.maxItemSeq(orgId);
        return "ITEM-%06d".formatted((max == null ? 0 : max) + 1);
    }

    private void requireUniqueName(Long orgId, String name, Long exceptId) {
        if (repo.activeNameTaken(orgId, name, exceptId))
            throw new ApiException.Validation(Map.of("name", "A product with this name already exists"));
    }

    private void requireUniqueNumber(Long orgId, String number, Long exceptId) {
        if (repo.numberTaken(orgId, number, exceptId))
            throw new ApiException.Validation(Map.of("number", "A product with this item code already exists"));
    }

    private Product load(Long id) {
        return repo.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Product"));
    }
}
