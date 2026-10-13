package awszqsedxzaw.cloudarchive.codec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class CodecBenchmark {
    private CodecBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Expected: <input> <output-directory> <baseline.litematic> [runs]");
        }
        Path input = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        long baseline = Files.size(Path.of(args[2]));
        int runs = args.length == 4 ? Integer.parseInt(args[3]) : 3;
        if (runs < 1) {
            throw new IllegalArgumentException("Runs must be positive");
        }
        Path ancestor = output;
        while (!Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        Path resolvedOutput = ancestor.toRealPath().resolve(ancestor.relativize(output));
        if (Files.isDirectory(input) && resolvedOutput.startsWith(input)) {
            throw new IllegalArgumentException("Benchmark output must be outside the source root");
        }
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Path warm = output.resolve("warmup");
        Files.write(warm, FileCodecSelfTest.randomBytes(1024 * 1024, 3));
        LitematicaFileCodec.pack(warm, output.resolve("warmup.litematic"));
        LitematicaFileCodec.restore(output.resolve("warmup.litematic"), output.resolve("warmup.restored"));
        for (int run = 1; run <= runs; run++) {
            Path archive = output.resolve("run-" + run + ".litematic");
            Path restored = output.resolve("restored-" + run);
            long start = System.nanoTime();
            LitematicaFileCodec.ArchiveInfo info = Files.isDirectory(input)
                    ? FileTreeCodec.pack(input, archive).archive() : LitematicaFileCodec.pack(input, archive);
            double packSeconds = (System.nanoTime() - start) / 1e9;
            start = System.nanoTime();
            FileTreeCodec.restore(archive, restored);
            double restoreSeconds = (System.nanoTime() - start) / 1e9;
            compare(input, restored);
            System.out.printf(Locale.ROOT,
                    "BENCHMARK input=%s run=%d bytes=%d baseline=%d pack_s=%.3f restore_s=%.3f barrels=%d unique=%d stored=%d verified=true%n",
                    input.getFileName(), run, Files.size(archive), baseline, packSeconds, restoreSeconds,
                    info.barrelCount(), info.dataSize(), info.storedSize());
        }
    }

    private static void compare(Path source, Path restored) throws Exception {
        if (Files.isRegularFile(source)) {
            FileCodecSelfTest.require(Files.mismatch(source, restored) == -1, "Benchmark bytes differ");
            return;
        }
        List<Path> before, after;
        try (Stream<Path> paths = Files.walk(source)) {
            before = paths.map(source::relativize).sorted().toList();
        }
        try (Stream<Path> paths = Files.walk(restored)) {
            after = paths.map(restored::relativize).sorted().toList();
        }
        FileCodecSelfTest.require(before.equals(after), "Benchmark tree differs");
        for (Path relative : before) {
            Path original = source.resolve(relative), result = restored.resolve(relative);
            FileCodecSelfTest.require(Files.isDirectory(original) == Files.isDirectory(result), "Node type differs");
            if (Files.isRegularFile(original)) {
                FileCodecSelfTest.require(Files.mismatch(original, result) == -1, "File differs: " + relative);
            }
        }
    }
}
