// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Serializes cache writes and amortizes directory accounting across a compilation burst.
 * This accounts disk space only: callers must still validate cached content independently.
 * External directory changes invalidate the account immediately; in-place file changes are
 * reconciled within 32 writes, 4 MiB of new writes, or one second, and before a near-full write.
 * A single entry larger than 4 MiB is checked on its own. Arbitrary external writers can
 * exceed a disk budget themselves; this bounds the interval before this writer notices.
 */
final class CacheDirectoryBudget {
    private static final int MAX_INDEXED_FILES = 4096;
    private final long budget;
    private final String glob;
    private final int maxDirectories;
    private final int maxWrites;
    private final long maxBytesBetweenScans;
    private final long maxScanAgeNanos;
    private final LongSupplier nanoTime;
    private final LinkedHashMap<Path, Account> accounts = new LinkedHashMap<>(16, 0.75f, true);
    private long scanCount;
    private long entriesScanned;

    @FunctionalInterface interface WriteOperation { void write() throws IOException; }

    CacheDirectoryBudget(long budget, String glob) {
        this(budget, glob, 16, 32, 4L * 1024 * 1024, 1_000_000_000L, System::nanoTime);
    }

    CacheDirectoryBudget(long budget, String glob, int maxDirectories, int maxWrites,
                         long maxBytesBetweenScans, long maxScanAgeNanos, LongSupplier nanoTime) {
        if (budget <= 0 || maxDirectories <= 0 || maxWrites <= 0 || maxBytesBetweenScans <= 0
                || maxScanAgeNanos <= 0) throw new IllegalArgumentException("Invalid cache budget");
        this.budget = budget;
        this.glob = Objects.requireNonNull(glob);
        this.maxDirectories = maxDirectories;
        this.maxWrites = maxWrites;
        this.maxBytesBetweenScans = maxBytesBetweenScans;
        this.maxScanAgeNanos = maxScanAgeNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    synchronized boolean write(Path file, long newSize, WriteOperation operation) throws IOException {
        Objects.requireNonNull(operation);
        if (newSize < 0) throw new IllegalArgumentException("Negative cache entry size");
        if (newSize > budget) return false;
        Path normalized = file.toAbsolutePath().normalize();
        Path directory = normalized.getParent();
        Files.createDirectories(directory);
        String name = normalized.getFileName().toString();
        long now = nanoTime.getAsLong();
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class);
        Account account = accounts.get(directory);
        long existing = sizeOrZero(normalized);
        if (account == null || !account.completeIndex || account.writes >= maxWrites
                || account.bytesWritten > maxBytesBetweenScans - Math.min(newSize, maxBytesBetweenScans)
                || now - account.scannedAt >= maxScanAgeNanos
                || !Objects.equals(attributes.fileKey(), account.fileKey)
                || !attributes.lastModifiedTime().equals(account.modified)
                || account.sizes.getOrDefault(name, 0L) != existing) {
            account = scan(directory, now);
        }
        long previous = account.sizes.getOrDefault(name, 0L);
        if (!account.completeIndex) previous = sizeOrZero(normalized);
        long projected = projectedSize(account.used, previous, newSize);
        long headroom = Math.min(maxBytesBetweenScans, budget / 16);
        if (projected > budget - headroom && account.writes > 0) {
            // External in-place growth does not necessarily change directory timestamps.
            account = scan(directory, now);
            previous = account.completeIndex ? account.sizes.getOrDefault(name, 0L) : sizeOrZero(normalized);
            projected = projectedSize(account.used, previous, newSize);
        }
        if (projected > budget) return false;
        try {
            operation.write();
            if (Files.size(normalized) != newSize) throw new IOException("Unexpected cache entry length");
            account.used = projected;
            account.writes++;
            account.bytesWritten = saturatingAdd(account.bytesWritten, newSize);
            if (account.sizes.size() < MAX_INDEXED_FILES || account.sizes.containsKey(name)) {
                account.sizes.put(name, newSize);
            } else {
                account.completeIndex = false;
            }
            BasicFileAttributes after = Files.readAttributes(directory, BasicFileAttributes.class);
            if (!Objects.equals(account.fileKey, after.fileKey())) accounts.remove(directory);
            else account.modified = after.lastModifiedTime();
            return true;
        } catch (IOException | RuntimeException | Error failure) {
            // A partial write, failed move, or external deletion must not poison later accounting.
            accounts.remove(directory);
            throw failure;
        }
    }

    private Account scan(Path directory, long now) throws IOException {
        Account account = new Account();
        account.scannedAt = now;
        scanCount++;
        try (var entries = Files.newDirectoryStream(directory, glob)) {
            for (Path entry : entries) {
                long size;
                try { size = Files.size(entry); }
                catch (NoSuchFileException deletedConcurrently) { continue; }
                entriesScanned++;
                account.used = saturatingAdd(account.used, size);
                if (account.sizes.size() < MAX_INDEXED_FILES) account.sizes.put(entry.getFileName().toString(), size);
                else account.completeIndex = false;
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class);
        account.modified = attributes.lastModifiedTime();
        account.fileKey = attributes.fileKey();
        accounts.put(directory, account);
        while (accounts.size() > maxDirectories) {
            Iterator<Path> oldest = accounts.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return account;
    }

    private static long sizeOrZero(Path path) throws IOException {
        try { return Files.size(path); }
        catch (NoSuchFileException absent) { return 0; }
    }

    private static long projectedSize(long used, long previous, long next) {
        return saturatingAdd(Math.max(0, used - previous), next);
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    synchronized long scanCount() { return scanCount; }
    synchronized long entriesScanned() { return entriesScanned; }
    synchronized int trackedDirectories() { return accounts.size(); }

    private static final class Account {
        private final Map<String, Long> sizes = new HashMap<>();
        private long used;
        private int writes;
        private long bytesWritten;
        private long scannedAt;
        private FileTime modified;
        private Object fileKey;
        private boolean completeIndex = true;
    }
}
