package com.yourcompany.payment.session;

import com.yourcompany.payment.config.AppProperties;
import com.yourcompany.payment.exception.SessionConflictException;
import com.yourcompany.payment.exception.SessionException;
import com.yourcompany.payment.exception.StateStoreUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for session state: Redis, with no local fallback.
 *
 * Writes are compare-and-swap, not blind overwrites. The previous version of this class
 * had the classic lost-update defect: two requests could each load a session, each
 * mutate it, and the second save would erase the first request's transition. For OTP
 * verification that meant two live tokens where the first silently stopped working; in
 * the payment phase the same shape is a double charge.
 *
 * The fix is optimistic concurrency rather than a distributed lock. A lock service adds
 * failure modes of its own - stale locks after a pod dies, and "lock store unreachable"
 * as a second way to be down - for a contention pattern that is rare. A version compare
 * costs one round trip and fails loudly when it matters.
 *
 * Storage is a Redis hash with `data` and `version` fields so the compare can read the
 * version without parsing JSON inside Lua. Redis executes the script atomically, so the
 * compare and the write cannot interleave.
 *
 * This class is the only place that knows the key layout, the TTL arithmetic and the
 * id format, which is why it sits beside the model rather than in the service layer.
 */
@Slf4j
@Service
public class SessionService {

    private static final String KEY_PREFIX = "pay:session:";
    private static final String CLAIM_PREFIX = "pay:claim:";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_VERSION = "version";
    private static final int ID_BYTES = 32;
    private static final int ID_LENGTH = 68;

    /**
     * Returns 1 on success, 0 if the version moved, -1 if the session is gone.
     * Distinguishing the last two matters: a moved version is a concurrent request, a
     * missing key is an expired session, and the borrower sees different messages.
     */
    private static final RedisScript<Long> CAS_SAVE = new DefaultRedisScript<>("""
            local stored = redis.call('HGET', KEYS[1], 'version')
            if stored == false then
                return -1
            end
            if stored ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], 'data', ARGV[2], 'version', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(StringRedisTemplate redis,
                          @Qualifier("redisObjectMapper") ObjectMapper mapper,
                          AppProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * Mints an id without persisting anything. The id is needed before the session
     * exists because it is the HKDF salt for key derivation.
     */
    public String mintSessionId() {
        byte[] raw = new byte[ID_BYTES];
        secureRandom.nextBytes(raw);
        String id = "SES_" + Hex.toHexString(raw);
        Arrays.fill(raw, (byte) 0);
        return id;
    }

    public PaymentSession create(String sessionId, byte[] sessionKey, String clientFingerprint) {
        Instant now = Instant.now();
        PaymentSession session = PaymentSession.builder()
                .sessionId(sessionId)
                .state(SessionState.HANDSHAKE_COMPLETE)
                .version(0L)
                .sessionKeyHex(Hex.toHexString(sessionKey))
                .clientFingerprint(clientFingerprint)
                .tokenConsumed(false)
                .createdAt(now)
                .expiresAt(now.plusSeconds(props.getSecurity().getSessionTtlSeconds()))
                .build();

        // A freshly minted id cannot collide, so this write needs no compare.
        try {
            redis.opsForHash().putAll(KEY_PREFIX + sessionId, Map.of(
                    FIELD_DATA, mapper.writeValueAsString(session),
                    FIELD_VERSION, "0"));
            redis.expire(KEY_PREFIX + sessionId,
                    Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds()));
        } catch (RuntimeException e) {
            log.error("Session store unavailable on create: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }
        return session;
    }

    public PaymentSession get(String sessionId) {
        if (sessionId == null || !isWellFormed(sessionId)) {
            throw SessionException.notFound();
        }

        List<Object> fields;
        try {
            fields = redis.opsForHash().multiGet(KEY_PREFIX + sessionId,
                    List.of(FIELD_DATA, FIELD_VERSION));
        } catch (RuntimeException e) {
            log.error("Session store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }

        Object data = fields.get(0);
        Object version = fields.get(1);
        if (data == null || version == null) {
            throw SessionException.notFound();
        }

        PaymentSession session;
        try {
            session = mapper.readValue(String.valueOf(data), PaymentSession.class);
            // The hash field is authoritative, not whatever was serialised into the JSON.
            session.setVersion(Long.parseLong(String.valueOf(version)));
        } catch (Exception e) {
            log.error("Corrupt session payload; discarding");
            invalidate(sessionId);
            throw SessionException.notFound();
        }

        if (session.isExpired() || session.getState() == SessionState.LOCKED) {
            invalidate(sessionId);
            throw SessionException.notFound();
        }
        return session;
    }

    /**
     * Compare-and-swap write. Throws SessionConflictException if another request
     * advanced the session since this one loaded it.
     *
     * On success the caller's object has its version bumped, so a second save in the
     * same request works without re-reading.
     */
    public void save(PaymentSession session) {
        long ttl = Duration.between(Instant.now(), session.getExpiresAt()).getSeconds();
        if (ttl <= 0) {
            invalidate(session.getSessionId());
            throw SessionException.notFound();
        }

        long expected = session.getVersion();
        long next = expected + 1;
        session.setVersion(next);

        Long result;
        try {
            result = redis.execute(CAS_SAVE,
                    List.of(KEY_PREFIX + session.getSessionId()),
                    Long.toString(expected),
                    mapper.writeValueAsString(session),
                    Long.toString(next),
                    Long.toString(ttl));
        } catch (RuntimeException e) {
            session.setVersion(expected);
            log.error("Session store unavailable on write: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }

        if (result == null || result == -1L) {
            session.setVersion(expected);
            throw SessionException.notFound();
        }
        if (result == 0L) {
            session.setVersion(expected);
            log.warn("Concurrent session update rejected for version {}", expected);
            throw new SessionConflictException();
        }
    }

    /**
     * Claims a one-shot operation for this session.
     *
     * Returns true exactly once per (sessionId, operation) within the TTL, however many
     * requests race. Used where two winners is the failure rather than merely untidy:
     * token issuance now, token consumption and payment initiation in the next phase.
     *
     * The TTL matches the session lifetime so a claim cannot outlive the thing it
     * protects and cannot leak if a pod dies mid-request - which is the failure mode a
     * lock would have had.
     */
    public boolean claimOnce(String sessionId, String operation) {
        String key = CLAIM_PREFIX + sessionId + ":" + operation;
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(key, "1",
                    Duration.ofSeconds(props.getSecurity().getSessionTtlSeconds()));
            if (claimed == null) {
                throw new StateStoreUnavailableException();
            }
            return claimed;
        } catch (StateStoreUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Claim store unavailable: {}", e.getClass().getSimpleName());
            throw new StateStoreUnavailableException();
        }
    }

    /** Releases a claim so the operation can be attempted again, after a failed attempt. */
    public void releaseClaim(String sessionId, String operation) {
        try {
            redis.delete(CLAIM_PREFIX + sessionId + ":" + operation);
        } catch (RuntimeException e) {
            log.warn("Could not release claim {} for session", operation);
        }
    }

    public void invalidate(String sessionId) {
        try {
            redis.delete(KEY_PREFIX + sessionId);
        } catch (RuntimeException e) {
            log.warn("Could not delete session key: {}", e.getClass().getSimpleName());
        }
    }

    public byte[] sessionKey(PaymentSession session) {
        return Hex.decode(session.getSessionKeyHex());
    }

    public void requireState(PaymentSession session, SessionState... allowed) {
        if (!session.isInState(allowed)) {
            throw SessionException.wrongState();
        }
    }

    private boolean isWellFormed(String sessionId) {
        return sessionId.length() == ID_LENGTH
                && sessionId.startsWith("SES_")
                && sessionId.chars().skip(4)
                    .allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
