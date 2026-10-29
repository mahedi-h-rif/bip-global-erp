package com.fatoora.api.payment;

import java.math.BigDecimal;

/** Sum of the payments recorded against one invoice. */
public record PaidTotal(Long invoiceId, BigDecimal amount) {}
