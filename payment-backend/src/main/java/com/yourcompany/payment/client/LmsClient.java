package com.yourcompany.payment.client;

import com.yourcompany.payment.dto.lms.LoanDetails;

import java.util.Optional;

/**
 * Gold-loan lookup. Empty means "we could not verify these details", deliberately
 * covering both "no such agreement" and "lookup failed" so the caller cannot tell them
 * apart and therefore neither can the borrower's browser.
 */
public interface LmsClient {
    Optional<LoanDetails> findByAgreementNumber(String agreementNumber);
}
