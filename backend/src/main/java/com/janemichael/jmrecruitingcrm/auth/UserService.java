package com.janemichael.jmrecruitingcrm.auth;

import com.janemichael.jmrecruitingcrm.auth.UserDtos.ChangePasswordRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.CreateUserRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.ResetPasswordRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.UpdateUserRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.UserResponse;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
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

    public UserService(
            AppUserRepository userRepository,
            CurrentUserService currentUserService,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse me() {
        return UserResponse.from(currentUserService.get());
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
        }

        user.setDisplayName(request.displayName().trim());
        user.setRole(request.role());
        user.setActive(request.active());

        return UserResponse.from(user);
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        currentUserService.requireAdmin();
        AppUser user = find(id);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
    }

    @Transactional
    public void changeOwnPassword(ChangePasswordRequest request) {
        AppUser user = currentUserService.get();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    private AppUser find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}
