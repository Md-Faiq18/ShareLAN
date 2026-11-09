package com.lanshare.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Answers UDP discovery requests and searches for active ShareLAN peers. */
public final class LanDiscovery implements AutoCloseable {
    public static final int DISCOVERY_PORT = 5051;
    private static final String REQUEST = "SHARELAN_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "SHARELAN_RECEIVER_V1|";

    private final int transferPort;
    private final AtomicBoolean running = new AtomicBoolean();
    private DatagramSocket listener;

    public record Device(String name, String address, int port) {
        @Override public String toString() {
            return name + "   " + address + ":" + port;
        }
    }

    public LanDiscovery(int transferPort) {
        this.transferPort = transferPort;
    }

    /** Starts answering requests while the local peer receiver is running. */
    public synchronized void start() throws SocketException {
        if (running.get()) return;

        listener = new DatagramSocket(null);
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(DISCOVERY_PORT));
        running.set(true);

        Thread responder = new Thread(this::answerRequests, "sharelan-discovery");
        responder.setDaemon(true);
        responder.start();
    }

    private void answerRequests() {
        byte[] buffer = new byte[128];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                listener.receive(packet);
                String message = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.UTF_8);
                if (!REQUEST.equals(message)) continue;

                byte[] reply = (RESPONSE_PREFIX + transferPort).getBytes(StandardCharsets.UTF_8);
                listener.send(new DatagramPacket(reply, reply.length,
                        packet.getAddress(), packet.getPort()));
            } catch (IOException ex) {
                if (running.get()) System.err.println("LAN discovery failed: " + ex.getMessage());
            }
        }
    }

    /** Broadcasts a request and collects replies for a short discovery window. */
    public static List<Device> findReceivers() throws IOException {
        Map<String, Device> found = new LinkedHashMap<>();
        byte[] query = REQUEST.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1_800);
            sendQuery(socket, query, InetAddress.getByName("255.255.255.255"));
            for (InetAddress broadcast : interfaceBroadcasts()) {
                try {
                    sendQuery(socket, query, broadcast);
                } catch (IOException ignored) {
                    // A network interface may not support broadcast.
                }
            }

            long deadline = System.currentTimeMillis() + 1_800;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] buffer = new byte[256];
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    addIfValid(found, reply);
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void sendQuery(DatagramSocket socket, byte[] query, InetAddress address)
            throws IOException {
        socket.send(new DatagramPacket(query, query.length, address, DISCOVERY_PORT));
    }

    private static void addIfValid(Map<String, Device> found, DatagramPacket packet) {
        String message = new String(packet.getData(), packet.getOffset(),
                packet.getLength(), StandardCharsets.UTF_8);
        if (!message.startsWith(RESPONSE_PREFIX)) return;

        try {
            int port = Integer.parseInt(message.substring(RESPONSE_PREFIX.length()));
            if (port < 1 || port > 65_535) return;
            String address = packet.getAddress().getHostAddress();
            found.putIfAbsent(address + ":" + port, new Device("ShareLAN peer", address, port));
        } catch (NumberFormatException ignored) {
            // Ignore malformed discovery replies.
        }
    }

    private static List<InetAddress> interfaceBroadcasts() throws SocketException {
        List<InetAddress> broadcasts = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;

            network.getInterfaceAddresses().forEach(address -> {
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null) {
                    broadcasts.add(address.getBroadcast());
                }
            });
        }
        return broadcasts;
    }

    @Override public synchronized void close() {
        running.set(false);
        if (listener != null) listener.close();
        listener = null;
    }
}
