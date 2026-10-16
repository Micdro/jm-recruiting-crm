package com.janemichael.jmrecruitingcrm.auth;

import com.janemichael.jmrecruitingcrm.auth.UserDtos.ChangePasswordRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.CreateUserRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.ResetPasswordRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.SignedInUser;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.UpdateUserRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.UserResponse;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.ForbiddenException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final CrmLock crmLock;

    public UserService(
            AppUserRepository userRepository,
            CurrentUserService currentUserService,
            PasswordEncoder passwordEncoder,
            CrmLock crmLock
    ) {
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.passwordEncoder = passwordEncoder;
        this.crmLock = crmLock;
    }

    public SignedInUser me() {
        return SignedInUser.from(currentUserService.get(), crmLock.isLocked());
    }

    public List<UserResponse> list() {
        return userRepository.findAllByOrderByDisplayNameAsc()
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        currentUserService.requireAdmin();

        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A user with that email already exists");
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(request.displayName().trim());
        user.setRole(request.role());
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        crmLock.lockKeyFor(user, request.password());

        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        AppUser admin = currentUserService.requireAdmin();
        AppUser user = find(id);

        if (user.getId().equals(admin.getId())) {
            if (!request.active()) {
                throw new BadRequestException("You can't deactivate your own account");
            }
            if (request.role() != UserRole.ADMIN) {
                throw new BadRequestException("You can't remove your own admin role");
            }
        } else if (user.isAccountOwner() && (request.role() != UserRole.ADMIN || !request.active())) {
            throw new ForbiddenException("The owner stays an active admin. Only they can hand over ownership.");
        }

        user.setDisplayName(request.displayName().trim());
        user.setRole(request.role());
        user.setActive(request.active());

        return UserResponse.from(user);
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        AppUser admin = currentUserService.requireAdmin();
        AppUser user = find(id);
        // Otherwise an admin could take over the owner's account, and with it the spending settings.
        if (user.isAccountOwner() && !user.getId().equals(admin.getId())) {
            throw new ForbiddenException("Only the owner can change their own password. If it's lost, it can be "
                    + "reset with the CRM key.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        crmLock.lockKeyFor(user, request.password());
    }

    /**
     * The owner hands ownership to another active teammate, who becomes an admin if they weren't.
     * While there's no owner, any admin can name one, themselves included.
     */
    @Transactional
    public List<UserResponse> handOverOwnership(Long id) {
        AppUser owner = currentUserService.requireAccountOwner("hand over ownership");
        AppUser next = find(id);
        if (next.getId().equals(owner.getId()) && owner.isAccountOwner()) {
            throw new BadRequestException("You're already the owner");
        }
        if (!next.isActive()) {
            throw new BadRequestException("Reactivate " + next.getDisplayName() + " first");
        }
        owner.setAccountOwner(false);
        next.setRole(UserRole.ADMIN);
        next.setAccountOwner(true);
        userRepository.flush();
        return list();
    }

    @Transactional
    public void changeOwnPassword(ChangePasswordRequest request) {
        AppUser user = currentUserService.get();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        crmLock.lockKeyFor(user, request.newPassword());
        userRepository.save(user);
    }

    private AppUser find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}
