package com.knowledgehub.auth;

import com.knowledgehub.auth.AccountRepository.AdminUser;
import com.knowledgehub.auth.CurrentAccount.Role;
import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.common.Page;
import com.knowledgehub.library.LibraryRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LibraryRepository libraries;
    private final PasswordEncoder passwords;
    private final AuthRateLimiter rateLimiter;
    private final String dummyHash;

    AccountService(AccountRepository accounts, LibraryRepository libraries, PasswordEncoder passwords,
                   AuthRateLimiter rateLimiter) {
        this.accounts = accounts;
        this.libraries = libraries;
        this.passwords = passwords;
        this.rateLimiter = rateLimiter;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record Viewer(UUID id, String username, Role role) {

        static Viewer from(CurrentAccount account) {
            return new Viewer(account.id(), account.username(), account.role());
        }
    }

    @Transactional
    public Viewer register(String rawUsername, String password, String ip) {
        rateLimiter.register(ip);
        String username = normalizeUsername(rawUsername);
        requirePassword(password);
        var id = UUID.randomUUID();
        if (!accounts.insert(id, username, passwords.encode(password), Role.USER)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "NAME_CONFLICT", "用户名已被使用");
        }
        libraries.insert(id, "我的知识库");
        return new Viewer(id, username, Role.USER);
    }

    CurrentAccount login(String rawUsername, String password, String ip) {
        String username = normalizeUsername(rawUsername);
        requirePassword(password);
        return rateLimiter.login(ip, username, () -> {
            var account = accounts.byUsername(username);
            String hash = account.map(AccountRepository.Account::passwordHash).orElse(dummyHash);
            if (!passwords.matches(password, hash)) {
                return Optional.empty();
            }
            return account.filter(AccountRepository.Account::enabled).map(AccountRepository.Account::principal);
        }).orElseThrow(() -> new ApiProblem(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "用户名或密码错误"));
    }

    Page<AdminUser> users(int page, int size) {
        if (page < 0 || size < 1) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "分页参数无效");
        }
        return accounts.listUsers(page, Math.min(size, 100));
    }

    void updateUserStatus(UUID id, boolean enabled) {
        if (!accounts.updateUserStatus(id, enabled)) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "用户不存在");
        }
    }

    static String normalizeUsername(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请输入用户名");
        }
        String username = raw.strip().toLowerCase(Locale.ROOT);
        if (username.codePointCount(0, username.length()) > 80 || username.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "用户名无效");
        }
        return username;
    }

    static void requirePassword(String password) {
        if (password == null || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "密码必须为 1 到 72 个 UTF-8 字节");
        }
    }
}
