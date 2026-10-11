package com.janemichael.jmrecruitingcrm.sync;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the sync timer and makes sure new records use this computer's id range. */
@Configuration
@EnableScheduling
@Order(1)
public class SyncStartup implements ApplicationRunner {

    private final SyncIdentity identity;
    private final IdRanges idRanges;

    public SyncStartup(SyncIdentity identity, IdRanges idRanges) {
        this.identity = identity;
        this.idRanges = idRanges;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (identity.isEnabled()) {
            idRanges.apply(identity.nodeId());
        }
    }
}
