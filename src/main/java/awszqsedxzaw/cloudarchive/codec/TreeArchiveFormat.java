package awszqsedxzaw.cloudarchive.codec;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TreeArchiveFormat {
    // "CAMD": CloudArchive Metadata.
    static final int MAGIC = 0x43414D44;
    static final int VERSION = 1;
    static final int HEADER_BYTES = 88;
    static final int SHA256_BYTES = 32;
    static final int MAX_NAME_BYTES = 1024;
    static final int MAX_METADATA_BOOKS = LitematicaFileCodec.INVENTORY_SLOTS;

    private TreeArchiveFormat() {
    }

    static long indexSize(String archiveName, List<Node> nodes) throws IOException {
        byte[] name = utf8Name(archiveName, "archive name");
        long size = HEADER_BYTES + name.length;
        for (Node node : nodes) {
            validateNodeShape(node);
            size = add(size, nodeSize(node));
        }
        return size;
    }

    static int metadataBookCount(long metadataLength) throws IOException {
        if (metadataLength < HEADER_BYTES || metadataLength > LitematicaFileCodec.MAX_ARCHIVE_BYTES) {
            throw new IOException("Metadata is outside the supported size range");
        }
        return (int) Math.max(1, (metadataLength + LitematicaFileCodec.BYTES_PER_BOOK - 1)
                / LitematicaFileCodec.BYTES_PER_BOOK);
    }

    static int dataBookCount(long dataLength) throws IOException {
        if (dataLength < 0 || dataLength > LitematicaFileCodec.MAX_ARCHIVE_BYTES) {
            throw new IOException("Data is outside the supported size range");
        }
        return (int) (dataLength == 0 ? 0
                : (dataLength + LitematicaFileCodec.BYTES_PER_BOOK - 1)
                / LitematicaFileCodec.BYTES_PER_BOOK);
    }

    static void write(
            Path metadata,
            ArchiveKind kind,
            String archiveName,
            List<Node> nodes,
            long dataLength,
            String dataSha256,
            long nextId
    ) throws IOException {
        byte[] name = utf8Name(archiveName, "archive name");
        byte[] hash = decodeHash(dataSha256);
        int dataBooks = dataBookCount(dataLength);
        long indexLength = indexSize(archiveName, nodes);
        if (metadataBookCount(indexLength) > MAX_METADATA_BOOKS) {
            throw new IOException("Metadata needs more than one barrel");
        }
        validate(kind, archiveName, nodes, dataLength, nextId);
        try (DataOutputStream output = new DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(metadata)))) {
            output.writeInt(MAGIC);
            output.writeShort(VERSION);
            output.writeShort(0);
            output.writeByte(kind.code);
            output.write(new byte[3]);
            output.writeLong(0);
            output.writeLong(nextId);
            output.writeInt(nodes.size());
            output.writeLong(indexLength);
            output.writeLong(dataLength);
            output.writeInt(dataBooks);
            output.writeInt(name.length);
            output.write(hash);
            output.write(name);
            writeNodes(output, nodes);
        }
        if (Files.size(metadata) != indexLength) {
            throw new IOException("Metadata length calculation is inconsistent");
        }
    }

    private static void writeNodes(DataOutput output, List<Node> nodes) throws IOException {
        for (Node node : nodes) {
            output.writeByte(node.directory ? 0 : 1);
            output.writeLong(node.id);
            output.writeLong(node.parentId);
            byte[] name = node.name.getBytes(StandardCharsets.UTF_8);
            output.writeInt(name.length);
            output.write(name);
            if (!node.directory) {
                output.writeLong(node.size);
                output.write(decodeHash(node.sha256));
                output.writeInt(node.extents.size());
                for (Extent extent : node.extents) {
                    output.writeLong(extent.offset);
                    output.writeLong(extent.length);
                }
            }
        }
    }

    static Manifest read(Path metadata) throws IOException {
        long metadataSize = Files.size(metadata);
        if (metadataSize < HEADER_BYTES || metadataSize > LitematicaFileCodec.BYTES_PER_BARREL) {
            throw new IOException("Metadata is truncated or exceeds one barrel");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(metadata)))) {
            if (input.readInt() != MAGIC || input.readUnsignedShort() != VERSION) {
                throw new IOException("Unsupported archive metadata magic or version");
            }
            if (input.readUnsignedShort() != 0) {
                throw new IOException("Unsupported archive metadata feature flags");
            }
            ArchiveKind kind = ArchiveKind.fromCode(input.readUnsignedByte());
            if (input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) {
                throw new IOException("Unsupported archive metadata header");
            }
            long generation = input.readLong();
            long nextId = input.readLong();
            int count = input.readInt();
            long indexLength = input.readLong();
            long dataLength = input.readLong();
            int dataBookCount = input.readInt();
            int nameLength = input.readInt();
            byte[] dataHash = input.readNBytes(SHA256_BYTES);
            if (generation < 0 || nextId < 1 || count < 0 || nameLength < 1
                    || nameLength > MAX_NAME_BYTES || dataHash.length != SHA256_BYTES
                    || indexLength < HEADER_BYTES + nameLength || indexLength != metadataSize
                    || dataLength < 0 || dataLength > LitematicaFileCodec.MAX_ARCHIVE_BYTES
                    || dataBookCount != dataBookCount(dataLength)) {
                throw new IOException("Invalid archive metadata lengths or counts");
            }
            String archiveName = decodeUtf8(input.readNBytes(nameLength), "archive name");
            if (count > (indexLength - HEADER_BYTES - nameLength) / 22) {
                throw new IOException("Archive metadata node count is too large");
            }
            LimitedInputStream limited = new LimitedInputStream(
                    input, indexLength - HEADER_BYTES - nameLength);
            DataInputStream nodesInput = new DataInputStream(limited);
            List<Node> nodes = new ArrayList<>(Math.min(count, 4096));
            for (int i = 0; i < count; i++) {
                int type = nodesInput.readUnsignedByte();
                if (type != 0 && type != 1) {
                    throw new IOException("Invalid archive node type");
                }
                long id = nodesInput.readLong();
                long parent = nodesInput.readLong();
                int nodeNameLength = nodesInput.readInt();
                if (nodeNameLength < 1 || nodeNameLength > MAX_NAME_BYTES
                        || nodeNameLength > limited.remaining()) {
                    throw new IOException("Invalid archive node name length");
                }
                String nodeName = decodeUtf8(nodesInput.readNBytes(nodeNameLength), "node name");
                long size = 0;
                String hash = "";
                List<Extent> extents = new ArrayList<>();
                if (type == 1) {
                    size = nodesInput.readLong();
                    hash = encodeHash(nodesInput.readNBytes(SHA256_BYTES));
                    int extentCount = nodesInput.readInt();
                    if (extentCount < 0 || extentCount > limited.remaining() / 16) {
                        throw new IOException("Invalid archive extent count");
                    }
                    for (int extent = 0; extent < extentCount; extent++) {
                        extents.add(new Extent(nodesInput.readLong(), nodesInput.readLong()));
                    }
                }
                nodes.add(new Node(id, parent, nodeName, type == 0, size, hash, List.copyOf(extents)));
            }
            if (limited.remaining() != 0) {
                throw new IOException("Archive metadata contains trailing or incomplete records");
            }
            validate(kind, archiveName, nodes, dataLength, nextId);
            return new Manifest(kind, archiveName, dataLength, dataBookCount,
                    encodeHash(dataHash), generation, nextId, List.copyOf(nodes),
                    paths(nodes));
        }
    }

    static void validate(
            ArchiveKind kind,
            String archiveName,
            List<Node> nodes,
            long dataLength,
            long nextId
    ) throws IOException {
        utf8Name(archiveName, "archive name");
        if (dataLength < 0 || dataLength > LitematicaFileCodec.MAX_ARCHIVE_BYTES || nextId < 1) {
            throw new IOException("Invalid archive data length or next node ID");
        }
        if (kind == ArchiveKind.FILE && (nodes.size() != 1 || nodes.get(0).directory()
                || nodes.get(0).parentId() != 0)) {
            throw new IOException("Single-file archive must contain one root file");
        }
        Map<Long, Path> paths = new HashMap<>();
        paths.put(0L, Path.of(""));
        Set<Long> directories = new HashSet<>();
        directories.add(0L);
        Set<String> siblings = new HashSet<>();
        long maxId = 0;
        for (Node node : nodes) {
            validateNodeShape(node);
            if (node.id <= 0 || paths.containsKey(node.id) || !directories.contains(node.parentId)) {
                throw new IOException("Invalid or duplicate archive node ID or parent directory");
            }
            if (!siblings.add(node.parentId + "/" + node.name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate or case-conflicting archive path");
            }
            Path relative;
            try {
                relative = paths.get(node.parentId).resolve(node.name);
            } catch (InvalidPathException exception) {
                throw new IOException("Invalid archive path", exception);
            }
            paths.put(node.id, relative);
            maxId = Math.max(maxId, node.id);
            if (node.directory) {
                if (node.size != 0 || !node.sha256.isEmpty() || !node.extents.isEmpty()) {
                    throw new IOException("Directory node contains file metadata");
                }
                directories.add(node.id);
            } else {
                if (node.size < 0) {
                    throw new IOException("Invalid archive file size");
                }
                long extentSize = 0;
                for (Extent extent : node.extents) {
                    if (extent.offset < 0 || extent.length <= 0
                            || extent.length > dataLength
                            || extent.offset > dataLength - extent.length) {
                        throw new IOException("Archive extent is outside the data area");
                    }
                    extentSize = add(extentSize, extent.length);
                }
                if (extentSize != node.size || (node.size > 0 && node.extents.isEmpty())) {
                    throw new IOException("Archive file extents do not match its size");
                }
                decodeHash(node.sha256);
            }
        }
        if (nextId <= maxId) {
            throw new IOException("Next archive node ID is not greater than allocated IDs");
        }
        if (kind == ArchiveKind.FILE && nodes.get(0).size != dataLength) {
            throw new IOException("Single-file metadata size does not match the data size");
        }
    }

    private static Map<Long, Path> paths(List<Node> nodes) throws IOException {
        Map<Long, Path> paths = new HashMap<>();
        paths.put(0L, Path.of(""));
        for (Node node : nodes) {
            Path parent = paths.get(node.parentId);
            if (parent == null) {
                throw new IOException("Archive node parent is missing");
            }
            paths.put(node.id, parent.resolve(node.name));
        }
        return Map.copyOf(paths);
    }

    private static void validateNodeShape(Node node) throws IOException {
        if (node == null || node.name == null || node.name.isEmpty()) {
            throw new IOException("Archive node is incomplete");
        }
        if (node.sha256 == null || node.extents == null) {
            throw new IOException("Archive node metadata is incomplete");
        }
        validateName(node.name);
        if (!node.directory && node.sha256 == null) {
            throw new IOException("Archive file hash is missing");
        }
        if (node.directory && (node.size != 0 || (node.sha256 != null && !node.sha256.isEmpty()))) {
            throw new IOException("Directory node contains file metadata");
        }
    }

    private static long nodeSize(Node node) throws IOException {
        long size = 1 + 8 + 8 + 4 + utf8Name(node.name, "node name").length;
        if (!node.directory) {
            size = add(size, 8 + SHA256_BYTES + 4 + 16L * node.extents.size());
        }
        return size;
    }

    private static byte[] utf8Name(String value, String label) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IOException("Empty " + label);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new IOException(label + " is too long");
        }
        return bytes;
    }

    static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IOException("Unsafe archive node name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                throw new IOException("Unsafe archive node name: " + name);
            }
            if (Character.isHighSurrogate(c) && i + 1 < name.length()
                    && Character.isLowSurrogate(name.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IOException("Invalid Unicode archive node name");
            }
        }
        String base = name.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if (base.matches("CON|PRN|AUX|NUL|COM[1-9\\u00b9\\u00b2\\u00b3]|LPT[1-9\\u00b9\\u00b2\\u00b3]")) {
            throw new IOException("Unsafe reserved archive node name: " + name);
        }
    }

    private static byte[] decodeHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException("Archive hash is invalid");
        }
        return java.util.HexFormat.of().parseHex(value);
    }

    private static String encodeHash(byte[] value) throws IOException {
        if (value.length != SHA256_BYTES) {
            throw new IOException("Archive hash is truncated");
        }
        return java.util.HexFormat.of().formatHex(value);
    }

    private static String decodeUtf8(byte[] value, String label) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 " + label, exception);
        }
    }

    static long add(long first, long second) throws IOException {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new IOException("Archive size overflow", exception);
        }
    }

    enum ArchiveKind {
        FILE(0),
        TREE(1);

        private final int code;

        ArchiveKind(int code) {
            this.code = code;
        }

        static ArchiveKind fromCode(int code) throws IOException {
            for (ArchiveKind kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }
            throw new IOException("Unsupported archive kind");
        }
    }

    record Extent(long offset, long length) {
    }

    record Node(long id, long parentId, String name, boolean directory, long size,
                String sha256, List<Extent> extents) {
    }

    record Manifest(ArchiveKind kind, String archiveName, long dataLength, int dataBookCount,
                    String dataSha256, long generation, long nextId, List<Node> nodes,
                    Map<Long, Path> paths) {
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream input;
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            this.input = input;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = input.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int count = input.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        long remaining() {
            return remaining;
        }
    }
}
