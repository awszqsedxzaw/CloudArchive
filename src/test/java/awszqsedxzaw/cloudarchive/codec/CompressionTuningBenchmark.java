package awszqsedxzaw.cloudarchive.codec;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public final class CompressionTuningBenchmark {
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CompressionTuningBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Expected: <current.litematic> <new-output-directory> [runs]");
        }
        int runs = args.length == 3 ? Integer.parseInt(args[2]) : 3;
        if (runs < 1) {
            throw new IllegalArgumentException("Runs must be positive");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Path raw = output.resolve("raw");
        TreeArchiveFormat.Manifest manifest = LitematicaFileCodec.restoreTransport(
                Path.of(args[0]), output.resolve("original.metadata"), raw).manifest();
        List<Configuration> configurations = List.of(
                new Configuration("static-6", 6, false, 0),
                new Configuration("context-6", 6, true, 0),
                new Configuration("workers2-6", 6, true, 2),
                new Configuration("context-1", 1, true, 0),
                new Configuration("context-3", 3, true, 0),
                new Configuration("context-9", 9, true, 0),
                new Configuration("context-12", 12, true, 0));
        Path warm = output.resolve("warmup");
        try (InputStream input = Files.newInputStream(raw)) {
            Files.write(warm, input.readNBytes(8 * 1024 * 1024));
        }
        for (Configuration configuration : configurations) {
            encode(warm, output.resolve("warm-" + configuration.name), configuration);
        }
        System.out.printf(Locale.ROOT, "TUNING_ENV source=%d java=%s heap=%d runs=%d%n",
                Files.size(raw), System.getProperty("java.version"), Runtime.getRuntime().maxMemory(), runs);
        for (int run = 1; run <= runs; run++) {
            List<Configuration> order = new ArrayList<>(configurations);
            if (run % 2 == 0) {
                java.util.Collections.reverse(order);
            }
            for (Configuration configuration : order) {
                Path stored = output.resolve(configuration.name + ".stored");
                long start = System.nanoTime();
                DataCompression.Encoded encoded = encode(raw, stored, configuration);
                double seconds = (System.nanoTime() - start) / 1e9;
                Path metadata = output.resolve(configuration.name + ".metadata");
                TreeArchiveFormat.write(metadata, manifest.kind(), manifest.archiveName(), manifest.nodes(),
                        manifest.dataLength(), manifest.dataSha256(), manifest.nextId(),
                        encoded.storedLength(), encoded.blocks());
                TreeArchiveFormat.Manifest variant = TreeArchiveFormat.read(metadata);
                start = System.nanoTime();
                DataCompression.decode(stored, OutputStream.nullOutputStream(), variant);
                double decode = (System.nanoTime() - start) / 1e9;
                if (configuration.name.equals("context-6")) {
                    FileCodecSelfTest.require(Files.mismatch(output.resolve("static-6.stored"), stored) == -1,
                            "Reused context changed level-6 frames");
                }
                System.out.printf(Locale.ROOT,
                        "ZSTD_TUNING run=%d mode=%s stored=%d encode_s=%.6f decode_s=%.6f verified=true%n",
                        run, configuration.name, encoded.storedLength(), seconds, decode);
                if (run == runs) {
                    Path archive = output.resolve(configuration.name + ".litematic");
                    start = System.nanoTime();
                    LitematicaFileCodec.packArchive(metadata, stored, archive, manifest.kind());
                    double transport = (System.nanoTime() - start) / 1e9;
                    System.out.printf(Locale.ROOT,
                            "TUNING_ARCHIVE mode=%s bytes=%d transport_pack_s=%.6f%n",
                            configuration.name, Files.size(archive), transport);
                }
            }
        }
        benchmarkGzip(output.resolve("context-6.stored"), output, runs);
    }

    private static DataCompression.Encoded encode(Path raw, Path stored, Configuration configuration)
            throws IOException {
        byte[] source = new byte[TreeArchiveFormat.BLOCK_BYTES];
        byte[] compressed = new byte[(int) Zstd.compressBound(source.length)];
        List<TreeArchiveFormat.Block> blocks = new ArrayList<>();
        long length = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(raw));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(stored));
             ZstdCompressCtx context = configuration.context
                     ? new ZstdCompressCtx().setLevel(configuration.level).setWorkers(configuration.workers) : null) {
            int count;
            while ((count = input.readNBytes(source, 0, source.length)) > 0) {
                long size = context == null
                        ? Zstd.compressByteArray(compressed, 0, compressed.length, source, 0, count,
                                configuration.level)
                        : context.compressByteArray(compressed, 0, compressed.length, source, 0, count);
                if (Zstd.isError(size)) {
                    throw new IOException(Zstd.getErrorName(size));
                }
                boolean smaller = size < count;
                int storedLength = smaller ? (int) size : count;
                output.write(smaller ? compressed : source, 0, storedLength);
                blocks.add(new TreeArchiveFormat.Block(smaller ? 1 : 0, count, storedLength));
                length += storedLength;
            }
        }
        return new DataCompression.Encoded(length, List.copyOf(blocks));
    }

    private static void benchmarkGzip(Path stored, Path output, int runs) throws IOException {
        byte[] sample;
        try (InputStream input = Files.newInputStream(stored)) {
            sample = input.readNBytes(16 * 1024 * 1024);
        }
        List<byte[]> pages = new ArrayList<>();
        for (int offset = 0; offset < sample.length; offset += LitematicaFileCodec.BYTES_PER_PAGE) {
            pages.add(BASE64_ENCODER.encode(java.util.Arrays.copyOfRange(sample, offset,
                    Math.min(sample.length, offset + LitematicaFileCodec.BYTES_PER_PAGE))));
        }
        for (int run = 0; run <= runs; run++) {
            for (int level : new int[]{1, 3, 6, 9}) {
                Path target = output.resolve("pages-gzip-" + level);
                long start = System.nanoTime();
                try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(target), 65536) {
                    {
                        def.setLevel(level);
                    }
                }; DataOutputStream data = new DataOutputStream(gzip)) {
                    for (byte[] page : pages) {
                        data.writeShort(page.length);
                        data.write(page);
                    }
                }
                if (run > 0) {
                    System.out.printf(Locale.ROOT,
                            "GZIP_TUNING run=%d level=%d source=%d bytes=%d seconds=%.6f%n",
                            run, level, sample.length, Files.size(target), (System.nanoTime() - start) / 1e9);
                }
            }
        }
    }

    private record Configuration(String name, int level, boolean context, int workers) {
    }
}
