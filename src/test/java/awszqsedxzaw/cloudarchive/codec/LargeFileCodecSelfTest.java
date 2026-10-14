package awszqsedxzaw.cloudarchive.codec;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

// Separate stress task so normal builds do not write several GiB to disk.
public final class LargeFileCodecSelfTest {
    private LargeFileCodecSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "cloudarchive-large-");
        try {
            Path random = work.resolve("random.bin");
            Random generator = new Random(20261014);
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream output = Files.newOutputStream(random)) {
                for (long left = 512L * 1024 * 1024; left > 0; left -= buffer.length) {
                    generator.nextBytes(buffer);
                    output.write(buffer);
                }
            }
            roundTrip(random, work, "random-512m");
            Files.delete(random);
            Path large = work.resolve("over-2g.bin");
            try (FileChannel output = FileChannel.open(large,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                output.write(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
                output.position((1L << 31) + 32);
                output.write(ByteBuffer.wrap(new byte[]{5}));
            }
            roundTrip(large, work, "over-2g");
            System.out.println("Large-file checks passed with max heap " + Runtime.getRuntime().maxMemory());
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void roundTrip(Path source, Path work, String label) throws Exception {
        Path archive = work.resolve(label + ".litematic");
        Path restored = work.resolve(label + ".restored");
        long start = System.nanoTime();
        LitematicaFileCodec.ArchiveInfo info = LitematicaFileCodec.pack(source, archive);
        double pack = (System.nanoTime() - start) / 1e9;
        start = System.nanoTime();
        LitematicaFileCodec.restore(archive, restored);
        double restore = (System.nanoTime() - start) / 1e9;
        FileCodecSelfTest.require(Files.mismatch(source, restored) == -1, "Large-file bytes differ");
        if (label.startsWith("random")) {
            FileCodecSelfTest.require(info.barrelCount() > 8, "Random fixture did not cross enough barrels");
        }
        System.out.printf(Locale.ROOT,
                "LARGE label=%s raw=%d archive=%d pack_s=%.3f restore_s=%.3f barrels=%d verified=true%n",
                label, Files.size(source), Files.size(archive), pack, restore, info.barrelCount());
        Files.delete(archive);
        Files.delete(restored);
    }
}
