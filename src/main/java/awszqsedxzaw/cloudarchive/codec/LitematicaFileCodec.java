package awszqsedxzaw.cloudarchive.codec;

import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

public final class LitematicaFileCodec {
    public static final String SCHEMATIC_EXTENSION = ".litematic";
    // Fit the 32,767-character JSON limit, including quotes, on a full Base64 quartet.
    public static final int MAX_PAGE_CHARS = 32_764;
    public static final int MAX_PAGES_PER_BOOK = 100;
    public static final int INVENTORY_SLOTS = 27;
    public static final int BYTES_PER_PAGE = MAX_PAGE_CHARS / 4 * 3;
    public static final long BYTES_PER_BOOK = (long) BYTES_PER_PAGE * MAX_PAGES_PER_BOOK;
    public static final long BYTES_PER_BARREL = BYTES_PER_BOOK * INVENTORY_SLOTS;
    public static final String MOD_TAG = "CloudArchive";

    private static final long MAX_FILE_SIZE = BYTES_PER_BOOK * Integer.MAX_VALUE;
    private static final String BOOK_ID = "minecraft:written_book";
    private static final String BARREL_ID = "minecraft:barrel";
    static final String FILE_FORMAT = "file-archive-v2";
    static final String TREE_FORMAT = "tree-archive-v1";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private LitematicaFileCodec() {
    }

    public static ArchiveInfo pack(Path input, Path output) throws IOException {
        return packPayload(input, output, input.toAbsolutePath().getFileName().toString(), FILE_FORMAT);
    }

    static ArchiveInfo packPayload(Path input, Path output, String name, String format) throws IOException {
        if (!FILE_FORMAT.equals(format) && !TREE_FORMAT.equals(format)) {
            throw new IOException("Unsupported archive format: " + format);
        }
        Path source = input.toAbsolutePath().normalize();
        Path destination = normalizeSchematicPath(output).toAbsolutePath().normalize();

        if (!Files.isRegularFile(source)) {
            throw new IOException("Input is not a regular file: " + source);
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }

        long fileSize = Files.size(source);
        int bookCount = bookCountForSize(fileSize);
        String fileName = name;
        // Hash first so only the first book needs the complete file header.
        String sha256 = sha256(source);
        NbtCompound header = new NbtCompound();
        header.putString("file_name", fileName);
        header.putLong("file_size", fileSize);
        header.putString("sha256", sha256);
        header.putInt("book_count", bookCount);

        writeCompressedAtomically(source, destination, fileSize, bookCount, header, format);
        return new ArchiveInfo(fileName, fileSize, sha256, bookCount, format);
    }

    static int bookCountForSize(long fileSize) throws IOException {
        if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
            throw new IOException("File size exceeds the archive's integer book index range");
        }
        return (int) Math.max(1, fileSize / BYTES_PER_BOOK
                + (fileSize % BYTES_PER_BOOK == 0 ? 0 : 1));
    }

    private static int barrelCountForBooks(int bookCount) {
        return (int) (((long) bookCount + INVENTORY_SLOTS - 1) / INVENTORY_SLOTS);
    }

    public static Path normalizeSchematicPath(Path output) {
        Path fileName = output.getFileName();
        if (fileName == null) {
            return output.resolveSibling(output + SCHEMATIC_EXTENSION);
        }

        String name = fileName.toString();
        if (name.endsWith(SCHEMATIC_EXTENSION)) {
            return output;
        }
        if (name.endsWith(".litematica")) {
            name = name.substring(0, name.length() - ".litematica".length());
        }
        return output.resolveSibling(name + SCHEMATIC_EXTENSION);
    }

    public static ArchiveInfo restore(Path input, Path output) throws IOException {
        return restorePayload(input, output, FILE_FORMAT);
    }

    static ArchiveInfo restorePayload(Path input, Path output, String expectedFormat) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Schematic is not a regular file: " + source);
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }

        Path temporary = createTemporaryFile(destination);
        boolean moved = false;
        try {
            ArchiveInfo info;
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                RestoreState state = new RestoreState(channel, expectedFormat);
                ArchiveBookScanner scanner = new ArchiveBookScanner(state::acceptBook);
                try {
                    NbtIo.scanCompressed(source.toFile(), scanner);
                } catch (UncheckedIOException exception) {
                    throw exception.getCause();
                }
                NbtCompound root = (NbtCompound) scanner.getRoot();
                if (root == null || !root.contains("Version", NbtElement.INT_TYPE)
                        || root.getInt("Version") < 1 || root.getInt("Version") > 6
                        || !root.contains("Regions", NbtElement.COMPOUND_TYPE)) {
                    throw new IOException("Input is not a supported Litematica schematic");
                }
                info = state.finish();
            }

            if (!sha256(temporary).equals(info.sha256())) {
                throw new IOException("Archive size or SHA-256 verification failed");
            }
            moveWithoutReplacing(temporary, destination);
            moved = true;
            return info;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static NbtCompound createSchematic(String fileName, int bookCount) {
        long now = System.currentTimeMillis();
        int barrelCount = barrelCountForBooks(bookCount);

        NbtCompound metadata = new NbtCompound();
        metadata.putString("Name", fileName);
        metadata.putString("Author", "CloudArchive");
        metadata.putString("Description", "File archive");
        metadata.putInt("RegionCount", 1);
        metadata.putInt("TotalVolume", barrelCount);
        metadata.putInt("TotalBlocks", barrelCount);
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);
        metadata.put("EnclosingSize", positionTag(barrelCount, 1, 1));

        NbtList palette = new NbtList();
        // Litematica initializes its palette with air at index zero before reading it.
        NbtCompound airState = new NbtCompound();
        airState.putString("Name", "minecraft:air");
        palette.add(airState);
        NbtCompound barrelState = new NbtCompound();
        barrelState.putString("Name", BARREL_ID);
        NbtCompound barrelProperties = new NbtCompound();
        barrelProperties.putString("facing", "north");
        barrelProperties.putString("open", "false");
        barrelState.put("Properties", barrelProperties);
        palette.add(barrelState);

        long[] blockStates = new long[(int) (((long) barrelCount + 31) / 32)];
        for (int barrelIndex = 0; barrelIndex < barrelCount; barrelIndex++) {
            blockStates[barrelIndex / 32] |= 1L << (barrelIndex % 32 * 2);
        }
        NbtCompound region = new NbtCompound();
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", blockStates);
        region.put("Entities", new NbtList());
        region.put("Position", positionTag(0, 0, 0));
        region.put("Size", positionTag(barrelCount, 1, 1));

        NbtCompound regions = new NbtCompound();
        regions.put("FileStorage", region);

        NbtCompound schematic = new NbtCompound();
        schematic.putInt("MinecraftDataVersion", 3465);
        schematic.putInt("Version", 6);
        schematic.putInt("SubVersion", 1);
        schematic.put("Metadata", metadata);
        schematic.put("Regions", regions);
        return schematic;
    }

    private static NbtCompound positionTag(int x, int y, int z) {
        NbtCompound position = new NbtCompound();
        position.putInt("x", x);
        position.putInt("y", y);
        position.putInt("z", z);
        return position;
    }

    private static byte[] decodePage(String page) throws IOException {
        for (int i = 0; i < page.length(); i++) {
            char character = page.charAt(i);
            boolean valid = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_';
            if (!valid) {
                throw new IOException("Archive page contains invalid Base64 data");
            }
        }

        try {
            return BASE64_DECODER.decode(page);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Archive page contains invalid Base64 data", exception);
        }
    }

    private static void writeCompressedAtomically(
            Path source, Path destination, long fileSize, int bookCount, NbtCompound header, String format
    ) throws IOException {
        Path temporary = createTemporaryFile(destination);
        boolean moved = false;
        try {
            MessageDigest digest = newSha256();
            try (InputStream stream = Files.newInputStream(source);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                         new GZIPOutputStream(Files.newOutputStream(temporary), 64 * 1024)))) {
                NbtCompound schematic = createSchematic(header.getString("file_name"), bookCount);
                writeCompoundStart(output, "");
                for (String key : schematic.getKeys()) {
                    if (!key.equals("Regions")) {
                        writeTag(output, key, schematic.get(key));
                    }
                }
                writeCompoundStart(output, "Regions");
                writeCompoundStart(output, "FileStorage");
                NbtCompound region = schematic.getCompound("Regions").getCompound("FileStorage");
                for (String key : region.getKeys()) {
                    writeTag(output, key, region.get(key));
                }
                int barrelCount = barrelCountForBooks(bookCount);
                writeListStart(output, "TileEntities", NbtElement.COMPOUND_TYPE, barrelCount);
                int bookIndex = 0;
                for (int barrelIndex = 0; barrelIndex < barrelCount; barrelIndex++) {
                    writeTag(output, "id", NbtString.of(BARREL_ID));
                    NbtCompound position = positionTag(barrelIndex, 0, 0);
                    for (String key : position.getKeys()) {
                        writeTag(output, key, position.get(key));
                    }
                    int booksInBarrel = Math.min(INVENTORY_SLOTS, bookCount - bookIndex);
                    writeListStart(output, "Items", NbtElement.COMPOUND_TYPE, booksInBarrel);
                    for (int slot = 0; slot < booksInBarrel; slot++, bookIndex++) {
                        long bookSize = Math.min(BYTES_PER_BOOK, fileSize - bookIndex * BYTES_PER_BOOK);
                        createBook(stream, bookSize, bookIndex, slot, header, digest, format).write(output);
                    }
                    output.writeByte(NbtElement.END_TYPE);
                }
                // Close FileStorage, Regions and the root; NBT lists have no end marker.
                output.writeByte(NbtElement.END_TYPE);
                output.writeByte(NbtElement.END_TYPE);
                output.writeByte(NbtElement.END_TYPE);
                if (stream.read() != -1
                        || !HexFormat.of().formatHex(digest.digest()).equals(header.getString("sha256"))) {
                    throw new IOException("Input changed while it was being packed");
                }
            }
            moveWithoutReplacing(temporary, destination);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static NbtCompound createBook(
            InputStream stream, long bookSize, int index, int slot,
            NbtCompound header, MessageDigest digest, String format
    ) throws IOException {
        NbtList pages = new NbtList();
        while (bookSize > 0) {
            int chunkSize = (int) Math.min(bookSize, BYTES_PER_PAGE);
            byte[] chunk = stream.readNBytes(chunkSize);
            if (chunk.length != chunkSize) {
                throw new IOException("Input changed while it was being packed");
            }
            digest.update(chunk);
            pages.add(NbtString.of(BASE64_ENCODER.encodeToString(chunk)));
            bookSize -= chunkSize;
        }
        if (pages.isEmpty()) {
            pages.add(NbtString.of(""));
        }

        NbtCompound marker = new NbtCompound();
        marker.putString("kind", format);
        marker.putInt("index", index);
        if (index == 0) {
            marker.put("header", header);
        }
        NbtCompound itemTag = new NbtCompound();
        itemTag.putString("author", "CloudArchive");
        itemTag.putString("title", "Archive " + (index + 1));
        itemTag.putInt("generation", 0);
        itemTag.putBoolean("resolved", true);
        itemTag.put("pages", pages);
        itemTag.put(MOD_TAG, marker);

        NbtCompound stack = new NbtCompound();
        stack.putString("id", BOOK_ID);
        stack.putByte("Count", (byte) 1);
        stack.putByte("Slot", (byte) slot);
        stack.put("tag", itemTag);
        return stack;
    }

    private static void writeTag(DataOutput output, String name, NbtElement value) throws IOException {
        output.writeByte(value.getType());
        output.writeUTF(name);
        value.write(output);
    }

    private static void writeCompoundStart(DataOutput output, String name) throws IOException {
        output.writeByte(NbtElement.COMPOUND_TYPE);
        output.writeUTF(name);
    }

    private static void writeListStart(
            DataOutput output, String name, int entryType, int length
    ) throws IOException {
        output.writeByte(NbtElement.LIST_TYPE);
        output.writeUTF(name);
        output.writeByte(entryType);
        output.writeInt(length);
    }

    private static Path createTemporaryFile(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent directory");
        }
        Files.createDirectories(parent);
        return Files.createTempFile(parent, ".cloudarchive-archive-", ".tmp");
    }

    private static void moveWithoutReplacing(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination);
        } catch (FileAlreadyExistsException exception) {
            throw new FileAlreadyExistsException(destination.toString());
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class RestoreState {
        private final FileChannel channel;
        private final String expectedFormat;
        private final Map<Integer, Long> bookSizes = new HashMap<>();
        private NbtCompound header;
        private String format;

        private RestoreState(FileChannel channel, String expectedFormat) {
            this.channel = channel;
            this.expectedFormat = expectedFormat;
        }

        private void acceptBook(NbtCompound stack) throws IOException {
            NbtCompound tag = stack.getCompound("tag");
            if (!BOOK_ID.equals(stack.getString("id"))
                    || !tag.contains(MOD_TAG, NbtElement.COMPOUND_TYPE)) {
                return;
            }
            NbtCompound marker = tag.getCompound(MOD_TAG);
            String bookFormat = marker.getString("kind");
            if (!FILE_FORMAT.equals(bookFormat) && !TREE_FORMAT.equals(bookFormat)) {
                throw new IOException("Archive book uses an unsupported format: " + bookFormat);
            }
            if (expectedFormat != null && !expectedFormat.equals(bookFormat)) {
                throw new IOException("Archive type does not match the requested operation");
            }
            if (format != null && !format.equals(bookFormat)) {
                throw new IOException("Archive contains mixed file and tree formats");
            }
            format = bookFormat;
            int index = marker.getInt("index");
            if (!marker.contains("index", NbtElement.INT_TYPE)
                    || index < 0 || index == Integer.MAX_VALUE || bookSizes.containsKey(index)) {
                throw new IOException("Archive contains an invalid or duplicate book index");
            }
            if (index == 0) {
                if (!marker.contains("header", NbtElement.COMPOUND_TYPE)) {
                    throw new IOException("Archive header is missing");
                }
                header = marker.getCompound("header");
            } else if (marker.contains("header")) {
                throw new IOException("Archive contains more than one file header");
            }

            NbtList pages = tag.getList("pages", NbtElement.STRING_TYPE);
            if (pages.isEmpty() || pages.size() > MAX_PAGES_PER_BOOK) {
                throw new IOException("Archive book " + (index + 1) + " has an invalid page count");
            }
            // Indices determine file offsets, so world position and inventory order may change.
            channel.position(index * BYTES_PER_BOOK);
            long bookSize = 0;
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                String page = pages.getString(pageIndex);
                if (page.length() > MAX_PAGE_CHARS) {
                    throw new IOException("Archive page exceeds the supported page capacity");
                }
                byte[] chunk = decodePage(page);
                bookSize += chunk.length;
                ByteBuffer buffer = ByteBuffer.wrap(chunk);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            bookSizes.put(index, bookSize);
        }

        private ArchiveInfo finish() throws IOException {
            if (bookSizes.isEmpty()) {
                throw new IOException("No CloudArchive file archive was found in the schematic");
            }
            if (header == null) {
                throw new IOException("Archive header book is missing");
            }
            long fileSize = header.getLong("file_size");
            int bookCount = header.getInt("book_count");
            String hash = header.getString("sha256");
            if (!header.contains("file_name", NbtElement.STRING_TYPE)
                    || !header.contains("file_size", NbtElement.LONG_TYPE)
                    || !header.contains("book_count", NbtElement.INT_TYPE)
                    || fileSize < 0 || fileSize > MAX_FILE_SIZE
                    || bookCount != bookCountForSize(fileSize) || bookCount != bookSizes.size()
                    || !hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Archive metadata is invalid or books are missing");
            }
            for (int index = 0; index < bookCount; index++) {
                Long size = bookSizes.get(index);
                long expected = Math.min(BYTES_PER_BOOK, fileSize - index * BYTES_PER_BOOK);
                if (size == null || size != expected) {
                    throw new IOException("Archive book " + (index + 1) + " is missing or incomplete");
                }
            }
            if (channel.size() != fileSize) {
                throw new IOException("Archive size verification failed");
            }
            return new ArchiveInfo(header.getString("file_name"), fileSize, hash, bookCount, format);
        }
    }

    public record ArchiveInfo(String fileName, long fileSize, String sha256, int bookCount, String format) {
        public int barrelCount() {
            return barrelCountForBooks(bookCount);
        }
    }
}
