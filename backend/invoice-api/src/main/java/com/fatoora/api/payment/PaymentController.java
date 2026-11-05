package com.fatoora.api.payment;

import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.payment.PaymentService.PaymentDto;
import com.fatoora.api.payment.PaymentService.PaymentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";

    public record DueDateRequest(LocalDate dueDate) {}

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping("/invoices/{id}/payments")
    List<PaymentDto> list(@PathVariable Long id) {
        return service.list(CurrentUser.orgId(), id);
    }

    @PostMapping("/invoices/{id}/payments")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    PaymentDto record(@PathVariable Long id, @Valid @RequestBody PaymentRequest req) {
        return service.record(CurrentUser.orgId(), CurrentUser.get().email(), id, req);
    }

    @PutMapping("/payments/{id}")
    @PreAuthorize(WRITE)
    PaymentDto update(@PathVariable Long id, @Valid @RequestBody PaymentRequest req) {
        return service.update(CurrentUser.orgId(), id, req);
    }

    @DeleteMapping("/payments/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        service.delete(CurrentUser.orgId(), id);
    }

    @PutMapping("/invoices/{id}/due-date")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dueDate(@PathVariable Long id, @RequestBody DueDateRequest req) {
        service.setDueDate(CurrentUser.orgId(), id, req.dueDate());
    }
}
