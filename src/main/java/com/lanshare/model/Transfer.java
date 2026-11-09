package com.lanshare.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable record for a completed sent or received file transfer. */
public record Transfer(
        String id,
        String filename,
        String sender,
        long size,
        Instant dateTime,
        String status) {

    public Transfer {
        if (id == null || filename == null || sender == null || dateTime == null || status == null || size < 0) {
            throw new IllegalArgumentException("Invalid transfer record");
        }
    }

    public static Transfer received(String filename, String sender, long size) {
        return new Transfer(UUID.randomUUID().toString(), filename, sender, size, Instant.now(), "SUCCESS");
    }

    public static Transfer sent(String filename, String receiver, long size) {
        return new Transfer(UUID.randomUUID().toString(), filename, receiver, size, Instant.now(), "SENT");
    }
}
