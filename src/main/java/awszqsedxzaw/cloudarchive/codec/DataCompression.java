package awszqsedxzaw.cloudarchive.codec;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

final class DataCompression {
    static final int LEVEL = 3;

    private DataCompression() {
    }

    static Encoded encode(Path raw, Path stored) throws IOException {
        try (InputStream input = Files.newInputStream(raw);
             Encoder encoder = new Encoder(stored)) {
            input.transferTo(encoder);
            return encoder.finish();
        }
    }

    // A bounded transaction permits hash-based deduplication after a single source read.
    static final class Encoder extends OutputStream {
        private final byte[] source = new byte[TreeArchiveFormat.BLOCK_BYTES];
        private final byte[] compressed = new byte[(int) Zstd.compressBound(source.length)];
        private final List<TreeArchiveFormat.Block> blocks = new ArrayList<>();
        private final FileChannel output;
        private final ZstdCompressCtx compressor = new ZstdCompressCtx().setLevel(LEVEL);
        private MessageDigest digest = LitematicaFileCodec.newSha256();
        private int buffered;
        private long storedLength;
        private Checkpoint checkpoint;
        private boolean finished;

        Encoder(Path stored) throws IOException {
            output = FileChannel.open(stored, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        void checkpoint() throws IOException {
            if (checkpoint != null || finished) {
                throw new IOException("Invalid compression checkpoint");
            }
            try {
                checkpoint = new Checkpoint(buffered, storedLength, blocks.size(), (MessageDigest) digest.clone());
            } catch (CloneNotSupportedException exception) {
                throw new IOException("SHA-256 provider must support snapshotting for single-pass deduplication",
                        exception);
            }
        }

        void commit() {
            checkpoint = null;
        }

        void rollback() throws IOException {
            if (checkpoint == null) {
                throw new IOException("Missing compression checkpoint");
            }
            output.truncate(checkpoint.storedLength).position(checkpoint.storedLength);
            blocks.subList(checkpoint.blockCount, blocks.size()).clear();
            storedLength = checkpoint.storedLength;
            buffered = checkpoint.buffered;
            if (checkpoint.prefix != null) {
                System.arraycopy(checkpoint.prefix, 0, source, 0, buffered);
            }
            digest = checkpoint.digest;
            checkpoint = null;
        }

        @Override
        public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (finished) {
                throw new IOException("Compression encoder is finished");
            }
            digest.update(bytes, offset, length);
            while (length > 0) {
                int count = Math.min(length, source.length - buffered);
                System.arraycopy(bytes, offset, source, buffered, count);
                buffered += count;
                offset += count;
                length -= count;
                if (buffered == source.length) {
                    writeBlock();
                }
            }
        }

        private void writeBlock() throws IOException {
            // The prefix is only overwritten after a block flush, so tiny duplicates
            // can roll back by changing buffered alone without copying megabytes.
            if (checkpoint != null && checkpoint.prefix == null && checkpoint.buffered > 0) {
                checkpoint.prefix = Arrays.copyOf(source, checkpoint.buffered);
            }
            try {
                long result = compressor.compressByteArray(compressed, 0, compressed.length,
                        source, 0, buffered);
                check(result);
                boolean smaller = result < buffered;
                int size = smaller ? (int) result : buffered;
                ByteBuffer buffer = ByteBuffer.wrap(smaller ? compressed : source, 0, size);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                blocks.add(new TreeArchiveFormat.Block(smaller ? 1 : 0, buffered, size));
                storedLength = TreeArchiveFormat.add(storedLength, size);
                buffered = 0;
            } catch (ZstdException exception) {
                throw new IOException("Zstandard compression failed", exception);
            }
        }

        Encoded finish() throws IOException {
            if (finished || checkpoint != null) {
                throw new IOException("Invalid compression finish");
            }
            if (buffered > 0) {
                writeBlock();
            }
            finished = true;
            return new Encoded(storedLength, List.copyOf(blocks));
        }

        String sha256() {
            if (!finished) {
                throw new IllegalStateException("Compression is not finished");
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        @Override
        public void close() throws IOException {
            try {
                compressor.close();
            } finally {
                output.close();
            }
        }

        private static final class Checkpoint {
            final int buffered;
            final long storedLength;
            final int blockCount;
            final MessageDigest digest;
            byte[] prefix;

            Checkpoint(int buffered, long storedLength, int blockCount, MessageDigest digest) {
                this.buffered = buffered;
                this.storedLength = storedLength;
                this.blockCount = blockCount;
                this.digest = digest;
            }
        }
    }

    static void decode(Path stored, OutputStream raw, TreeArchiveFormat.Manifest manifest) throws IOException {
        MessageDigest digest = LitematicaFileCodec.newSha256();
        byte[] source = new byte[TreeArchiveFormat.BLOCK_BYTES];
        byte[] decoded = new byte[TreeArchiveFormat.BLOCK_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(stored));
             ZstdDecompressCtx decompressor = new ZstdDecompressCtx()) {
            DigestOutputStream verified = new DigestOutputStream(raw, digest);
            for (TreeArchiveFormat.Block block : manifest.blocks()) {
                if (input.readNBytes(source, 0, block.storedLength()) != block.storedLength()) {
                    throw new IOException("Archive compression block is truncated");
                }
                if (block.codec() == 0) {
                    verified.write(source, 0, block.rawLength());
                } else {
                    long size = decompressor.decompressByteArray(decoded, 0, block.rawLength(),
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
