package com.kpmg.qtracker.security;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class UserPrincipal implements Principal {

    private final Long id;
    private final String email;
    private final Set<String> roles;

    public UserPrincipal(Long id, String email, Set<String> roles) {
        this.id = id;
        this.email = email;
        this.roles = roles == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }

    public Long getId() {
        return id;
    }

    @Override
    public String getName() {
        return email;
    }

    public String getEmail() {
        return email;
    }

    public Set<String> getRoles() {
        return roles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UserPrincipal that = (UserPrincipal) o;
        return Objects.equals(id, that.id)
                && Objects.equals(email, that.email)
                && Objects.equals(roles, that.roles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email, roles);
    }

    @Override
    public String toString() {
        return "UserPrincipal{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", roles=" + roles +
                '}';
    }
}
