package com.leash.payment.app;

import com.leash.payment.domain.PaymentRecord;

/** The result of the short transaction that decides and persists a payment. */
public record PreparedPayment(PaymentRecord record, boolean replayed) {}
