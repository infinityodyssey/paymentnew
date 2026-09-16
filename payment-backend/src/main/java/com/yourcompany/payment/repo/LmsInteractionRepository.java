package com.yourcompany.payment.repo;

import com.yourcompany.payment.entity.LmsInteraction;
import org.springframework.data.repository.Repository;

/** Insert only. Purging is a privileged job, not something the application can do. */
public interface LmsInteractionRepository extends Repository<LmsInteraction, Long> {
    LmsInteraction save(LmsInteraction interaction);
}
