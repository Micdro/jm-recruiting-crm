package com.janemichael.jmrecruitingcrm.sync;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.sync.Upnp.Gateway;
import com.janemichael.jmrecruitingcrm.sync.Upnp.UpnpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Whether other computers can connect straight to this one. When it's on, the sync port is open,
 * the home router is asked to forward it (UPnP), and the addresses this computer can be reached at
 * go into the shared folder for the others. Off by default: the folder alone works everywhere.
 */
@Component
public class DirectSync implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DirectSync.class);
    private static final int LEASE_SECONDS = 3600;
    /** Other ports to try on the router if another computer in the house already has this one. */
    private static final int PORTS_TO_TRY = 10;

    static final String ROUTER_OFF = "This computer didn't ask the router to open a port.";
    static final String ROUTER_REFUSED = "Your router didn't open a port for the CRM (its automatic port "
            + "opening, called UPnP, is off or not supported). Computers on your home network can still connect, "
            + "and this computer still connects to computers that accept connections.";
    static final String SHARED_ADDRESS = "Your internet provider shares one internet address among several "
            + "customers, so other homes can't connect to this computer. Computers on your home network can still "
            + "connect, and this computer still connects to computers that accept connections.";

    /**
     * @param enabled        whether direct connections are turned on here
     * @param listening      whether the sync port is open
     * @param homeAddresses  where computers on this network reach this one
     * @param internetAddress where computers elsewhere reach this one, through the router, if they can
     * @param routerProblem  why computers elsewhere can't, if they can't
     */
    public record DirectStatus(
            boolean enabled,
            boolean listening,
            List<String> homeAddresses,
            String internetAddress,
            String routerProblem
    ) {
    }

    private final JdbcTemplate jdbc;
    private final SyncIdentity identity;
    private final SyncServer server;
    private final boolean desktop;
    private final boolean upnp;

    private volatile boolean running;
    private volatile Gateway gateway;
    private volatile int mappedPort;
    private volatile String internetAddress;
    private volatile String routerProblem;

    public DirectSync(
            JdbcTemplate jdbc,
            SyncIdentity identity,
            SyncServer server,
            @Value("${app.desktop.enabled:false}") boolean desktop,
            @Value("${app.sync.upnp.enabled:true}") boolean upnp
    ) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.server = server;
        this.desktop = desktop;
        this.upnp = upnp;
    }

    /** Reopens the port at startup if direct connections were on; the router is asked in the background. */
    @Override
    public void start() {
        running = true;
        if (desktop && isEnabled() && server.open()) {
            Thread thread = new Thread(this::refreshRouter, "direct-sync-router");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void stop() {
        removeMapping();
        server.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isEnabled() {
        if (!identity.isEnabled()) {
            return false;
        }
        List<Boolean> enabled = jdbc.queryForList("SELECT direct_enabled FROM sync_self LIMIT 1", Boolean.class);
        return !enabled.isEmpty() && Boolean.TRUE.equals(enabled.get(0));
    }

    /** Turns direct connections on or off. Turning on asks the router, which takes a few seconds. */
    public synchronized DirectStatus setEnabled(boolean enabled) {
        if (!desktop || !identity.isEnabled()) {
            throw new BadRequestException("Set up this computer first.");
        }
        if (enabled) {
            if (!server.open()) {
                throw new BadRequestException("Couldn't open port " + server.port()
                        + " for other computers. Another program may be using it.");
            }
            jdbc.update("UPDATE sync_self SET direct_enabled = TRUE");
            refreshRouter();
        } else {
            jdbc.update("UPDATE sync_self SET direct_enabled = FALSE");
            removeMapping();
            server.close();
            internetAddress = null;
            routerProblem = null;
        }
        return status();
    }

    /** Router port forwards expire after an hour unless renewed, so a computer that's gone stops being reachable. */
    @Scheduled(initialDelay = 30 * 60 * 1000L, fixedDelay = 30 * 60 * 1000L)
    public void renew() {
        if (server.isOpen() && isEnabled()) {
            refreshRouter();
        }
    }

    public DirectStatus status() {
        boolean enabled = desktop && isEnabled();
        boolean listening = server.isOpen();
        return new DirectStatus(enabled, listening, listening ? homeAddresses() : List.of(),
                listening ? internetAddress : null, listening ? routerProblem : null);
    }

    /** Where other computers can reach this one, to leave in the shared folder. Empty when off. */
    public List<String> addresses() {
        if (!server.isOpen() || !isEnabled()) {
            return List.of();
        }
        List<String> addresses = new ArrayList<>(homeAddresses());
        String internet = internetAddress;
        if (internet != null) {
            addresses.add(internet);
        }
        return addresses;
    }

    /** This computer's addresses on the networks it's connected to (home, office, VPN). */
    private List<String> homeAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress() && !address.isLoopbackAddress()) {
                        addresses.add(address.getHostAddress() + ":" + server.port());
                    }
                }
            }
        } catch (SocketException exception) {
            log.debug("Could not list network addresses", exception);
        }
        Collections.sort(addresses);
        return addresses;
    }

    /** Asks the router to forward the sync port here, and learns the internet address. */
    synchronized void refreshRouter() {
        if (!upnp) {
            internetAddress = null;
            routerProblem = ROUTER_OFF;
            return;
        }
        try {
            Gateway found = gateway != null ? gateway : Upnp.discover(Duration.ofSeconds(3)).orElse(null);
            if (found == null) {
                gateway = null;
                internetAddress = null;
                routerProblem = ROUTER_REFUSED;
                log.info("No router answered a request to open a port");
                return;
            }
            int external = map(found);
            String ip = Upnp.externalAddress(found);
            gateway = found;
            mappedPort = external;
            if (Upnp.isPublic(ip)) {
                internetAddress = ip + ":" + external;
                routerProblem = null;
                log.info("The router forwards port {} to this computer's sync port", external);
            } else {
                internetAddress = null;
                routerProblem = SHARED_ADDRESS;
                log.info("The router's internet address ({}) is shared, so other homes can't connect", ip);
            }
        } catch (IOException | UpnpException exception) {
            gateway = null;
            internetAddress = null;
            routerProblem = ROUTER_REFUSED;
            log.info("The router didn't open a port: {}", exception.getMessage());
        }
    }

    /** Forwards a port on the router: the sync port itself if it's free there, or one of the next few. */
    private int map(Gateway found) throws IOException, UpnpException {
        int internal = server.port();
        UpnpException last = null;
        int first = mappedPort > 0 ? mappedPort : internal;
        for (int attempt = 0; attempt < PORTS_TO_TRY; attempt++) {
            int external = first + attempt;
            try {
                Upnp.addPortMapping(found, external, internal, LEASE_SECONDS, "JM CRM sync");
                return external;
            } catch (UpnpException refused) {
                if (refused.code() == Upnp.ONLY_PERMANENT_LEASES) {
                    Upnp.addPortMapping(found, external, internal, 0, "JM CRM sync");
                    return external;
                }
                if (refused.code() != Upnp.CONFLICT) {
                    throw refused;
                }
                last = refused;
            }
        }
        throw last;
    }

    private void removeMapping() {
        Gateway found = gateway;
        int external = mappedPort;
        if (found == null || external <= 0) {
            return;
        }
        try {
            Upnp.deletePortMapping(found, external);
        } catch (IOException | UpnpException exception) {
            log.debug("Could not remove the router's port forward", exception);
        }
        mappedPort = 0;
    }
}
