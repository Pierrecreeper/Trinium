// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Real filesystem regression tests; no Minecraft process or GPU required. */
public final class CacheDirectoryBudgetTest {
    private static int checks;
    private static final AtomicLong CLOCK = new AtomicLong(1);

    private static CacheDirectoryBudget budget(long size) {
        return new CacheDirectoryBudget(size, "*.spv-cache", 4, 32, 4096, 1000, CLOCK::get);
    }
    private static Path entry(Path directory, String name) { return directory.resolve(name + ".spv-cache"); }
    private static boolean write(CacheDirectoryBudget budget, Path file, int size) throws IOException {
        return budget.write(file, size, () -> Files.write(file, new byte[size]));
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    public static void main(String[] arguments) throws Exception {
        Path root = Files.createTempDirectory("trinium-budget-test-");
        try {
            Path replace = root.resolve("replace");
            var budget = budget(100);
            check(write(budget, entry(replace, "a"), 60), "cold write");
            check(write(budget, entry(replace, "a"), 70), "replacement subtracts old length");
            check(Files.size(entry(replace, "a")) == 70, "replacement committed");
            AtomicInteger calls = new AtomicInteger();
            check(!budget.write(entry(replace, "b"), 40, calls::incrementAndGet), "reject over budget");
            check(calls.get() == 0 && !Files.exists(entry(replace, "b")), "rejected writer never called");
            check(write(budget, entry(replace, "a"), 30), "shrinking replacement");
            check(write(budget, entry(replace, "b"), 70), "exact full capacity");
            check(write(budget, entry(replace, "a"), 30), "replacement remains possible at full capacity");
            check(!write(budget, entry(replace, "huge"), 101), "single oversized entry rejected");

            Path external = root.resolve("external");
            var externalBudget = budget(100);
            check(write(externalBudget, entry(external, "ours"), 10), "external fixture initialized");
            Files.write(entry(external, "outside"), new byte[90]);
            check(!write(externalBudget, entry(external, "next"), 1), "external addition counted");
            Files.delete(entry(external, "outside"));
            check(write(externalBudget, entry(external, "next"), 90), "external deletion frees capacity");
            Files.write(entry(external, "ours"), new byte[50]);
            check(!write(externalBudget, entry(external, "ours"), 40), "changed replacement size triggers rescan");

            Path hidden = root.resolve("in-place");
            var hiddenBudget = new CacheDirectoryBudget(1000, "*.spv-cache", 4, 2, 4096, 1000, CLOCK::get);
            check(write(hiddenBudget, entry(hidden, "a"), 10), "bounded fixture initialized");
            check(write(hiddenBudget, entry(hidden, "b"), 10), "second write before reconciliation");
            FileTime stamp = Files.getLastModifiedTime(hidden);
            Files.write(entry(hidden, "a"), new byte[995]);
            Files.setLastModifiedTime(hidden, stamp);
            check(!write(hiddenBudget, entry(hidden, "c"), 10), "write-count bound catches in-place growth with unchanged directory timestamp");
            Files.write(entry(hidden, "a"), new byte[5]);
            CLOCK.addAndGet(1001);
            check(write(hiddenBudget, entry(hidden, "c"), 10), "time bound catches in-place shrink after rejected writes");

            Path nearFull = root.resolve("near-full");
            var nearBudget = budget(1000);
            check(write(nearBudget, entry(nearFull, "a"), 500), "near-budget fixture initialized");
            FileTime nearStamp = Files.getLastModifiedTime(nearFull);
            Files.write(entry(nearFull, "a"), new byte[900]);
            Files.setLastModifiedTime(nearFull, nearStamp);
            check(!write(nearBudget, entry(nearFull, "b"), 450), "near-budget reconciliation catches hidden growth immediately");

            Path byteBound = root.resolve("bytes");
            var bytesBudget = new CacheDirectoryBudget(10000, "*.spv-cache", 4, 100, 50, 1000, CLOCK::get);
            check(write(bytesBudget, entry(byteBound, "a"), 30), "byte fixture initialized");
            long scans = bytesBudget.scanCount();
            check(write(bytesBudget, entry(byteBound, "b"), 30), "byte-bounded write");
            check(bytesBudget.scanCount() == scans + 1, "byte bound forces reconciliation before crossing threshold");

            Path failed = root.resolve("failure");
            var failureBudget = budget(100);
            check(write(failureBudget, entry(failed, "a"), 20), "failure fixture initialized");
            try {
                failureBudget.write(entry(failed, "bad"), 60, () -> {
                    Files.write(entry(failed, "bad"), new byte[60]);
                    throw new IOException("Intentional failed commit");
                });
                throw new AssertionError("Failed write accepted");
            } catch (IOException expected) { checks++; }
            check(!write(failureBudget, entry(failed, "next"), 30), "failed partial write is re-accounted");
            Files.delete(entry(failed, "bad"));
            check(write(failureBudget, entry(failed, "next"), 80), "recovery after failed write");
            try {
                failureBudget.write(entry(failed, "a"), 5, () -> Files.write(entry(failed, "a"), new byte[4]));
                throw new AssertionError("Unexpected output length accepted");
            } catch (IOException expected) { checks++; }
            check(write(failureBudget, entry(failed, "a"), 20), "recovery after unexpected output length");

            Path replacedDirectory = root.resolve("recreated");
            var directoryBudget = budget(100);
            check(write(directoryBudget, entry(replacedDirectory, "a"), 40), "directory fixture initialized");
            deleteTree(replacedDirectory);
            Files.createDirectories(replacedDirectory);
            Files.write(entry(replacedDirectory, "foreign"), new byte[100]);
            check(!write(directoryBudget, entry(replacedDirectory, "b"), 1), "replacement directory re-accounted");
            var lru = budget(1000);
            for (int i = 0; i < 8; i++) check(write(lru, entry(root.resolve("lru-" + i), "a"), 20), "LRU write");
            check(lru.trackedDirectories() == 4, "directory LRU bounded");
            check(write(lru, entry(root.resolve("lru-0"), "b"), 20), "evicted directory can be revisited");

            Path parallel = root.resolve("parallel");
            var parallelBudget = budget(1000);
            try (var executor = Executors.newFixedThreadPool(4)) {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    Path file = entry(parallel, "entry-" + i);
                    tasks.add(() -> write(parallelBudget, file, 40));
                }
                int accepted = 0;
                for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
                check(accepted == 25, "concurrent writers obey exact budget");
                try (var files = Files.list(parallel)) {
                    long size = 0;
                    for (Path file : files.toList()) size += Files.size(file);
                    check(size == 1000, "concurrent final directory size");
                }
            }
            System.out.println("Cache directory budget: " + checks + " checks passed.");
            if (arguments.length > 0 && arguments[0].equals("--benchmark")) {
                benchmark(root, 400);
                benchmark(root, 800);
            }
        } finally { deleteTree(root); }
    }

    private static void benchmark(Path root, int count) throws IOException {
        final int size = 4096;
        Path baseline = root.resolve("baseline-" + count);
        Files.createDirectories(baseline);
        byte[] payload = new byte[size];
        long baselineEntries = 0;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long used = 0;
            try (var entries = Files.newDirectoryStream(baseline, "*.spv-cache")) {
                for (Path file : entries) { used += Files.size(file); baselineEntries++; }
            }
            if (used + size > 128L * 1024 * 1024) throw new AssertionError();
            Files.write(entry(baseline, "shader-" + i), payload);
        }
        long baselineTime = System.nanoTime() - start;
        Path accounted = root.resolve("accounted-" + count);
        var budget = new CacheDirectoryBudget(128L * 1024 * 1024, "*.spv-cache");
        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            Path file = entry(accounted, "shader-" + i);
            if (!budget.write(file, size, () -> Files.write(file, payload))) throw new AssertionError();
        }
        long accountedTime = System.nanoTime() - start;
        System.out.printf(java.util.Locale.ROOT,
                "%d x 4KiB filesystem writes: old=%.3f ms (%d entry stats), accounted=%.3f ms (%d scans/%d entry stats). Cache-write microbenchmark only.%n",
                count, baselineTime / 1e6, baselineEntries, accountedTime / 1e6,
                budget.scanCount(), budget.entriesScanned());
    }
}
