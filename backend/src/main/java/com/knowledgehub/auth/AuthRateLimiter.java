package com.knowledgehub.auth;

import com.knowledgehub.common.ApiProblem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class AuthRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final int registrationLimit;
    private final int loginLimit;
    private final ConcurrentHashMap<String, Window> registrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LoginKey, Window> logins = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> nextCleanup = new AtomicReference<>(Instant.MIN);

    AuthRateLimiter(Clock clock, @Value("${knowledgehub.auth.registration-limit}") int registrationLimit,
                    @Value("${knowledgehub.auth.login-limit}") int loginLimit) {
        this.clock = clock;
        this.registrationLimit = registrationLimit;
        this.loginLimit = loginLimit;
        if (registrationLimit < 1 || loginLimit < 1) {
            throw new IllegalArgumentException("Authentication rate limits must be positive");
        }
    }

    void register(String ip) {
        var now = clock.instant();
        cleanExpired(now);
        registrations.compute(ip, (key, previous) -> window(previous, now, registrationLimit).increment());
    }

    Optional<CurrentAccount> login(String ip, String username, Supplier<Optional<CurrentAccount>> attempt) {
        var now = clock.instant();
        cleanExpired(now);
        var result = new AtomicReference<Optional<CurrentAccount>>();
        logins.compute(new LoginKey(ip, username), (key, previous) -> {
            var current = window(previous, now, loginLimit);
            var authenticated = attempt.get();
            result.set(authenticated);
            return authenticated.isPresent() ? null : current.increment();
        });
        return result.get();
    }

    private Window window(Window previous, Instant now, int limit) {
        if (previous == null || !previous.expiresAt().isAfter(now)) {
            return new Window(now.plus(WINDOW), 0);
        }
        if (previous.count() >= limit) {
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过多，请稍后再试");
        }
        return previous;
    }

    private void cleanExpired(Instant now) {
        var next = nextCleanup.get();
        if (!next.isAfter(now) && nextCleanup.compareAndSet(next, now.plus(WINDOW))) {
            registrations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            logins.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
    }

    private record LoginKey(String ip, String username) {}
    private record Window(Instant expiresAt, int count) {
        Window increment() {
            return new Window(expiresAt, count + 1);
        }
    }
}
