package com.janemichael.jmrecruitingcrm.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users for sign-in. The email address is the username. Also stores the stronger
 * hash when Spring Security upgrades an old password hash after a successful sign-in.
 */
@Service
public class AppUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final AppUserRepository userRepository;

    public AppUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username == null ? "" : username.trim();

        AppUser user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with that email"));

        return toUserDetails(user);
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails details, String newPasswordHash) {
        AppUser user = userRepository.findByEmailIgnoreCase(details.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException("No user with that email"));
        user.setPasswordHash(newPasswordHash);
        userRepository.save(user);
        return toUserDetails(user);
    }

    private static UserDetails toUserDetails(AppUser user) {
        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(!user.isActive())
                .build();
    }
}
