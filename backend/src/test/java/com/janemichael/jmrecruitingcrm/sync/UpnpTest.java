package com.janemichael.jmrecruitingcrm.sync;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UpnpTest {

    private static final URI LOCATION = URI.create("http://192.168.1.1:5000/rootDesc.xml");

    @Test
    void findsTheRoutersPortForwardingService() {
        String description = """
                <?xml version="1.0"?>
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                        <controlURL>/ctl/L3F</controlURL>
                      </service>
                    </serviceList>
                    <deviceList><device><deviceList><device>
                      <serviceList>
                        <service>
                          <serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>
                          <controlURL>/ctl/PPPConn</controlURL>
                        </service>
                        <service>
                          <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                          <controlURL>/ctl/IPConn</controlURL>
                        </service>
                      </serviceList>
                    </device></deviceList></device></deviceList>
                  </device>
                </root>
                """;

        Optional<Upnp.ParsedService> service = Upnp.parseDescription(LOCATION, bytes(description));

        assertThat(service).isPresent();
        assertThat(service.get().serviceType()).isEqualTo("urn:schemas-upnp-org:service:WANIPConnection:1");
        assertThat(service.get().controlUrl()).isEqualTo(URI.create("http://192.168.1.1:5000/ctl/IPConn"));
    }

    @Test
    void usesThePppServiceAndTheBaseAddressWhenThatIsAllThereIs() {
        String description = """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <URLBase>http://192.168.0.1:49000</URLBase>
                  <device><serviceList><service>
                    <serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>
                    <controlURL>/upnp/control/WANPPPConn1</controlURL>
                  </service></serviceList></device>
                </root>
                """;

        Upnp.ParsedService service = Upnp.parseDescription(LOCATION, bytes(description)).orElseThrow();

        assertThat(service.controlUrl()).isEqualTo(URI.create("http://192.168.0.1:49000/upnp/control/WANPPPConn1"));
    }

    @Test
    void ignoresDevicesThatDontForwardPortsAndRefusesXmlWithEntities() {
        assertThat(Upnp.parseDescription(LOCATION, bytes("<root><device/></root>"))).isEmpty();
        assertThat(Upnp.parseDescription(LOCATION, bytes("not xml"))).isEmpty();
        String withEntity = """
                <?xml version="1.0"?>
                <!DOCTYPE root [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <root><URLBase>&secret;</URLBase></root>
                """;
        assertThat(Upnp.parseDescription(LOCATION, bytes(withEntity))).isEmpty();
    }

    @Test
    void readsAnswersAndErrorCodes() {
        String answer = """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                <u:GetExternalIPAddressResponse xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
                <NewExternalIPAddress>73.14.92.181</NewExternalIPAddress>
                </u:GetExternalIPAddressResponse></s:Body></s:Envelope>
                """;
        assertThat(Upnp.parseResponse(bytes(answer))).containsEntry("NewExternalIPAddress", "73.14.92.181");

        String fault = """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
                <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
                <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                <errorCode>718</errorCode><errorDescription>ConflictInMappingEntry</errorDescription>
                </UPnPError></detail></s:Fault></s:Body></s:Envelope>
                """;
        Map<String, String> values = Upnp.parseResponse(bytes(fault));
        assertThat(values).containsEntry("errorCode", "718").containsEntry("errorDescription", "ConflictInMappingEntry");
    }

    @Test
    void requestsNameTheActionAndEscapeValues() {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("NewExternalPort", "4781");
        arguments.put("NewPortMappingDescription", "JM CRM <sync> & more");

        String envelope = Upnp.envelope("urn:schemas-upnp-org:service:WANIPConnection:1", "AddPortMapping", arguments);

        assertThat(envelope)
                .contains("<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">")
                .contains("<NewExternalPort>4781</NewExternalPort>")
                .contains("JM CRM &lt;sync&gt; &amp; more")
                .endsWith("</u:AddPortMapping></s:Body></s:Envelope>");
    }

    @Test
    void onlyAddressesOtherHomesCanReachCountAsPublic() {
        assertThat(Upnp.isPublic("73.14.92.181")).isTrue();
        assertThat(Upnp.isPublic("8.8.8.8")).isTrue();
        assertThat(Upnp.isPublic("192.168.1.20")).isFalse();
        assertThat(Upnp.isPublic("10.0.0.5")).isFalse();
        assertThat(Upnp.isPublic("172.16.4.1")).isFalse();
        assertThat(Upnp.isPublic("100.72.1.9")).as("shared between an internet provider's customers").isFalse();
        assertThat(Upnp.isPublic("127.0.0.1")).isFalse();
        assertThat(Upnp.isPublic("0.0.0.0")).isFalse();
    }

    @Test
    void readsTheLocationFromADiscoveryAnswer() {
        String answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nlocation: http://192.168.1.1:5000/rootDesc.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        assertThat(Upnp.header(answer, "LOCATION")).isEqualTo("http://192.168.1.1:5000/rootDesc.xml");
        assertThat(Upnp.header(answer, "missing")).isNull();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
