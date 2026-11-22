package com.knowledgehub.auth;

import java.io.Serializable;
import java.util.UUID;

public record CurrentAccount(UUID id, String username, Role role, long sessionVersion) implements Serializable {

    public enum Role {
        USER,
        ADMIN
    }
}
