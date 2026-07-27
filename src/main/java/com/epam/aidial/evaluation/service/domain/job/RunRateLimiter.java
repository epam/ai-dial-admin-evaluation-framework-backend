package com.epam.aidial.evaluation.service.domain.job;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-run gate limiting the rate of outgoing HTTP calls, wrapping a Bucket4j token bucket.
 *
 * <p>Acquired at the point <b>each individual HTTP call</b> is issued, not once per dispatched test-case
 * run. The distinction matters: admitting {@code R} dispatches/second where each dispatch emits {@code N}
 * requests (multi-turn turns, multi-request chain requests, or retries) puts {@code R·N} requests/second on
 * the deployment. Sequentiality within a turn loop or a chain changes the burst shape, not the mean, since
 * up to {@code concurrencyLevel} of them overlap.
 *
 * <p>Retries consume tokens too: a retry is a real request, and retries cluster precisely when the target
 * is already returning 429/5xx — the worst moment to bypass the limit.
 *
 * <p>Not a Spring bean: the bucket is per-run state, built from that run's {@code rateLimitRps}. Instances
 * are carried on {@link EvaluationContext} and shared by every worker of the run, which is what makes the
 * limit run-wide rather than per-worker.
 */
@Slf4j
public final class RunRateLimiter {

    /** Null when the run configures no rate limit, making {@link #acquire()} a no-op. */
    private final Bucket bucket;

    private RunRateLimiter(Bucket bucket) {
        this.bucket = bucket;
    }

    /**
     * Builds a gate for the run's configured RPS. A null or non-positive value yields a no-op gate, so
     * calls are dispatched as fast as concurrency allows.
     */
    public static RunRateLimiter of(Double rateLimitRps) {
        if (rateLimitRps == null || rateLimitRps <= 0) {
            return new RunRateLimiter(null);
        }
        final long tokens = Math.max(1, Math.round(rateLimitRps));
        final Bandwidth bandwidth = Bandwidth.builder()
                .capacity(tokens)
                .refillGreedy(tokens, Duration.ofSeconds(1))
                .build();
        return new RunRateLimiter(Bucket.builder().addLimit(bandwidth).build());
    }

    /** A gate that never throttles — the shape a run with no configured {@code rateLimitRps} gets. */
    public static RunRateLimiter disabled() {
        return new RunRateLimiter(null);
    }

    /** True when this run configures a rate limit. Exposed for tests and diagnostics. */
    public boolean isEnabled() {
        return bucket != null;
    }

    /**
     * Blocks until a token is available, then consumes it. No-op when the run configures no rate limit.
     *
     * <p>{@link InterruptedException} is propagated rather than swallowed so that {@code shutdownNow()}
     * cancellation is not delayed by a pending token wait — a worker blocked here must terminate promptly
     * without issuing its call.
     */
    public void acquire() throws InterruptedException {
        if (bucket != null) {
            bucket.asBlocking().consume(1);
        }
    }

    /**
     * Acquires a token, reporting interruption as {@code false} instead of throwing — the form every call
     * site needs, since all three of them must skip their call and return a "not issued" value rather than
     * propagate a checked exception through signatures that cannot carry one.
     *
     * <p>This is the single home for the interrupt policy: re-set the thread's interrupt flag (so
     * {@code InProcessEvaluationExecutor} can tell the case was cancelled and drop its rows) and do not
     * issue the call. Having each call site implement that policy inline is what let the three copies
     * drift apart.
     *
     * @return {@code true} when a token was consumed and the call may be issued; {@code false} when the
     *     wait was interrupted, in which case the interrupt flag is set and NO call must be made
     */
    public boolean tryAcquire() {
        try {
            acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for a rate limit token; not issuing the call", e);
            return false;
        }
    }
}
