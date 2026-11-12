package com.fatoora.api.common;

import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FriendlyValidationTest {

    record Line(@NotBlank String name, @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {}

    record Form(@NotEmpty List<@Valid Line> lines,
                @Size(max = 5) String vatNumber,
                @Pattern(regexp = "[0-9]{4}", message = "4 digits") String buildingNumber,
                @NotBlank String customerId) {}

    private static Map<String, String> errors(Object target) {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        var result = new BeanPropertyBindingResult(target, "form");
        new SpringValidatorAdapter(v).validate(target, result);
        return result.getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FriendlyValidation::message));
    }

    @Test
    void readableSentences() {
        var e = errors(new Form(List.of(new Line("ok", BigDecimal.ONE), new Line(" ", BigDecimal.ZERO)),
                "1234567", "12", null));
        assertEquals("Line 2: Name is required", e.get("lines[1].name"));
        assertEquals("Line 2: Quantity must be more than 0", e.get("lines[1].quantity"));
        assertEquals("VAT number can be at most 5 characters", e.get("vatNumber"));
        assertEquals("Building number: 4 digits", e.get("buildingNumber"));
        assertEquals("Customer is required", e.get("customerId"));
    }

    @Test
    void emptyList() {
        var e = errors(new Form(List.of(), null, null, "c"));
        assertEquals("Lines needs at least one entry", e.get("lines"));
    }

    @Test
    void labels() {
        assertEquals("Row 1: Name", FriendlyValidation.label("[0].name"));
        assertEquals("Entry 3: Unit price", FriendlyValidation.label("entries[2].unitPrice"));
        assertEquals("Item 1, Batch 2: Expiry date", FriendlyValidation.label("items[0].batches[1].expiryDate"));
    }
}
