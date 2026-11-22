package com.knowledgehub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgehub.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class SessionSecurity {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    HttpSessionSecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountRepository accounts, ObjectMapper json,
                                           HttpSessionSecurityContextRepository contexts,
                                           HttpSessionCsrfTokenRepository csrfTokens) throws Exception {
        return http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .securityContext(security -> security.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/libraries/**", "/api/library-trash/**").hasRole("USER")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> unauthorized(json, response))
                        .accessDeniedHandler((request, response, exception) -> write(json, response, 403,
                                new ApiError("FORBIDDEN", "请求无权限或校验令牌无效"))))
                .addFilterBefore(new SessionVersionFilter(accounts, json), CsrfFilter.class)
                .build();
    }

    private static void unauthorized(ObjectMapper json, HttpServletResponse response) throws IOException {
        write(json, response, 401, new ApiError("AUTH_REQUIRED", "请登录"));
    }

    private static void write(ObjectMapper json, HttpServletResponse response, int status, ApiError body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }

    private static final class SessionVersionFilter extends OncePerRequestFilter {
        private final AccountRepository accounts;
        private final ObjectMapper json;

        private SessionVersionFilter(AccountRepository accounts, ObjectMapper json) {
            this.accounts = accounts;
            this.json = json;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CurrentAccount account
                    && !accounts.isSessionCurrent(account)) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                if (!publicGet(request)) {
                    unauthorized(json, response);
                    return;
                }
            }
            chain.doFilter(request, response);
        }

        private boolean publicGet(HttpServletRequest request) {
            String path = request.getServletPath();
            if (path.isEmpty()) {
                path = request.getRequestURI().substring(request.getContextPath().length());
            }
            return request.getMethod().equals("GET")
                    && (path.equals("/api/auth/csrf") || path.equals("/actuator/health"));
        }
    }
}
