package com.epam.aidial.evaluation.web.security.apikey;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Caches successful DIAL API-Key introspection results, keyed by a SHA-256 hash of the raw
 * API key so the plaintext key is never held as a cache key or logged. Uses Caffeine's
 * {@link Cache#get(Object, java.util.function.Function)} contract: a mapping function that
 * throws never populates the cache, so failed introspections are never cached.
 */
@Slf4j
@Component
@LogExecution
@ConditionalOnProperty(value = "config.rest.security.api-key.enabled", havingValue = "true")
public class ApiKeyCache {

    private final Cache<String, Authentication> cache;

    public ApiKeyCache(ApiKeyProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.getCacheTtlSeconds()))
                .maximumSize(properties.getCacheMaxSize())
                .build();
        log.debug(
                "Initialized API-key cache: ttl={}s, maxSize={}",
                properties.getCacheTtlSeconds(),
                properties.getCacheMaxSize());
    }

    public Authentication getOrAuthenticate(String apiKey, Supplier<Authentication> loader) {
        String cacheKey = sha256Hex(apiKey);
        return cache.get(cacheKey, key -> loader.get());
    }

    public long size() {
        return cache.estimatedSize();
    }

    private static String sha256Hex(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
