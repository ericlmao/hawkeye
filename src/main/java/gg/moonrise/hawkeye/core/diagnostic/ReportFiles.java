package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.entity.diagnostic.PendingCount;
import gg.moonrise.hawkeye.entity.diagnostic.PendingReport;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * The error and crash files waiting for review, and marking them reviewed: a file counts as reviewed once it is in
 * the {@code reviewed} folder under {@code errors/} or {@code crash/}. File I/O: call it off the server threads.
 */
public final class ReportFiles {

    /** The folder under {@code errors/} and {@code crash/} that reviewed files are moved to. */
    public static final String REVIEWED = "reviewed";

    private static final Pattern ENTRY = Pattern.compile("^#\\d+ [A-Z]+ at .*");

    private final @NotNull Path dataFolder;

    /**
     * The files of one data folder.
     *
     * @param dataFolder Hawkeye's data folder
     */
    public ReportFiles(@NotNull Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    /**
     * Every file waiting for review, crashes first, then oldest first.
     *
     * @return the files
     * @throws IOException when a folder or file cannot be read
     */
    public @NotNull List<PendingReport> pending() throws IOException {
        List<PendingReport> reports = new ArrayList<>();
        for (ProblemKind kind : List.of(ProblemKind.CRASH, ProblemKind.ERROR)) {
            for (Path file : files(kind)) {
                reports.add(read(kind, file));
            }
        }
        return List.copyOf(reports);
    }

    /**
     * How many files wait for review, from the folder listings alone: unlike {@link #pending()} no file is read.
     *
     * @return the waiting crash and error files
     * @throws IOException when a folder cannot be listed
     */
    public @NotNull PendingCount waiting() throws IOException {
        return new PendingCount(files(ProblemKind.CRASH).size(), files(ProblemKind.ERROR).size());
    }

    /**
     * Moves every waiting file into its folder's {@code reviewed} folder. A name already taken there gets a
     * number ({@code Error-2026-11-25-14-03-2.txt}). Run it on the error log's thread
     * ({@link ErrorLog#markReviewed}), so repeats of a reviewed problem follow its file.
     *
     * @return every file moved, and where to
     * @throws IOException when a file cannot be moved (the files moved before it stay moved)
     */
    public @NotNull List<Moved> markReviewed() throws IOException {
        List<Moved> moved = new ArrayList<>();
        for (ProblemKind kind : ProblemKind.values()) {
            List<Path> files = files(kind);
            if (files.isEmpty()) {
                continue;
            }
            Path reviewed = Files.createDirectories(folder(kind).resolve(REVIEWED));
            for (Path file : files) {
                Path target = free(reviewed, file.getFileName().toString());
                Files.move(file, target);
                moved.add(new Moved(file, target));
            }
        }
        return List.copyOf(moved);
    }

    /**
     * A file marked reviewed.
     *
     * @param from where it was waiting
     * @param to   where it is now
     */
    public record Moved(@NotNull Path from, @NotNull Path to) {
    }

    private @NotNull Path folder(@NotNull ProblemKind kind) {
        return dataFolder.resolve(kind.folder());
    }

    /** A kind's waiting files by name, which is oldest first (the name holds the time). */
    private @NotNull List<Path> files(@NotNull ProblemKind kind) throws IOException {
        Path folder = folder(kind);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        String prefix = kind.filePrefix() + "-";
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".txt");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static @NotNull PendingReport read(@NotNull ProblemKind kind, @NotNull Path file) throws IOException {
        int problems = 0;
        String first = "";
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            boolean inEntry = false;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (ENTRY.matcher(line).matches()) {
                    problems++;
                    inEntry = problems == 1;
                } else if (inEntry && !line.startsWith("Logger: ") && !line.startsWith("Thread: ")) {
                    first = line.strip();
                    inEntry = false;
                }
            }
        }
        return new PendingReport(kind, file.getFileName().toString(), Files.getLastModifiedTime(file).toMillis(),
                Files.size(file), problems, first);
    }

    private static @NotNull Path free(@NotNull Path folder, @NotNull String name) {
        Path target = folder.resolve(name);
        String stem = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        for (int number = 2; Files.exists(target); number++) {
            target = folder.resolve(stem + "-" + number + ".txt");
        }
        return target;
    }
}
