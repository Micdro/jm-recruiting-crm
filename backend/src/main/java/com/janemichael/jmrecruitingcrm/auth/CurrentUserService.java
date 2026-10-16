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

    /**
     * The signed-in account owner. While there's no active owner (an older CRM, or the owner was
     * deactivated), any admin may act, so nothing is ever locked away.
     *
     * @param what what they're trying to do, to finish "Only the owner ... can ..."
     */
    public AppUser requireAccountOwner(String what) {
        AppUser user = get();
        if (user.isAccountOwner()) {
            return user;
        }
        java.util.Optional<AppUser> owner = userRepository.findFirstByAccountOwnerTrueAndActiveTrue();
        if (owner.isEmpty() && user.isAdmin()) {
            return user;
        }
        throw new ForbiddenException("Only the owner" + owner.map(o -> " (" + o.getDisplayName() + ")").orElse("")
                + " can " + what + ".");
    }

    /** Whether the signed-in person may do what {@link #requireAccountOwner} guards. */
    public boolean isAccountOwnerOrStandIn(AppUser user) {
        return user.isAccountOwner()
                || user.isAdmin() && userRepository.findFirstByAccountOwnerTrueAndActiveTrue().isEmpty();
    }

    /** The active account owner's name, or null if there isn't one. */
    public String accountOwnerName() {
        return userRepository.findFirstByAccountOwnerTrueAndActiveTrue().map(AppUser::getDisplayName).orElse(null);
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
