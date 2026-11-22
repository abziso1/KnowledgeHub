package com.knowledgehub.auth;

import com.knowledgehub.auth.CurrentAccount.Role;
import com.knowledgehub.common.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Account(CurrentAccount principal, String passwordHash, boolean enabled) {}

    public record AdminUser(UUID id, String username, boolean enabled, Instant createdAt) {}

    Optional<Account> byUsername(String username) {
        return jdbc.query("select * from accounts where username = ?", this::mapAccount, username)
                .stream().findFirst();
    }

    boolean isSessionCurrent(CurrentAccount account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from accounts
                    where id = ? and enabled and session_version = ? and role = ?)
                """, Boolean.class, account.id(), account.sessionVersion(), account.role().name()));
    }

    boolean insert(UUID id, String username, String passwordHash, Role role) {
        return jdbc.update("""
                insert into accounts (id, username, password_hash, role)
                values (?, ?, ?, ?) on conflict (username) do nothing
                """, id, username, passwordHash, role.name()) == 1;
    }

    Page<AdminUser> listUsers(int page, int size) {
        var rows = jdbc.query("""
                select id, username, enabled, created_at from accounts where role = 'USER'
                order by created_at desc, id
                limit ? offset ?
                """, (row, index) -> new AdminUser(row.getObject("id", UUID.class),
                row.getString("username"), row.getBoolean("enabled"),
                row.getObject("created_at", OffsetDateTime.class).toInstant()),
                size + 1, (long) page * size);
        return new Page<>(rows.subList(0, Math.min(rows.size(), size)), page, size, rows.size() > size);
    }

    boolean updateUserStatus(UUID id, boolean enabled) {
        return jdbc.update("""
                update accounts
                set enabled = ?, session_version = session_version
                    + case when enabled and not ? then 1 else 0 end
                where id = ? and role = 'USER'
                """, enabled, enabled, id) == 1;
    }

    private Account mapAccount(ResultSet row, int index) throws SQLException {
        var principal = new CurrentAccount(row.getObject("id", UUID.class), row.getString("username"),
                Role.valueOf(row.getString("role")), row.getLong("session_version"));
        return new Account(principal, row.getString("password_hash"), row.getBoolean("enabled"));
    }
}
