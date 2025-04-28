package org.sks.portsmanagement.client;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import jakarta.annotation.PostConstruct;
import org.sks.portsmanagement.service.MavlinkMessageHandlerService;
import org.sks.portsmanagement.utils.ZeroTierIPProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class MavlinkClient {

    // Configuration for 9 ships, each with 5 ports (14550-14649)
    private static final int PORTS_PER_SHIP = 5;
    private static final int BASE_PORT = 15000;
    private static final int TOTAL_SHIPS = 9;

    private final List<Integer> udpPorts = generatePortList();
    private final ExecutorService executorService = Executors.newFixedThreadPool(udpPorts.size() * 2);

    // Map of allowed IPs to their port ranges (startPort to endPort inclusive)
    private final Map<String, PortRange> allowedShips = new ConcurrentHashMap<>();

    private final ZeroTierIPProvider zeroTierIPProvider;
    private final MavlinkMessageHandlerService messageHandlerService;

    public MavlinkClient(ZeroTierIPProvider zeroTierIPProvider,
                         MavlinkMessageHandlerService messageHandlerService) {
        this.zeroTierIPProvider = zeroTierIPProvider;
        this.messageHandlerService = messageHandlerService;
    }

    // Generate ports from 14550 to 14550 + (100 * 9) - 1
    private List<Integer> generatePortList() {
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < PORTS_PER_SHIP * TOTAL_SHIPS; i++) {
            ports.add(BASE_PORT + i);
        }
        return ports;
    }

    @PostConstruct
    public void init() {
        setupAllowedShips();
        startListening();
    }

    private void setupAllowedShips() {
        // Example configuration - replace with your actual ship IPs
        // Ship 1: 192.168.1.23 (ports 14550-14649)
        allowedShips.put("192.168.1.23", new PortRange(BASE_PORT, BASE_PORT + PORTS_PER_SHIP - 1));

        // Ship 2: 192.168.1.24 (ports 14650-14749)
        allowedShips.put("192.168.1.24", new PortRange(BASE_PORT + PORTS_PER_SHIP, BASE_PORT + 2*PORTS_PER_SHIP - 1));

        // Add remaining ships (3-9) similarly...
        // allowedShips.put("192.168.1.25", new PortRange(...));
        // ...
    }

    private boolean isSenderAllowed(InetAddress senderAddress, int targetPort) {
        String ip = senderAddress.getHostAddress();
        PortRange range = allowedShips.get(ip);

        if (range == null) {
            // IP not in allowed list
            return false;
        }

        // Check if port is within this ship's allowed range
        return targetPort >= range.startPort && targetPort <= range.endPort;
    }

    public void startListening() {
        for (int port : udpPorts) {
            List<InetAddress> bindAddresses = zeroTierIPProvider.getZeroTierIPs();

            if (bindAddresses.isEmpty()) {
                try {
                    bindAddresses.add(InetAddress.getByName("0.0.0.0"));
                } catch (UnknownHostException e) {
                    System.err.printf("❌ Error binding to 0.0.0.0 for port %d: %s%n", port, e.getMessage());
                    continue;
                }
            }

            for (InetAddress address : bindAddresses) {
                executorService.execute(() -> listenOnPort(address, port));
            }
        }
    }

    private void listenOnPort(InetAddress bindAddress, int port) {
        try (DatagramSocket udpSocket = new DatagramSocket(new InetSocketAddress(bindAddress, port))) {
            System.out.printf("✅ Listening for MAVLink messages on IP %s, Port: %d%n", bindAddress, port);
            UdpInputStream udpInputStream = new UdpInputStream(udpSocket);
            MavlinkConnection mavlinkConnection = MavlinkConnection.create(udpInputStream, null);

            while (!Thread.currentThread().isInterrupted()) {
                MavlinkMessage<?> message = mavlinkConnection.next();
                if (message != null) {
                    InetAddress senderAddress = udpInputStream.getSenderAddress();
                    int senderPort = udpInputStream.getSenderPort();

                    if (isSenderAllowed(senderAddress, port)) {
                        messageHandlerService.handleMessage(message, port, udpSocket, senderAddress, senderPort);
                    } else {
                        String errorJson = String.format(
                                "{\"error\": \"unauthorized_connection\", " +
                                        "\"message\": \"Connection rejected\", " +
                                        "\"details\": {" +
                                        "\"sender_ip\": \"%s\", " +
                                        "\"attempted_port\": %d, " +
                                        "\"allowed_ports\": \"%s\"}}",
                                senderAddress.getHostAddress(),
                                port,
                                getAllowedPortsForIP(senderAddress)
                        );
                        System.out.println(errorJson);

                    }
                }
            }
        } catch (Exception e) {
            System.err.printf("❌ Error on port %d, IP %s: %s%n", port, bindAddress, e.getMessage());
        }
    }

    private String getAllowedPortsForIP(InetAddress senderAddress) {
        PortRange range = allowedShips.get(senderAddress.getHostAddress());
        if (range == null) {
            return "none (IP not allowed)";
        }
        return range.startPort + "-" + range.endPort;
    }

    // Helper class to store port ranges
    private static class PortRange {
        final int startPort;
        final int endPort;

        PortRange(int startPort, int endPort) {
            this.startPort = startPort;
            this.endPort = endPort;
        }
    }

    // UdpInputStream remains the same as in original code
    private static class UdpInputStream extends InputStream {
        private final DatagramSocket socket;
        private final byte[] buffer = new byte[4096];
        private int position = 0, length = 0;
        private InetAddress senderAddress;
        private int senderPort;

        public UdpInputStream(DatagramSocket socket) {
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            if (position >= length) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                length = packet.getLength();
                position = 0;
                senderAddress = packet.getAddress();
                senderPort = packet.getPort();
            }
            return buffer[position++] & 0xFF;
        }

        public InetAddress getSenderAddress() {
            return senderAddress;
        }

        public int getSenderPort() {
            return senderPort;
        }
    }
}
