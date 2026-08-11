package com.epam.aidial.evaluation.web.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ApiKeyCache")
class ApiKeyCacheTest {

    private ApiKeyCache cache;

    @BeforeEach
    void setUp() {
        ApiKeyProperties properties = new ApiKeyProperties(new ObjectMapper());
        properties.setCacheTtlSeconds(60);
        properties.setCacheMaxSize(100);
        cache = new ApiKeyCache(properties);
    }

    @Test
    @DisplayName("shouldCallLoaderOnceForSameKey")
    void shouldCallLoaderOnceForSameKey() {
        AtomicInteger loadCount = new AtomicInteger();
        Authentication first = cache.getOrAuthenticate("key-1", () -> {
            loadCount.incrementAndGet();
            return authentication("user-1");
        });
        Authentication second = cache.getOrAuthenticate("key-1", () -> {
            loadCount.incrementAndGet();
            return authentication("user-1");
        });

        assertThat(loadCount.get()).isEqualTo(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("shouldCallLoaderPerDistinctKey")
    void shouldCallLoaderPerDistinctKey() {
        AtomicInteger loadCount = new AtomicInteger();
        cache.getOrAuthenticate("key-1", () -> {
            loadCount.incrementAndGet();
            return authentication("user-1");
        });
        cache.getOrAuthenticate("key-2", () -> {
            loadCount.incrementAndGet();
            return authentication("user-2");
        });

        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("shouldNotCacheFailures")
    void shouldNotCacheFailures() {
        AtomicInteger loadCount = new AtomicInteger();

        assertThatThrownBy(() -> cache.getOrAuthenticate("key-1", () -> {
                    loadCount.incrementAndGet();
                    throw new IllegalStateException("introspection failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> cache.getOrAuthenticate("key-1", () -> {
                    loadCount.incrementAndGet();
                    throw new IllegalStateException("introspection failed again");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(loadCount.get()).isEqualTo(2);
    }

    private static Authentication authentication(String principal) {
        return new TestingAuthenticationToken(principal, null, AuthorityUtils.NO_AUTHORITIES);
    }
}
