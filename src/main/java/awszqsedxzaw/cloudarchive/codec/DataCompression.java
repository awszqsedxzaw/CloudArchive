package awszqsedxzaw.cloudarchive.codec;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class DataCompression {
    static final int LEVEL = 6;

    private DataCompression() {
    }

    static Encoded encode(Path raw, Path stored) throws IOException {
        List<TreeArchiveFormat.Block> blocks = new ArrayList<>();
        long length = 0;
        byte[] source = new byte[TreeArchiveFormat.BLOCK_BYTES];
        byte[] compressed = new byte[(int) Zstd.compressBound(source.length)];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(raw));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(stored))) {
            int count;
            while ((count = input.readNBytes(source, 0, source.length)) > 0) {
                long result = Zstd.compressByteArray(compressed, 0, compressed.length, source, 0, count, LEVEL);
                check(result);
                boolean smaller = result < count;
                int storedSize = smaller ? (int) result : count;
                output.write(smaller ? compressed : source, 0, storedSize);
                blocks.add(new TreeArchiveFormat.Block(smaller ? 1 : 0, count, storedSize));
                length = TreeArchiveFormat.add(length, storedSize);
            }
        } catch (ZstdException exception) {
            throw new IOException("Zstandard compression failed", exception);
        }
        return new Encoded(length, List.copyOf(blocks));
    }

    static void decode(Path stored, OutputStream raw, TreeArchiveFormat.Manifest manifest) throws IOException {
        MessageDigest digest = LitematicaFileCodec.newSha256();
        byte[] source = new byte[TreeArchiveFormat.BLOCK_BYTES];
        byte[] decoded = new byte[TreeArchiveFormat.BLOCK_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(stored))) {
            DigestOutputStream verified = new DigestOutputStream(raw, digest);
            for (TreeArchiveFormat.Block block : manifest.blocks()) {
                if (input.readNBytes(source, 0, block.storedLength()) != block.storedLength()) {
                    throw new IOException("Archive compression block is truncated");
                }
                if (block.codec() == 0) {
                    verified.write(source, 0, block.rawLength());
                } else {
                    long size = Zstd.decompressByteArray(decoded, 0, block.rawLength(),
                            source, 0, block.storedLength());
                    check(size);
                    if (size != block.rawLength()) {
                        throw new IOException("Archive decompressed block length differs");
                    }
                    verified.write(decoded, 0, block.rawLength());
                }
            }
            if (input.read() != -1) {
                throw new IOException("Archive data contains trailing bytes");
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(manifest.dataSha256())) {
                throw new IOException("Archive data SHA-256 verification failed");
            }
        } catch (ZstdException exception) {
            throw new IOException("Invalid Zstandard archive block", exception);
        }
    }

    private static void check(long result) throws IOException {
        if (Zstd.isError(result)) {
            throw new IOException("Zstandard failed: " + Zstd.getErrorName(result));
        }
    }

    record Encoded(long storedLength, List<TreeArchiveFormat.Block> blocks) {
    }
}
