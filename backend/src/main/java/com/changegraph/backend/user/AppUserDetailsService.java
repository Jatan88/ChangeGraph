package com.changegraph.backend.user;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String email = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
        UserAccount user = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password."));
        return new AppUserDetails(user);
    }
}
