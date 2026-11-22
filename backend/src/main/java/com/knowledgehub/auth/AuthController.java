package com.knowledgehub.auth;

import com.knowledgehub.auth.AccountService.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accounts;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;

    public AuthController(AccountService accounts, HttpSessionSecurityContextRepository contexts,
                          HttpSessionCsrfTokenRepository tokens) {
        this.accounts = accounts;
        this.contexts = contexts;
        this.tokens = tokens;
    }

    record Credentials(String username, String password) {}
    record Csrf(String headerName, String token) {}

    @GetMapping("/csrf")
    Csrf csrf(CsrfToken token) {
        return new Csrf(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Viewer register(@RequestBody Credentials credentials, HttpServletRequest request) {
        return accounts.register(credentials.username(), credentials.password(), request.getRemoteAddr());
    }

    @PostMapping("/login")
    Viewer login(@RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
        var account = accounts.login(credentials.username(), credentials.password(), request.getRemoteAddr());
        request.getSession(true);
        request.changeSessionId();
        tokens.saveToken(null, request, response);

        var authority = new SimpleGrantedAuthority("ROLE_" + account.role().name());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(account, null, List.of(authority));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return Viewer.from(account);
    }

    @GetMapping("/me")
    Viewer me(@AuthenticationPrincipal CurrentAccount account) {
        return Viewer.from(account);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        var context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.clearContext();
        contexts.saveContext(context, request, response);
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
