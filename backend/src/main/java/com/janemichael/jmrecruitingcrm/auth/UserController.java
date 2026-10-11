package com.janemichael.jmrecruitingcrm.auth;

import com.janemichael.jmrecruitingcrm.auth.UserDtos.ChangePasswordRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.CreateUserRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.ResetPasswordRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.SignedInUser;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.UpdateUserRequest;
import com.janemichael.jmrecruitingcrm.auth.UserDtos.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Who is signed in. Returns 401 when nobody is. */
    @GetMapping("/auth/me")
    public SignedInUser me() {
        return userService.me();
    }

    /** Sets the XSRF-TOKEN cookie (see CsrfCookieFilter) before the first sign-in. */
    @GetMapping("/auth/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf() {
    }

    @GetMapping("/users")
    public List<UserResponse> list() {
        return userService.list();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/users/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
    }

    @PostMapping("/users/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeOwnPassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(request);
    }
}
