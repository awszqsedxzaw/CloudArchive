package awszqsedxzaw.cloudarchive.codec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

// Compile a read-only source snapshot, then alternate isolated, equally sized JVMs.
public final class CodecComparisonBenchmark {
    private CodecComparisonBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Expected: <baseline-source-root> <input> <output-directory> <baseline.litematic> <runs>");
        }
        Path baseline = Path.of(args[0]).toRealPath();
        Path input = Path.of(args[1]).toRealPath();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path sizeBaseline = Path.of(args[3]).toRealPath();
        int runs = Integer.parseInt(args[4]);
        if (runs < 1) {
            throw new IllegalArgumentException("Runs must be positive");
        }
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Path classes = Files.createDirectory(output.resolve("baseline-classes"));
        compile(baseline, classes);
        String currentClasspath = System.getProperty("java.class.path");
        String previousClasspath = classes + File.pathSeparator + currentClasspath;
        for (int round = 1; round <= runs; round++) {
            for (String variant : round % 2 == 1
                    ? List.of("baseline", "current") : List.of("current", "baseline")) {
                System.out.printf("COMPARISON round=%d variant=%s%n", round, variant);
                List<String> command = new ArrayList<>(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx128m", "-cp", variant.equals("baseline") ? previousClasspath : currentClasspath,
                        CodecBenchmark.class.getName(), input.toString(),
                        output.resolve("round-" + round + "-" + variant).toString(),
                        sizeBaseline.toString(), "1"));
                int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
                if (exit != 0) {
                    throw new IOException("Benchmark JVM failed: " + variant + ", exit=" + exit);
                }
            }
        }
    }

    private static void compile(Path baseline, Path classes) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK is required to compile the source snapshot");
        }
        List<File> sources;
        try (var paths = Files.list(baseline.resolve("src/main/java/awszqsedxzaw/cloudarchive/codec"))) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().map(Path::toFile).toList();
        }
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromFiles(sources);
            List<String> options = List.of("--release", "17", "-proc:none", "-encoding", "UTF-8",
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString());
            if (!compiler.getTask(null, manager, null, options, null, units).call()) {
                throw new IOException("Baseline codec compilation failed");
            }
        }
    }
}
