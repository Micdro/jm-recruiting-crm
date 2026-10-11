package com.janemichael.jmrecruitingcrm.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates the first admin from ADMIN_EMAIL and ADMIN_PASSWORD when no users exist yet.
 * After that, admins add teammates from the Team page.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CrmLock crmLock;
    private final String email;
    private final String password;
    private final String name;
    private final boolean desktop;

    public AdminBootstrap(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            CrmLock crmLock,
            @Value("${app.bootstrap.admin-email:}") String email,
            @Value("${app.bootstrap.admin-password:}") String password,
            @Value("${app.bootstrap.admin-name:Admin}") String name,
            @Value("${app.desktop.enabled:false}") boolean desktop
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.crmLock = crmLock;
        this.email = email;
        this.password = password;
        this.name = name;
        this.desktop = desktop;
    }

    @Override
    public void run(ApplicationArguments args) {
        // On the desktop, the first account is created in the setup screen.
        if (desktop || userRepository.count() > 0) {
            return;
        }

        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.warn("No users exist yet. Set ADMIN_EMAIL and ADMIN_PASSWORD to create the first admin account.");
            return;
        }

        if (password.length() < UserDtos.MIN_PASSWORD_LENGTH) {
            log.warn("ADMIN_PASSWORD must be at least {} characters. The admin account was not created.",
                    UserDtos.MIN_PASSWORD_LENGTH);
            return;
        }

        AppUser admin = new AppUser();
        admin.setEmail(email.trim().toLowerCase());
        admin.setDisplayName(StringUtils.hasText(name) ? name.trim() : "Admin");
        admin.setRole(UserRole.ADMIN);
        admin.setActive(true);
        admin.setPasswordHash(passwordEncoder.encode(password));
        crmLock.lockKeyFor(admin, password);
        userRepository.save(admin);

        log.info("Created the first admin account for {}", admin.getEmail());
    }
}
