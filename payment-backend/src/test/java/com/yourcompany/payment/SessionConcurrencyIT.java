package com.yourcompany.payment;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.SessionConflictException;
import com.yourcompany.payment.session.PaymentSession;
import com.yourcompany.payment.session.SessionService;
import com.yourcompany.payment.session.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.security.SecureRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requires a live Redis. Enable with -Dtest=SessionConcurrencyIT once one is reachable,
 * or point spring.data.redis.host at a container.
 *
 * These are the tests that would have caught the lost-update defect. Without them the
 * compare-and-swap is an assertion in a comment.
 */
@Disabled("requires a live Redis; enable in CI where one is provisioned")
@SpringBootTest
class SessionConcurrencyIT {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AppProperties props;

    private String sessionId;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        sessionId = sessionService.mintSessionId();
        sessionService.create(sessionId, key, "fingerprint");
    }

    @Test
    void secondWriterCannotOverwriteTheFirst() {
        PaymentSession a = sessionService.get(sessionId);
        PaymentSession b = sessionService.get(sessionId);

        a.setState(SessionState.OTP_SENT);
        a.setAgreementNumber("GL0001234567");
        sessionService.save(a);

        // b was loaded at the same version and must not be allowed to erase a's transition.
        b.setState(SessionState.OTP_VERIFIED);
        assertThrows(SessionConflictException.class, () -> sessionService.save(b));

        PaymentSession stored = sessionService.get(sessionId);
        assertEquals(SessionState.OTP_SENT, stored.getState());
        assertEquals("GL0001234567", stored.getAgreementNumber());
    }

    @Test
    void exactlyOneOfManyConcurrentWritersSucceeds() throws Exception {
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        PaymentSession loaded = sessionService.get(sessionId);

        try {
            var tasks = IntStream.range(0, writers).<Callable<Void>>mapToObj(i -> () -> {
                PaymentSession copy = sessionService.get(sessionId);
                copy.setVersion(loaded.getVersion());   // all racing from the same version
                copy.setState(SessionState.OTP_SENT);
                try {
                    sessionService.save(copy);
                    succeeded.incrementAndGet();
                } catch (SessionConflictException e) {
                    conflicted.incrementAndGet();
                }
                return null;
            }).toList();

            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, succeeded.get(), "exactly one writer should win the version compare");
        assertEquals(writers - 1, conflicted.get());
    }

    @Test
    void claimOnceElectsASingleWinner() throws Exception {
        int contenders = 16;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        AtomicInteger claims = new AtomicInteger();

        try {
            var tasks = IntStream.range(0, contenders).<Callable<Void>>mapToObj(i -> () -> {
                if (sessionService.claimOnce(sessionId, "token-issue")) {
                    claims.incrementAndGet();
                }
                return null;
            }).toList();

            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, claims.get(), "token issuance must be claimable exactly once");
        assertTrue(props.getSecurity().getSessionTtlSeconds() > 0);
    }
}
