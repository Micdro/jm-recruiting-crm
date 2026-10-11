package com.janemichael.jmrecruitingcrm.auth;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ForbiddenException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Finds the signed-in user and resolves owner ids sent by the UI. */
@Service
public class CurrentUserService {

    private final AppUserRepository userRepository;

    public CurrentUserService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ForbiddenException("You need to sign in");
        }

        return userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ForbiddenException("Your account was not found"));
    }

    public AppUser requireAdmin() {
        AppUser user = get();
        if (!user.isAdmin()) {
            throw new ForbiddenException("Only admins can do this");
        }
        return user;
    }

    /** Looks up a user picked as an owner. Null means no owner. */
    public AppUser findOwner(Long ownerId) {
        if (ownerId == null) {
            return null;
        }
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new BadRequestException("Owner not found"));
    }
}
