package com.hytechster.ewallet.user;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The logged-in principal. Money code always takes the acting user's id from here,
 * never from a form field.
 */
public record AppUserDetails(long id, String email, String fullName, Role role, String passwordHash)
        implements UserDetails {

    public static AppUserDetails of(User user) {
        return new AppUserDetails(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getPasswordHash());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    public String firstName() {
        int space = fullName.indexOf(' ');
        return space > 0 ? fullName.substring(0, space) : fullName;
    }
}
