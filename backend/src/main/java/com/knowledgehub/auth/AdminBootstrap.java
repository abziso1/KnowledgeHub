package com.knowledgehub.auth;

import com.knowledgehub.auth.CurrentAccount.Role;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    AdminBootstrap(AccountRepository accounts, PasswordEncoder passwords,
                   @Value("${knowledgehub.admin.username}") String username,
                   @Value("${knowledgehub.admin.password}") String password) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!username.isEmpty() || !password.isEmpty()) {
            initialize(username, password);
        }
    }

    public void initialize(String rawUsername, String password) {
        String username = AccountService.normalizeUsername(rawUsername);
        AccountService.requirePassword(password);
        var existing = accounts.byUsername(username);
        if (existing.isEmpty() && accounts.insert(UUID.randomUUID(), username, passwords.encode(password), Role.ADMIN)) {
            return;
        }
        if (accounts.byUsername(username).orElseThrow().principal().role() != Role.ADMIN) {
            throw new IllegalStateException("Configured administrator username belongs to a regular user");
        }
    }
}
