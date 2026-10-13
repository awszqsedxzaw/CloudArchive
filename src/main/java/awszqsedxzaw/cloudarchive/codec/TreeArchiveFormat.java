package awszqsedxzaw.cloudarchive.codec;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
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
    static final int MAGIC = 0x43415452;
    static final int VERSION = 1;
    static final int HEADER_BYTES = 44;
    static final int MAX_NAME_BYTES = 1024;

    private TreeArchiveFormat() {
    }

    static long indexSize(List<Node> nodes) throws IOException {
        long size = HEADER_BYTES;
        for (Node node : nodes) {
            size = add(size, 21L + node.name().getBytes(StandardCharsets.UTF_8).length);
            if (!node.directory()) {
                size = add(size, 12L + 16L * node.extents().size());
            }
        }
        return size;
    }

    static void writeHeader(DataOutput output, List<Node> nodes, long dataLength, long nextId) throws IOException {
        validate(nodes, dataLength, nextId);
        output.writeInt(MAGIC);
        output.writeShort(VERSION);
        output.writeShort(0); // Feature flags; unknown features must not be silently interpreted.
        output.writeLong(0); // Initial generation; updates and commit handling are not implemented.
        output.writeLong(nextId);
        output.writeInt(nodes.size());
        output.writeLong(indexSize(nodes));
        output.writeLong(dataLength);
    }

    static void writeNodes(DataOutput output, List<Node> nodes) throws IOException {
        for (Node node : nodes) {
            output.writeByte(node.directory() ? 0 : 1);
            output.writeLong(node.id());
            output.writeLong(node.parentId());
            byte[] name = node.name().getBytes(StandardCharsets.UTF_8);
            output.writeInt(name.length);
            output.write(name);
            if (!node.directory()) {
                output.writeLong(node.size());
                output.writeInt(node.extents().size());
                for (Extent extent : node.extents()) {
                    output.writeLong(extent.offset());
                    output.writeLong(extent.length());
                }
            }
        }
    }

    static Manifest read(Path payload) throws IOException {
        long payloadSize = Files.size(payload);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(payload)))) {
            if (input.readInt() != MAGIC || input.readUnsignedShort() != VERSION) {
                throw new IOException("Unsupported tree container magic or version");
            }
            if (input.readUnsignedShort() != 0) {
                throw new IOException("Unsupported tree container feature flags");
            }
            long generation = input.readLong();
            long nextId = input.readLong();
            int count = input.readInt();
            long indexLength = input.readLong();
            long dataLength = input.readLong();
            if (generation < 0 || count < 0 || indexLength < HEADER_BYTES || indexLength > payloadSize
                    || dataLength != payloadSize - indexLength
                    || count > (indexLength - HEADER_BYTES) / 22) {
                throw new IOException("Invalid tree container lengths or node count");
            }
            input.skipNBytes(dataLength);
            LimitedInputStream limited = new LimitedInputStream(input, indexLength - HEADER_BYTES);
            DataInputStream index = new DataInputStream(limited);
            List<Node> nodes = new ArrayList<>(Math.min(count, 4096));
            for (int i = 0; i < count; i++) {
                int type = index.readUnsignedByte();
                if (type != 0 && type != 1) {
                    throw new IOException("Invalid tree node type");
                }
                long id = index.readLong();
                long parent = index.readLong();
                int nameLength = index.readInt();
                if (nameLength < 1 || nameLength > MAX_NAME_BYTES || nameLength > limited.remaining) {
                    throw new IOException("Invalid tree node name length");
                }
                byte[] name = new byte[nameLength];
                index.readFully(name);
                String decoded;
                try {
                    decoded = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(name)).toString();
                } catch (CharacterCodingException exception) {
                    throw new IOException("Invalid UTF-8 tree node name", exception);
                }
                long size = 0;
                List<Extent> extents = new ArrayList<>();
                if (type == 1) {
                    size = index.readLong();
                    int extentCount = index.readInt();
                    if (extentCount < 0 || extentCount > limited.remaining / 16) {
                        throw new IOException("Invalid tree extent count");
                    }
                    for (int extent = 0; extent < extentCount; extent++) {
                        extents.add(new Extent(index.readLong(), index.readLong()));
                    }
                }
                nodes.add(new Node(id, parent, decoded, type == 0, size, List.copyOf(extents)));
            }
            if (limited.remaining != 0) {
                throw new IOException("Tree index contains trailing or incomplete records");
            }
            Map<Long, Path> paths = validate(nodes, dataLength, nextId);
            return new Manifest(indexLength, dataLength, generation, nextId, List.copyOf(nodes), paths);
        }
    }

    static Map<Long, Path> validate(List<Node> nodes, long dataLength, long nextId) throws IOException {
        if (dataLength < 0 || nextId < 1) {
            throw new IOException("Invalid tree data length or next node ID");
        }
        Map<Long, Path> paths = new HashMap<>();
        paths.put(0L, Path.of(""));
        Set<Long> directories = new HashSet<>();
        directories.add(0L);
        Set<String> siblings = new HashSet<>();
        long maxId = 0;
        for (Node node : nodes) {
            validateName(node.name());
            if (node.id() <= 0 || paths.containsKey(node.id()) || !directories.contains(node.parentId())) {
                throw new IOException("Invalid or duplicate tree node ID or parent directory");
            }
            if (!siblings.add(node.parentId() + "/" + node.name().toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate or case-conflicting tree path");
            }
            Path relative;
            try {
                relative = paths.get(node.parentId()).resolve(node.name());
            } catch (InvalidPathException exception) {
                throw new IOException("Invalid tree path", exception);
            }
            paths.put(node.id(), relative);
            maxId = Math.max(maxId, node.id());
            if (node.directory()) {
                if (node.size() != 0 || !node.extents().isEmpty()) {
                    throw new IOException("Directory node contains file data");
                }
                directories.add(node.id());
            } else {
                if (node.size() < 0) {
                    throw new IOException("Invalid tree file size");
                }
                long size = 0;
                for (Extent extent : node.extents()) {
                    if (extent.offset() < 0 || extent.length() <= 0 || extent.length() > dataLength
                            || extent.offset() > dataLength - extent.length()) {
                        throw new IOException("Tree extent is outside the data area");
                    }
                    size = add(size, extent.length());
                }
                if (size != node.size()) {
                    throw new IOException("Tree file size does not match its extents");
                }
            }
        }
        if (nextId <= maxId) {
            throw new IOException("Next tree node ID is not greater than allocated IDs");
        }
        return Map.copyOf(paths);
    }

    static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IOException("Unsafe tree node name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                throw new IOException("Unsafe tree node name: " + name);
            }
            if (Character.isHighSurrogate(c) && i + 1 < name.length()
                    && Character.isLowSurrogate(name.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IOException("Invalid Unicode tree node name");
            }
        }
        String base = name.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if (base.matches("CON|PRN|AUX|NUL|COM[1-9\\u00b9\\u00b2\\u00b3]|LPT[1-9\\u00b9\\u00b2\\u00b3]")) {
            throw new IOException("Unsafe reserved tree node name: " + name);
        }
    }

    static long add(long first, long second) throws IOException {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new IOException("Tree container size overflow", exception);
        }
    }

    record Extent(long offset, long length) {
    }

    record Node(long id, long parentId, String name, boolean directory, long size, List<Extent> extents) {
    }

    record Manifest(long indexLength, long dataLength, long generation, long nextId,
                    List<Node> nodes, Map<Long, Path> paths) {
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
    }
}
