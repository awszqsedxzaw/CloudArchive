package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

public final class Base85CodecBenchmark {
    private static final String PACKAGE = "awszqsedxzaw.cloudarchive.codec";
    private static final String EXPERIMENT = "awszqsedxzaw.cloudarchive.benchmarkbase85";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static volatile long sink;

    private Base85CodecBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--compiled")) {
            run(Path.of(args[1]), Path.of(args[2]), Integer.parseInt(args[3]));
            return;
        }
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Expected: <input> <new-output-directory> [runs]");
        }
        Path input = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        int runs = args.length == 3 ? Integer.parseInt(args[2]) : 3;
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
        verifyBase85();
        Path classes = Files.createDirectory(output.resolve("experimental-classes"));
        compileExperiment(classes);
        // Keep javac's retained classes and caches out of the measured 128 MiB JVM.
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-Xmx" + Runtime.getRuntime().maxMemory(), "-Dfile.encoding=UTF-8",
                "-classpath", System.getProperty("java.class.path"), Base85CodecBenchmark.class.getName(),
                "--compiled", input.toString(), output.toString(), Integer.toString(runs))
                .inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IOException("Isolated benchmark JVM failed");
        }
    }

    private static void run(Path input, Path output, int runs) throws Exception {
        Path classes = output.resolve("experimental-classes");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                Base85CodecBenchmark.class.getClassLoader())) {
            Variant base64 = new Variant("base64", Class.forName(PACKAGE + ".FileTreeCodec"),
                    Class.forName(PACKAGE + ".LitematicaFileCodec"));
            Variant base85 = new Variant("base85", loader.loadClass(EXPERIMENT + ".FileTreeCodec"),
                    loader.loadClass(EXPERIMENT + ".LitematicaFileCodec"));
            System.out.printf(Locale.ROOT, "ENV java=%s heap=%d processors=%d input=%s runs=%d%n",
                    System.getProperty("java.version"), Runtime.getRuntime().maxMemory(),
                    Runtime.getRuntime().availableProcessors(), input, runs);
            warmup(output, base64, base85);
            if (Files.isRegularFile(input)) {
                benchmarkPages(input, output, base64, base85);
            }
            for (int run = 1; run <= runs; run++) {
                Variant[] order = run % 2 == 1
                        ? new Variant[]{base64, base85} : new Variant[]{base85, base64};
                for (Variant variant : order) {
                    measure(variant, input, output, "run-" + run, true);
                }
            }
        }
    }

    private static void warmup(Path output, Variant base64, Variant base85) throws Exception {
        Path warm = output.resolve("warmup.bin");
        byte[] bytes = new byte[8 * 1024 * 1024];
        new Random(85).nextBytes(bytes);
        Arrays.fill(bytes, 0, 1024 * 1024, (byte) 0);
        Files.write(warm, bytes);
        for (int run = 0; run < 3; run++) {
            for (Variant variant : new Variant[]{base64, base85}) {
                measure(variant, warm, output, "warm-" + run, false);
            }
        }
    }

    private static void compileExperiment(Path classes) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("A JDK is required for the isolated experimental codec");
        }
        List<SimpleJavaFileObject> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.list(Path.of("src/main/java/awszqsedxzaw/cloudarchive/codec"))) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(source).replace(PACKAGE, EXPERIMENT);
                if (source.getFileName().toString().equals("LitematicaFileCodec.java")) {
                    text = replaceOnce(text, "MAX_PAGE_CHARS = 32_764", "MAX_PAGE_CHARS = 32_760");
                    text = replaceOnce(text, "BYTES_PER_PAGE = MAX_PAGE_CHARS / 4 * 3",
                            "BYTES_PER_PAGE = MAX_PAGE_CHARS / 5 * 4");
                    text = replaceOnce(text, "int pageLength = BASE64_ENCODER.encode(input, page);",
                            "byte[] encoded = " + PACKAGE + ".Base85CodecBenchmark.Base85.encode(input)"
                                    + ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);\n"
                                    + "            System.arraycopy(encoded, 0, page, 0, encoded.length);\n"
                                    + "            int pageLength = encoded.length;");
                    int start = text.indexOf("    private static byte[] decodePage(");
                    int end = text.indexOf("    private static ", start + "    private static ".length());
                    if (start < 0 || end < 0) {
                        throw new IOException("Cannot locate the current page decoder");
                    }
                    text = text.substring(0, start)
                            + "    private static byte[] decodePage(String page) throws IOException {\n"
                            + "        if (page.isEmpty() || page.length() > MAX_PAGE_CHARS) {\n"
                            + "            throw new IOException(\"Invalid experimental Base85 page length\");\n"
                            + "        }\n"
                            + "        return " + PACKAGE + ".Base85CodecBenchmark.Base85.decode(page);\n"
                            + "    }\n\n" + text.substring(end);
                    start = text.indexOf("    private static int decodePage(");
                    end = text.indexOf("    private static ", start + "    private static ".length());
                    if (start < 0 || end < 0) {
                        throw new IOException("Cannot locate the streaming page decoder");
                    }
                    text = text.substring(0, start)
                            + "    private static int decodePage(String page, byte[] target, int offset)"
                            + " throws IOException {\n"
                            + "        byte[] decoded = decodePage(page);\n"
                            + "        System.arraycopy(decoded, 0, target, offset, decoded.length);\n"
                            + "        return decoded.length;\n"
                            + "    }\n\n" + text.substring(end);
                }
                String content = text;
                sources.add(new SimpleJavaFileObject(URI.create("string:///"
                        + EXPERIMENT.replace('.', '/') + "/" + source.getFileName()),
                        javax.tools.JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return content;
                    }
                });
            }
        }
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = List.of("--release", "17", "-proc:none", "-encoding", "UTF-8",
                    "-classpath", System.getProperty("java.class.path"), "-d", classes.toString());
            if (!compiler.getTask(null, manager, null, options, null, sources).call()) {
                throw new IOException("Experimental Base85 codec compilation failed");
            }
        }
    }

    private static String replaceOnce(String text, String before, String after) throws IOException {
        int position = text.indexOf(before);
        if (position < 0 || text.indexOf(before, position + before.length()) >= 0) {
            throw new IOException("Expected exactly one source fragment: " + before);
        }
        return text.substring(0, position) + after + text.substring(position + before.length());
    }

    private static void measure(Variant variant, Path input, Path output, String label, boolean report)
            throws Exception {
        Path archive = output.resolve(label + "-" + variant.name + ".litematic");
        Path restored = output.resolve(label + "-" + variant.name + "-restored");
        long start = System.nanoTime();
        Object info = variant.pack(input, archive);
        double pack = (System.nanoTime() - start) / 1e9;
        start = System.nanoTime();
        invoke(variant.restore, archive, restored);
        double restore = (System.nanoTime() - start) / 1e9;
        compare(input, restored);
        if (report) {
            long stored = (long) invokeOn(info.getClass().getMethod("storedSize"), info);
            int books = (int) invokeOn(info.getClass().getMethod("dataBookCount"), info);
            int barrels = (int) invokeOn(info.getClass().getMethod("barrelCount"), info);
            long characters = stored / variant.capacity * variant.fullChars
                    + variant.encodedLength((int) (stored % variant.capacity));
            long nbtBytes = 0;
            try (var gzip = new GZIPInputStream(Files.newInputStream(archive), 65536)) {
                nbtBytes = gzip.transferTo(OutputStream.nullOutputStream());
            }
            System.out.printf(Locale.ROOT,
                    "BASE85_BENCH input=%s run=%s encoding=%s bytes=%d nbt_bytes=%d data_chars=%d "
                            + "pack_s=%.6f restore_s=%.6f books=%d barrels=%d stored=%d verified=true%n",
                    input.getFileName(), label, variant.name, Files.size(archive), nbtBytes,
                    characters, pack, restore, books, barrels, stored);
        }
    }

    private static void benchmarkPages(Path input, Path output, Variant base64, Variant base85)
            throws Exception {
        Path raw = output.resolve("page-sample.raw");
        byte[] sample;
        try (var stream = Files.newInputStream(input)) {
            sample = stream.readNBytes(16 * 1024 * 1024);
        }
        Files.write(raw, sample);
        Path stored = output.resolve("page-sample.stored");
        DataCompression.encode(raw, stored);
        sample = Files.readAllBytes(stored);
        for (int run = 0; run < 7; run++) {
            Variant[] order = run % 2 == 0
                    ? new Variant[]{base64, base85} : new Variant[]{base85, base64};
            for (Variant variant : order) {
                List<byte[]> chunks = new ArrayList<>();
                for (int offset = 0; offset < sample.length; offset += variant.capacity) {
                    chunks.add(Arrays.copyOfRange(sample, offset,
                            Math.min(sample.length, offset + variant.capacity)));
                }
                long start = System.nanoTime();
                List<String> pages = new ArrayList<>();
                for (byte[] chunk : chunks) {
                    pages.add(variant.name.equals("base64")
                            ? BASE64_ENCODER.encodeToString(chunk) : Base85.encode(chunk));
                }
                double encode = (System.nanoTime() - start) / 1e9;
                start = System.nanoTime();
                List<byte[]> decoded = new ArrayList<>();
                for (String page : pages) {
                    decoded.add((byte[]) invoke(variant.decode, page));
                }
                double decode = (System.nanoTime() - start) / 1e9;
                for (int i = 0; i < chunks.size(); i++) {
                    FileCodecSelfTest.require(Arrays.equals(chunks.get(i), decoded.get(i)), "Page bytes differ");
                    sink += decoded.get(i).length;
                }
                if (run >= 2) {
                    System.out.printf(Locale.ROOT,
                            "BASE85_PAGES run=%d encoding=%s source=%d chars=%d encode_s=%.6f "
                                    + "decode_s=%.6f verified=true%n", run - 1, variant.name, sample.length,
                            pages.stream().mapToLong(String::length).sum(), encode, decode);
                }
            }
        }
    }

    private static void compare(Path source, Path restored) throws IOException {
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

    private static Object invoke(Method method, Object... arguments) throws Exception {
        return invokeOn(method, null, arguments);
    }

    private static Object invokeOn(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static void verifyBase85() throws IOException {
        FileCodecSelfTest.require(Base85.encode(new byte[4]).equals("00000"), "Zero Base85 vector differs");
        FileCodecSelfTest.require(Base85.encode("hello".getBytes(StandardCharsets.US_ASCII)).equals("Xk~0{Zv"),
                "Git/Python Base85 vector differs");
        for (int length = 0; length <= 4096; length++) {
            byte[] bytes = FileCodecSelfTest.randomBytes(length, length);
            FileCodecSelfTest.require(Arrays.equals(bytes, Base85.decode(Base85.encode(bytes))),
                    "Base85 remainder round trip differs");
        }
        for (String invalid : List.of("0", "~~~~~", "\"\"", "\\\\", "01", "\u0100\u0100")) {
            boolean rejected = false;
            try {
                Base85.decode(invalid);
            } catch (IOException expected) {
                rejected = true;
            }
            FileCodecSelfTest.require(rejected, "Invalid Base85 accepted: " + invalid);
        }
        System.out.println("Base85 vectors, partial groups, overflow and canonicality checks passed");
    }

    private static final class Variant {
        private final String name;
        private final Method packFile;
        private final Method packTree;
        private final Method restore;
        private final Method decode;
        private final int capacity;
        private final int fullChars;

        private Variant(String name, Class<?> tree, Class<?> file) throws ReflectiveOperationException {
            this.name = name;
            packFile = file.getMethod("pack", Path.class, Path.class);
            packTree = tree.getMethod("pack", Path.class, Path.class);
            restore = tree.getMethod("restore", Path.class, Path.class);
            decode = file.getDeclaredMethod("decodePage", String.class);
            decode.setAccessible(true);
            capacity = file.getField("BYTES_PER_PAGE").getInt(null);
            fullChars = file.getField("MAX_PAGE_CHARS").getInt(null);
        }

        private Object pack(Path source, Path destination) throws Exception {
            if (Files.isDirectory(source)) {
                Object summary = invoke(packTree, source, destination);
                return invokeOn(summary.getClass().getMethod("archive"), summary);
            }
            return invoke(packFile, source, destination);
        }

        private int encodedLength(int length) {
            return name.equals("base64") ? (length * 8 + 5) / 6 : length / 4 * 5
                    + (length % 4 == 0 ? 0 : length % 4 + 1);
        }
    }

    // Git/Python's RFC 1924 alphabet; partial groups use zero padding without emitting padding characters.
    public static final class Base85 {
        private static final String ALPHABET =
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~";
        private static final int[] VALUES = new int[128];

        static {
            Arrays.fill(VALUES, -1);
            for (int i = 0; i < ALPHABET.length(); i++) {
                VALUES[ALPHABET.charAt(i)] = i;
            }
        }

        private Base85() {
        }

        public static String encode(byte[] bytes) {
            int remainder = bytes.length % 4;
            char[] result = new char[bytes.length / 4 * 5 + (remainder == 0 ? 0 : remainder + 1)];
            char[] group = new char[5];
            int position = 0;
            for (int offset = 0; offset < bytes.length; offset += 4) {
                int count = Math.min(4, bytes.length - offset);
                long value = 0;
                for (int i = 0; i < 4; i++) {
                    value = value << 8 | (i < count ? bytes[offset + i] & 255L : 0);
                }
                for (int i = 4; i >= 0; i--) {
                    group[i] = ALPHABET.charAt((int) (value % 85));
                    value /= 85;
                }
                int characters = count == 4 ? 5 : count + 1;
                System.arraycopy(group, 0, result, position, characters);
                position += characters;
            }
            return new String(result);
        }

        public static byte[] decode(String text) throws IOException {
            int remainder = text.length() % 5;
            if (remainder == 1) {
                throw new IOException("Invalid Base85 group length");
            }
            byte[] result = new byte[text.length() / 5 * 4 + (remainder == 0 ? 0 : remainder - 1)];
            int position = 0;
            for (int offset = 0; offset < text.length(); offset += 5) {
                int count = Math.min(5, text.length() - offset);
                long value = 0;
                for (int i = 0; i < 5; i++) {
                    int digit = 84;
                    if (i < count) {
                        char character = text.charAt(offset + i);
                        if (character >= VALUES.length || VALUES[character] < 0) {
                            throw new IOException("Invalid Base85 character");
                        }
                        digit = VALUES[character];
                    }
                    value = value * 85 + digit;
                }
                if (value > 0xFFFFFFFFL) {
                    throw new IOException("Base85 group overflow");
                }
                int bytes = count == 5 ? 4 : count - 1;
                for (int i = 0; i < bytes; i++) {
                    result[position++] = (byte) (value >>> (24 - i * 8));
                }
            }
            if (remainder > 0) {
                String canonical = encode(Arrays.copyOfRange(result, result.length - remainder + 1, result.length));
                if (!text.regionMatches(text.length() - remainder, canonical, 0, remainder)) {
                    throw new IOException("Noncanonical Base85 final group");
                }
            }
            return result;
        }
    }
}
