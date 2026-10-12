package com.janemichael.jmrecruitingcrm.sync;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Asks the home router to forward a port to this computer, the way games and torrent apps do
 * (UPnP Internet Gateway Device). Many home routers allow it; some have it switched off.
 */
final class Upnp {

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
    };

    /** The router's port-forwarding service, and this computer's address on the router's network. */
    record Gateway(URI controlUrl, String serviceType, InetAddress localAddress) {
    }

    /** The router refused (with a UPnP error code) or answered with something unusable. */
    static class UpnpException extends Exception {
        private final int code;

        UpnpException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }

    /** The mapping already exists for another computer. */
    static final int CONFLICT = 718;
    /** The router only accepts mappings that never expire. */
    static final int ONLY_PERMANENT_LEASES = 725;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private Upnp() {
    }

    /** Finds the router on this network that will forward ports, or empty if none answers. */
    static Optional<Gateway> discover(Duration wait) throws IOException {
        Set<String> locations = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(250);
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            for (String target : SEARCH_TARGETS) {
                byte[] search = ("M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(search, search.length, group, SSDP_PORT));
            }
            long deadline = System.nanoTime() + wait.toNanos();
            byte[] buffer = new byte[2048];
            while (System.nanoTime() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException quiet) {
                    if (!locations.isEmpty()) {
                        break;
                    }
                    continue;
                }
                String location = header(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII),
                        "location");
                if (location != null) {
                    locations.add(location);
                }
            }
        }

        for (String location : locations) {
            try {
                URI uri = URI.create(location);
                HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    continue;
                }
                Optional<ParsedService> service = parseDescription(uri, response.body());
                if (service.isPresent()) {
                    return Optional.of(new Gateway(service.get().controlUrl(), service.get().serviceType(),
                            localAddressFacing(uri)));
                }
            } catch (IOException | IllegalArgumentException unusable) {
                // Try the next device that answered.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The router's address on the internet. */
    static String externalAddress(Gateway gateway) throws IOException, UpnpException {
        Map<String, String> result = call(gateway, "GetExternalIPAddress", Map.of());
        String address = result.get("NewExternalIPAddress");
        if (address == null || address.isBlank()) {
            throw new UpnpException(0, "The router didn't say what its internet address is.");
        }
        return address.trim();
    }

    static void addPortMapping(Gateway gateway, int externalPort, int internalPort, int leaseSeconds, String description)
            throws IOException, UpnpException {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("NewRemoteHost", "");
        arguments.put("NewExternalPort", String.valueOf(externalPort));
        arguments.put("NewProtocol", "TCP");
        arguments.put("NewInternalPort", String.valueOf(internalPort));
        arguments.put("NewInternalClient", gateway.localAddress().getHostAddress());
        arguments.put("NewEnabled", "1");
        arguments.put("NewPortMappingDescription", description);
        arguments.put("NewLeaseDuration", String.valueOf(leaseSeconds));
        call(gateway, "AddPortMapping", arguments);
    }

    static void deletePortMapping(Gateway gateway, int externalPort) throws IOException, UpnpException {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("NewRemoteHost", "");
        arguments.put("NewExternalPort", String.valueOf(externalPort));
        arguments.put("NewProtocol", "TCP");
        call(gateway, "DeletePortMapping", arguments);
    }

    /** Whether other people's computers on the internet can reach this address. */
    static boolean isPublic(String address) {
        try {
            InetAddress ip = InetAddress.getByName(address);
            if (!(ip instanceof Inet4Address)) {
                return false;
            }
            byte[] bytes = ip.getAddress();
            boolean sharedBetweenCustomers = (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64; // 100.64.0.0/10
            return !(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                    || ip.isSiteLocalAddress() || ip.isMulticastAddress() || sharedBetweenCustomers);
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    // SOAP --------------------------------------------------------------------------

    private static Map<String, String> call(Gateway gateway, String action, Map<String, String> arguments)
            throws IOException, UpnpException {
        HttpRequest request = HttpRequest.newBuilder(gateway.controlUrl())
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"" + gateway.serviceType() + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope(gateway.serviceType(), action, arguments),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        Map<String, String> values = parseResponse(response.body());
        if (response.statusCode() != 200) {
            int code = parseCode(values.get("errorCode"));
            String detail = values.getOrDefault("errorDescription", "HTTP " + response.statusCode());
            throw new UpnpException(code, "The router refused " + action + ": " + detail);
        }
        return values;
    }

    static String envelope(String serviceType, String action, Map<String, String> arguments) {
        StringBuilder body = new StringBuilder()
                .append("<?xml version=\"1.0\"?>")
                .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                .append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(escape(serviceType)).append("\">");
        arguments.forEach((name, value) ->
                body.append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>'));
        return body.append("</u:").append(action).append("></s:Body></s:Envelope>").toString();
    }

    /** Every leaf element of a SOAP response (or fault) by its local name. */
    static Map<String, String> parseResponse(byte[] xml) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            Document document = parse(xml);
            NodeList all = document.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);
                if (!hasChildElements(element)) {
                    values.put(localName(element.getTagName()), element.getTextContent().trim());
                }
            }
        } catch (IOException | SAXException | ParserConfigurationException unreadable) {
            // Leave it empty: the caller reports what it can.
        }
        return values;
    }

    // Device description ---------------------------------------------------------

    record ParsedService(URI controlUrl, String serviceType) {
    }

    /** Finds the port-forwarding service in a router's description, preferring WANIPConnection. */
    static Optional<ParsedService> parseDescription(URI location, byte[] xml) {
        try {
            Document document = parse(xml);
            URI base = location;
            NodeList bases = document.getElementsByTagName("URLBase");
            if (bases.getLength() > 0 && !bases.item(0).getTextContent().isBlank()) {
                base = URI.create(bases.item(0).getTextContent().trim());
            }
            ParsedService ppp = null;
            NodeList services = document.getElementsByTagName("service");
            for (int i = 0; i < services.getLength(); i++) {
                Element service = (Element) services.item(i);
                String type = childText(service, "serviceType");
                String control = childText(service, "controlURL");
                if (type == null || control == null) {
                    continue;
                }
                if (type.contains(":WANIPConnection:")) {
                    return Optional.of(new ParsedService(base.resolve(control), type));
                }
                if (type.contains(":WANPPPConnection:") && ppp == null) {
                    ppp = new ParsedService(base.resolve(control), type);
                }
            }
            return Optional.ofNullable(ppp);
        } catch (IOException | SAXException | ParserConfigurationException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    // Helpers -----------------------------------------------------------------------

    private static InetAddress localAddressFacing(URI router) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(router.getHost(), router.getPort() > 0 ? router.getPort() : 80));
            return socket.getLocalAddress();
        }
    }

    static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static Document parse(byte[] xml) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // A router's XML is untrusted: no external entities or DTDs.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static String childText(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName(element.getTagName()).equals(name)) {
                String text = element.getTextContent();
                return text == null || text.isBlank() ? null : text.trim();
            }
        }
        return null;
    }

    private static boolean hasChildElements(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static String localName(String tag) {
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static int parseCode(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
