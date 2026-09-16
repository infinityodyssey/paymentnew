package com.yourcompany.payment.dto.lms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The subset of the gold-loan response this phase needs.
 *
 * The full LMS payload is persisted to LMS_INTERACTION_LOG for reconciliation; this
 * object carries only what the authentication flow uses. Dues arithmetic, address
 * details, charge breakdowns and the loss-amount branch belong to the payment phase
 * and are deliberately not modelled here yet.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanDetails {
    private String agreementNumber;
    private String customerName;
    private String mobileNumber;
    private String maskedMobile;
    private String accountStatus;
    /** Number of entries in the LMS success array, for audit and later dues aggregation. */
    private int agreementCount;
}
