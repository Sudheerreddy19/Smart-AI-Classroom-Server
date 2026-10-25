package com.finalYear.smartClassRoom.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class RateLimiterService {

    private final int maxRequestsPerMinute;
    private final ConcurrentHashMap<String, UserBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterService(
            @Value("${app.ai.rate-limit.requests-per-minute:15}") int maxRequestsPerMinute
    ) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        log.info("Initialized RateLimiterService: {} requests/minute limit per user", this.maxRequestsPerMinute);
    }

    /**
     * Checks if a request is allowed for the given user key.
     * Increments the counter if allowed.
     *
     * @param userKey Unique user identifier (e.g., "user_1" or IP)
     * @return true if request is permitted under rate limit; false if limit exceeded
     */
    public boolean tryAcquire(String userKey) {
        long now = System.currentTimeMillis();

        UserBucket bucket = buckets.compute(userKey, (key, existing) -> {
            if (existing == null || (now - existing.windowStartMs) >= 60000) {
                return new UserBucket(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        boolean allowed = bucket.count.get() <= maxRequestsPerMinute;
        if (!allowed) {
            log.warn("Rate limit exceeded for {}: {}/{} requests this minute",
                    userKey, bucket.count.get(), maxRequestsPerMinute);
        }
        return allowed;
    }

    /**
     * Get remaining allowed requests in the current 1-minute window.
     */
    public int getRemainingRequests(String userKey) {
        long now = System.currentTimeMillis();
        UserBucket bucket = buckets.get(userKey);
        if (bucket == null || (now - bucket.windowStartMs) >= 60000) {
            return maxRequestsPerMinute;
        }
        return Math.max(0, maxRequestsPerMinute - bucket.count.get());
    }

    private record UserBucket(long windowStartMs, AtomicInteger count) {}
}
