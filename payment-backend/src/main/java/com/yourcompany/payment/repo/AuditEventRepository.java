package com.yourcompany.payment.repo;

import com.yourcompany.payment.entity.AuditEvent;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare Repository marker rather than JpaRepository on purpose: the ledger
 * exposes no delete or update operation to application code.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {
    AuditEvent save(AuditEvent event);
}
