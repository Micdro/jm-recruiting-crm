package com.janemichael.jmrecruitingcrm.desktop;

import com.janemichael.jmrecruitingcrm.auth.UserDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * First-run setup for the desktop app. Open to anyone who can reach the app (on the desktop, that is
 * only this computer: the app listens on localhost), and only until setup is done.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    public record StartRequest(
            @Size(max = 100, message = "Computer name must be 100 characters or fewer")
            String computerName,

            @NotBlank(message = "Your name is required")
            @Size(max = 100, message = "Name must be 100 characters or fewer")
            String name,

            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            @Size(max = 255, message = "Email must be 255 characters or fewer")
            String email,

            @NotBlank(message = "Password is required")
            @Size(min = UserDtos.MIN_PASSWORD_LENGTH, max = 200, message = "Password must be at least 10 characters")
            String password
    ) {
    }

    public record JoinRequest(
            @NotBlank(message = "Enter the other computer's name or address")
            @Size(max = 255, message = "Address must be 255 characters or fewer")
            String address,

            @NotBlank(message = "Paste the CRM key")
            @Size(max = 200, message = "That key is too long")
            String key,

            @Size(max = 100, message = "Computer name must be 100 characters or fewer")
            String computerName
    ) {
    }

    @GetMapping("/status")
    public SetupService.Status status() {
        return setup.status();
    }

    @PostMapping("/start")
    public SetupService.StartResult start(@Valid @RequestBody StartRequest request) {
        return setup.startNew(request.computerName(), request.name(), request.email(), request.password());
    }

    @PostMapping("/join")
    public SetupService.JoinResult join(@Valid @RequestBody JoinRequest request) {
        return setup.join(request.address(), request.key(), request.computerName());
    }
}
