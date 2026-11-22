package com.knowledgehub.auth;

import com.knowledgehub.auth.AccountRepository.AdminUser;
import com.knowledgehub.common.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AccountService accounts;

    public AdminUserController(AccountService accounts) {
        this.accounts = accounts;
    }

    record UserStatus(@NotNull Boolean enabled) {}

    @GetMapping
    Page<AdminUser> users(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        return accounts.users(page, size);
    }

    @PutMapping("/{userId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateStatus(@PathVariable UUID userId, @Valid @RequestBody UserStatus change) {
        accounts.updateUserStatus(userId, change.enabled());
    }
}
