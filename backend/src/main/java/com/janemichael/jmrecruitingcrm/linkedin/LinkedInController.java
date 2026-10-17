package com.janemichael.jmrecruitingcrm.linkedin;

import com.janemichael.jmrecruitingcrm.linkedin.LinkedInService.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** The firm's LinkedIn app, and connecting each person's LinkedIn to post from the CRM. */
@RestController
@RequestMapping("/api/linkedin")
public class LinkedInController {

    public record AppRequest(
            @NotBlank(message = "Paste the app's Client ID")
            @Size(max = 200, message = "That's too long for a Client ID")
            String clientId,

            @NotBlank(message = "Paste the app's Primary Client Secret")
            @Size(max = 500, message = "That's too long for a client secret")
            String clientSecret
    ) {
    }

    public record ConnectResponse(String url) {
    }

    private final LinkedInService linkedIn;

    public LinkedInController(LinkedInService linkedIn) {
        this.linkedIn = linkedIn;
    }

    @GetMapping
    public Status status() {
        return linkedIn.status(origin());
    }

    @PutMapping("/app")
    public Status saveApp(@Valid @RequestBody AppRequest request) {
        return linkedIn.saveApp(request.clientId(), request.clientSecret(), origin());
    }

    @DeleteMapping("/app")
    public Status removeApp() {
        return linkedIn.removeApp(origin());
    }

    /** Where to send the browser to connect: LinkedIn's sign-in for the firm's app. */
    @GetMapping("/connect")
    public ConnectResponse connect() {
        return new ConnectResponse(linkedIn.connectUrl(origin()));
    }

    /** LinkedIn sends the person back here; the CRM saves the connection and returns them to Marketing. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(linkedIn.callback(code, state, error)))
                .build();
    }

    @DeleteMapping("/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect() {
        linkedIn.disconnect();
    }

    /** This CRM's address as the browser sees it, like http://localhost:4780. */
    private static String origin() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().replacePath(null).replaceQuery(null).build()
                .toUriString();
    }
}
