package com.campusshare.discovery;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {
    @Test void discoversAndTimesOutWithoutAResponse() throws Exception {
        try (var server = new Discovery(0)) {
            assertTrue(Discovery.search(server.port(), "127.0.0.1").isEmpty());
            server.start(443);
            assertEquals(List.of(new Discovery.Server("127.0.0.1", 443)),
                    Discovery.search(server.port(), "127.0.0.1"));
        }
    }

    @Test void rejectsMalformedRepliesAndWrongSourcePort() throws Exception {
        byte[] data = "CAMPUSSHARE_SERVER_V1 65536".getBytes(StandardCharsets.US_ASCII);
        var packet = new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), 42000);
        assertNull(Discovery.parse(packet, 42000));
        data = "CAMPUSSHARE_SERVER_V1 42001".getBytes(StandardCharsets.US_ASCII);
        packet.setData(data);
        assertNull(Discovery.parse(packet, 42001));
        assertEquals(42001, Discovery.parse(packet, 42000).port());
    }
}
