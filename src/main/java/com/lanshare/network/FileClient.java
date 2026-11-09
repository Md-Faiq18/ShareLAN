package com.lanshare.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongConsumer;

/** Opens a peer connection and streams one file using bounded memory. */
public final class FileClient {
    private FileClient() { }

    public static Connection connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(30_000);
            return new Connection(socket);
        } catch (IOException ex) {
            try { socket.close(); }
            catch (IOException closeError) { ex.addSuppressed(closeError); }
            throw ex;
        }
    }

    public static void send(String host, int port, Path file, LongConsumer progress) throws IOException {
        try (Connection connection = connect(host, port)) {
            connection.send(file, progress);
        }
    }

    /** One connection carries one file; the server closes it after replying OK. */
    public static final class Connection implements AutoCloseable {
        private final Socket socket;
        private final DataOutputStream output;
        private final DataInputStream input;

        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        }

        public synchronized void send(Path file, LongConsumer progress) throws IOException {
            long fileSize = Files.size(file);
            String filename = file.getFileName().toString();

            try (InputStream fileInput = new BufferedInputStream(Files.newInputStream(file))) {
                output.writeUTF(filename);
                output.writeLong(fileSize);
                output.flush();

                String response = input.readUTF();
                if (!response.equals("READY")) throw new IOException(response);

                byte[] buffer = new byte[64 * 1024];
                long sent = 0;
                int bytesRead;
                while ((bytesRead = fileInput.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    sent += bytesRead;
                    progress.accept(sent);
                }
                output.flush();

                response = input.readUTF();
                if (!response.equals("OK")) throw new IOException(response);
            }
        }

        public boolean isOpen() {
            return socket.isConnected() && !socket.isClosed();
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }
}
