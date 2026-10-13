package awszqsedxzaw.cloudarchive.codec;

import com.github.luben.zstd.Zstd;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class PageEncodingBenchmark {
    private PageEncodingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected: <input-file>");
        }
        byte[] sample;
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            sample = input.readNBytes(16 * 1024 * 1024);
        }
        for (int level : new int[]{1, 6}) {
            for (String encoding : new String[]{"base64", "byte-to-U0100", "15-bit-to-U3000"}) {
                for (int run = 0; run < 3; run++) {
                    CountingOutputStream counter = new CountingOutputStream();
                    long start = System.nanoTime();
                    try (GZIPOutputStream gzip = new GZIPOutputStream(counter, 65536) {
                        {
                            def.setLevel(level);
                        }
                    }; DataOutputStream output = new DataOutputStream(gzip)) {
                        int capacity = switch (encoding) {
                            case "base64" -> 24573;
                            case "byte-to-U0100" -> 32764;
                            default -> 40957;
                        };
                        for (int offset = 0; offset < sample.length; offset += capacity) {
                            byte[] bytes = Arrays.copyOfRange(sample, offset, Math.min(sample.length, offset + capacity));
                            output.writeUTF(encode(encoding, bytes));
                        }
                    }
                    if (run > 0) {
                        System.out.printf(Locale.ROOT,
                                "ENCODING %s gzip=%d bytes=%d source=%d seconds=%.3f%n",
                                encoding, level, counter.bytes, sample.length, (System.nanoTime() - start) / 1e9);
                    }
                }
            }
        }
        for (int level : new int[]{1, 3, 6}) {
            for (int run = 0; run < 3; run++) {
                long start = System.nanoTime();
                byte[] compressed = Zstd.compress(sample, level);
                if (run > 0) {
                    System.out.printf(Locale.ROOT, "COMPRESS zstd=%d bytes=%d source=%d seconds=%.3f%n",
                            level, compressed.length, sample.length, (System.nanoTime() - start) / 1e9);
                }
            }
        }
        for (int level : new int[]{1, 6}) {
            for (int run = 0; run < 3; run++) {
                CountingOutputStream counter = new CountingOutputStream();
                Deflater deflater = new Deflater(level);
                long start = System.nanoTime();
                try (DeflaterOutputStream output = new DeflaterOutputStream(counter, deflater)) {
                    output.write(sample);
                } finally {
                    deflater.end();
                }
                if (run > 0) {
                    System.out.printf(Locale.ROOT, "COMPRESS deflate=%d bytes=%d source=%d seconds=%.3f%n",
                            level, counter.bytes, sample.length, (System.nanoTime() - start) / 1e9);
                }
            }
        }
    }

    private static String encode(String encoding, byte[] bytes) {
        if (encoding.equals("base64")) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        if (encoding.equals("byte-to-U0100")) {
            char[] chars = new char[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                chars[i] = (char) (0x100 + (bytes[i] & 255));
            }
            return new String(chars);
        }
        char[] chars = new char[(bytes.length * 8 + 14) / 15];
        int bits = 0, value = 0, position = 0;
        for (byte b : bytes) {
            value = value << 8 | (b & 255);
            bits += 8;
            if (bits >= 15) {
                bits -= 15;
                chars[position++] = (char) (0x3000 + (value >>> bits & 32767));
            }
        }
        if (bits > 0) {
            chars[position] = (char) (0x3000 + (value << (15 - bits) & 32767));
        }
        return new String(chars);
    }

    private static final class CountingOutputStream extends OutputStream {
        private long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int offset, int length) {
            bytes += length;
        }
    }
}
