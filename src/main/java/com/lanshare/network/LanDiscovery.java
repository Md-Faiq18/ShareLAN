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

/** Small UDP broadcast discovery service for finding active ShareLAN receivers. */
public final class LanDiscovery implements AutoCloseable {
    public static final int DISCOVERY_PORT = 5051;
    private static final String REQUEST = "SHARELAN_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "SHARELAN_RECEIVER_V1|";

    private final int transferPort;
    private final AtomicBoolean running = new AtomicBoolean();
    private DatagramSocket listener;
    private Thread listenerThread;

    public record Device(String name, String address, int port) {
        @Override public String toString() { return name + "   " + address + ":" + port; }
    }

    public LanDiscovery(int transferPort) { this.transferPort = transferPort; }

    /** Starts answering discovery requests while this computer's file receiver is running. */
    public synchronized void start() throws SocketException {
        if (running.get()) return;
        listener = new DatagramSocket(null);
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(DISCOVERY_PORT));
        running.set(true);
        listenerThread = new Thread(this::answerRequests, "sharelan-discovery");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void answerRequests() {
        byte[] buffer = new byte[128];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                listener.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                if (REQUEST.equals(request)) {
                    byte[] response = (RESPONSE_PREFIX + transferPort).getBytes(StandardCharsets.UTF_8);
                    listener.send(new DatagramPacket(response, response.length, packet.getAddress(), packet.getPort()));
                }
            } catch (IOException ex) {
                if (running.get()) System.err.println("LAN discovery listener failed: " + ex.getMessage());
            }
        }
    }

    /** Broadcasts a query and returns receivers that answer within the short scan window. */
    public static List<Device> findReceivers() throws IOException {
        Map<String, Device> found = new LinkedHashMap<>();
        byte[] query = REQUEST.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1800);
            DatagramPacket request = new DatagramPacket(query, query.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(request);
            for (InetAddress broadcast : interfaceBroadcasts()) {
                try { socket.send(new DatagramPacket(query, query.length, broadcast, DISCOVERY_PORT)); }
                catch (IOException ignored) { /* Another interface may not support broadcast. */ }
            }

            long deadline = System.currentTimeMillis() + 1800;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] buffer = new byte[256];
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    String value = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
                    if (!value.startsWith(RESPONSE_PREFIX)) continue;
                    int port = Integer.parseInt(value.substring(RESPONSE_PREFIX.length()));
                    if (port < 1 || port > 65535) continue;
                    String address = reply.getAddress().getHostAddress();
                    found.putIfAbsent(address + ":" + port,
                            new Device("ShareLAN receiver", address, port));
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                } catch (IllegalArgumentException malformed) {
                    // Ignore malformed packets; discovery is only a convenience on the local network.
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static List<InetAddress> interfaceBroadcasts() throws SocketException {
        List<InetAddress> broadcasts = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            network.getInterfaceAddresses().forEach(address -> {
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
                    broadcasts.add(address.getBroadcast());
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
