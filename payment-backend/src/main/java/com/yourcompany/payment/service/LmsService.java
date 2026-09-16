package com.yourcompany.payment.service;

import com.yourcompany.payment.client.LmsClient;
import com.yourcompany.payment.dto.lms.LoanDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Single entry point for everything LMS.
 *
 * Thin today because the authentication phase needs one lookup. It exists so the
 * payment phase has an obvious home for dues aggregation, and so nothing outside this
 * class ever holds an LmsClient reference.
 *
 * Deliberately no cache. The legacy design cached loan details for an hour, which is
 * fine for a read-only dues display and wrong here: the mobile number this returns
 * decides where a one-time code is sent, and a stale number sends it to a phone the
 * borrower may no longer control. A cache can be added for the payment-phase dues
 * display, keyed separately from this call.
 */
@Service
@RequiredArgsConstructor
public class LmsService {

    private final LmsClient lmsClient;

    public Optional<LoanDetails> lookup(String agreementNumber) {
        return lmsClient.findByAgreementNumber(agreementNumber);
    }
}
