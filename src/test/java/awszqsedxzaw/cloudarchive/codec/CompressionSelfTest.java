package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import static awszqsedxzaw.cloudarchive.codec.FileCodecSelfTest.*;

final class CompressionSelfTest {
    private CompressionSelfTest() {
    }

    static void run(Path work) throws IOException {
        verifyEncoderRollback(work);
        Path source = Files.createDirectory(work.resolve("compressed-source"));
        Path file = source.resolve("a.txt");
        try (OutputStream output = Files.newOutputStream(file)) {
            byte[] block = "CloudArchive repeated text\n".repeat(4096).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            for (int i = 0; i < 125; i++) {
                output.write(block);
            }
        }
        Files.copy(file, source.resolve("b.txt"));
        Files.copy(file, source.resolve("c.txt"));
        Files.write(source.resolve("d.bin"), randomBytes(200_000, 93));
        Path archive = work.resolve("compressed.litematic");
        FileTreeCodec.Summary result = FileTreeCodec.pack(source, archive);
        require(result.contentSize() == Files.size(file) * 3 + 200_000, "Total bytes exclude duplicates");
        require(result.archive().dataSize() == Files.size(file) + 200_000, "Duplicate data was stored");
        require(result.archive().storedSize() < 250_000 && result.archive().dataBookCount() == 1,
                "Compression did not reduce book count");
        Path metadata = work.resolve("compressed.metadata");
        Path raw = work.resolve("compressed.raw");
        TreeArchiveFormat.Manifest manifest = LitematicaFileCodec.restoreTransport(archive, metadata, raw).manifest();
        require(manifest.blocks().size() > 1 && manifest.blocks().stream().anyMatch(b -> b.codec() == 1),
                "Compressed multi-block fixture missing");
        require(manifest.nodes().get(0).extents().equals(manifest.nodes().get(1).extents())
                && manifest.nodes().get(1).extents().equals(manifest.nodes().get(2).extents()),
                "Duplicate files do not share logical extents");
        Path restored = work.resolve("compressed-restored");
        FileTreeCodec.restore(archive, restored);
        for (String name : List.of("a.txt", "b.txt", "c.txt", "d.bin")) {
            require(Files.mismatch(source.resolve(name), restored.resolve(name)) == -1, "Compressed bytes differ");
        }
        require(FileTreeCodec.list(archive).summary().contentSize() == result.contentSize(), "Compressed listing differs");

        NbtCompound random = NbtIo.readCompressed(work.resolve("exact-book.bin.litematic").toFile());
        Path randomMetadata = work.resolve("random.metadata");
        Files.write(randomMetadata, decodeBooks(items(random, 0)));
        TreeArchiveFormat.Manifest incompressible = TreeArchiveFormat.read(randomMetadata);
        require(incompressible.blocks().stream().allMatch(b -> b.codec() == 0)
                && incompressible.storedLength() == incompressible.dataLength(), "RAW fallback expanded data");
        mutateAndReject(work, "noncanonical-base64", random, root ->
                pages(items(root, 1).getCompound(0)).set(99, NbtString.of("AB")));

        NbtCompound valid = NbtIo.readCompressed(archive.toFile());
        for (int test = 0; test < 6; test++) {
            int scenario = test;
            mutateAndReject(work, "bad-block-" + test, valid, root -> {
                NbtList pages = pages(items(root, 0).getCompound(0));
                byte[] bytes = Base64.getUrlDecoder().decode(pages.getString(0));
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                int table = bytes.length - manifest.blocks().size() * TreeArchiveFormat.BLOCK_RECORD_BYTES;
                switch (scenario) {
                    case 0 -> buffer.put(table, (byte) 2);
                    case 1 -> buffer.putInt(table + 1, TreeArchiveFormat.BLOCK_BYTES + 1);
                    case 2 -> buffer.putInt(table + 5, 0);
                    case 3 -> buffer.putLong(88, manifest.storedLength() + 1);
                    case 4 -> buffer.putInt(96, Integer.MAX_VALUE);
                    case 5 -> buffer.putInt(table + 1, buffer.getInt(table + 1) - 1);
                    default -> throw new AssertionError();
                }
                pages.set(0, NbtString.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
            });
        }
        mutateAndReject(work, "corrupt-zstd", valid, root -> {
            NbtList pages = pages(items(root, 1).getCompound(0));
            byte[] bytes = Base64.getUrlDecoder().decode(pages.getString(0));
            bytes[0] ^= 1;
            pages.set(0, NbtString.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
        });
        try (var input = new java.util.zip.GZIPInputStream(
                Files.newInputStream(work.resolve("cross-book.bin.litematic")), 65536)) {
            require(input.transferTo(OutputStream.nullOutputStream()) > 3 * 1024 * 1024,
                    "Standard gzip reader did not decode all parallel members");
        }
        System.out.println("Zstandard blocks, RAW fallback, deduplication, corruption and standard gzip checks passed");
    }

    private static void verifyEncoderRollback(Path work) throws IOException {
        byte[] prefix = randomBytes(31_173, 41);
        byte[] content = randomBytes(TreeArchiveFormat.BLOCK_BYTES + 12_345, 42);
        byte[] suffix = randomBytes(91, 43);
        Path actual = work.resolve("rollback-stored");
        try (DataCompression.Encoder encoder = new DataCompression.Encoder(actual)) {
            encoder.write(prefix);
            encoder.checkpoint();
            encoder.write(content); // Discard multiple blocks, preserving the buffered prefix.
            encoder.write(content);
            encoder.rollback();
            encoder.checkpoint();
            encoder.write(content);
            encoder.commit();
            encoder.checkpoint();
            encoder.write(suffix); // Roll back without flushing or copying the prefix.
            encoder.rollback();
            encoder.write(suffix);
            encoder.finish();
        }
        Path raw = work.resolve("rollback-raw");
        try (OutputStream output = Files.newOutputStream(raw)) {
            output.write(prefix);
            output.write(content);
            output.write(suffix);
        }
        Path expected = work.resolve("rollback-expected");
        DataCompression.encode(raw, expected);
        require(Files.mismatch(actual, expected) == -1, "Rollback changed the compression stream");

        Path source = Files.createDirectory(work.resolve("rollback-tree"));
        Files.write(source.resolve("a.bin"), prefix);
        Files.write(source.resolve("b.bin"), content);
        Files.write(source.resolve("c-duplicate.bin"), content);
        byte[] different = content.clone();
        different[0] ^= 1;
        Files.write(source.resolve("d-same-size.bin"), different);
        Files.write(source.resolve("e-small-duplicate.bin"), prefix);
        Files.write(source.resolve("f.bin"), suffix);
        Path archive = work.resolve("rollback-tree.litematic");
        FileTreeCodec.Summary info = FileTreeCodec.pack(source, archive);
        require(info.archive().dataSize() == prefix.length + content.length * 2L + suffix.length,
                "Single-read deduplication discarded unequal contents or retained duplicates");
        Path restored = work.resolve("rollback-tree-restored");
        FileTreeCodec.restore(archive, restored);
        try (var paths = Files.list(source)) {
            for (Path path : paths.toList()) {
                require(Files.mismatch(path, restored.resolve(path.getFileName())) == -1,
                        "Transactional deduplication changed file contents");
            }
        }
        System.out.println("Streaming compression rollback and equal-size collision checks passed");
    }
}
