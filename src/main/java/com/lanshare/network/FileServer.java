package com.lanshare.network;

import com.lanshare.model.Transfer;
import com.lanshare.storage.TransferRepository;
import com.lanshare.util.FileValidator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Accepts one framed binary file per TCP connection. */
public class FileServer implements AutoCloseable {
    private final int port;
    private final Path directory;
    private final TransferRepository history;
    private final Consumer<String> events;
    private final BiConsumer<Long, Long> transferProgress;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger clientCount = new AtomicInteger();
    private volatile boolean running;
    private ServerSocket server;

    public FileServer(int port, Path directory, TransferRepository history, Consumer<String> events) {
        this(port, directory, history, events, (received, total) -> { });
    }

    public FileServer(int port, Path directory, TransferRepository history,
                      Consumer<String> events, BiConsumer<Long, Long> transferProgress) {
        this.port = port;
        this.directory = directory;
        this.history = history;
        this.events = events;
        this.transferProgress = transferProgress;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        Files.createDirectories(directory);
        server = new ServerSocket(port);
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "sharelan-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        events.accept("Receiver started on TCP port " + server.getLocalPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                clients.add(socket);
                clientCount.incrementAndGet();
                events.accept("Peer connected: " + socket.getInetAddress().getHostAddress());
                workers.submit(() -> receive(socket));
            } catch (SocketException ex) {
                if (running) events.accept("Receiver socket error: " + ex.getMessage());
            } catch (IOException ex) {
                events.accept("Could not accept peer connection: " + ex.getMessage());
            }
        }
    }

    private void receive(Socket socket) {
        Path partialFile = null;
        try (socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            String filename = input.readUTF();
            long fileSize = input.readLong();
            if (!FileValidator.validFilename(filename) || fileSize < 0) {
                output.writeUTF("REJECTED: invalid filename or size");
                output.flush();
                return;
            }

            long freeSpace = Files.getFileStore(directory).getUsableSpace();
            if (fileSize > freeSpace) {
                output.writeUTF("REJECTED: not enough free disk space");
                output.flush();
                return;
            }

            output.writeUTF("READY");
            output.flush();
            transferProgress.accept(0L, fileSize);

            Path destination = uniqueDestination(filename);
            partialFile = Files.createTempFile(directory, ".incoming-", ".part");
            long received = 0;
            try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(partialFile))) {
                byte[] buffer = new byte[64 * 1024];
                while (received < fileSize) {
                    int wanted = (int) Math.min(buffer.length, fileSize - received);
                    int bytesRead = input.read(buffer, 0, wanted);
                    if (bytesRead < 0) throw new EOFException("Peer disconnected before the file completed");
                    fileOutput.write(buffer, 0, bytesRead);
                    received += bytesRead;
                    transferProgress.accept(received, fileSize);
                }
            }

            Files.move(partialFile, destination);
            partialFile = null;
            history.add(Transfer.received(filenameOf(destination), socket.getInetAddress().getHostAddress(), fileSize));
            output.writeUTF("OK");
            output.flush();
            events.accept("Received " + destination.getFileName() + " (" + fileSize + " bytes)");
        } catch (Exception ex) {
            events.accept("Transfer failed: " + ex.getMessage());
            try {
                if (!socket.isClosed()) {
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    output.writeUTF("FAILED: " + ex.getMessage());
                    output.flush();
                }
            } catch (IOException ignored) {
                // The peer may already have disconnected.
            }
        } finally {
            if (partialFile != null) {
                try {
                    Files.deleteIfExists(partialFile);
                } catch (IOException ex) {
                    events.accept("Could not remove partial file: " + ex.getMessage());
                }
            }
            if (clients.remove(socket)) clientCount.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private String filenameOf(Path path) {
        return path.getFileName().toString();
    }

    private Path uniqueDestination(String filename) {
        Path destination = directory.resolve(filename);
        if (!Files.exists(destination)) return destination;

        String stem = filename;
        String extension = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            stem = filename.substring(0, dot);
            extension = filename.substring(dot);
        }
        for (int number = 1; ; number++) {
            destination = directory.resolve(stem + " (" + number + ")" + extension);
            if (!Files.exists(destination)) return destination;
        }
    }

    public int connectedClients() { return clientCount.get(); }
    public boolean isRunning() { return running; }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ex) {
                events.accept("Could not close receiver socket: " + ex.getMessage());
            }
        }
        for (Socket socket : clients) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket is already closed.
            }
        }
        clients.clear();
        clientCount.set(0);
        workers.shutdownNow();
        events.accept("Receiver stopped");
    }

    @Override public void close() { stop(); }
}
