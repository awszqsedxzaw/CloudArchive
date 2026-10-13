package awszqsedxzaw.cloudarchive.codec;

import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
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
    static final long MAX_ARCHIVE_BYTES = BYTES_PER_BOOK * Integer.MAX_VALUE;
    static final String BOOK_ID = "minecraft:written_book";
    static final String BARREL_ID = "minecraft:barrel";
    private static final String AUTHOR = "CA";
    private static final String METADATA_TITLE = "M";
    private static final String DATA_TITLE = "D";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final int[] BASE64_VALUES = createBase64Values();

    private LitematicaFileCodec() {
    }

    public static ArchiveInfo pack(Path input, Path output) throws IOException {
        return FileTreeCodec.packSingleFile(input, output).archive();
    }

    public static ArchiveInfo restore(Path input, Path output) throws IOException {
        return FileTreeCodec.restoreSingle(input, output).archive();
    }

    static ArchiveInfo packArchive(
            Path metadata,
            Path data,
            Path destination,
            TreeArchiveFormat.ArchiveKind expectedKind
    ) throws IOException {
        Path metadataPath = metadata.toAbsolutePath().normalize();
        Path dataPath = data.toAbsolutePath().normalize();
        Path schematic = normalizeSchematicPath(destination).toAbsolutePath().normalize();
        if (!Files.isRegularFile(metadataPath) || !Files.isRegularFile(dataPath)) {
            throw new IOException("Archive staging files are not regular files");
        }
        if (Files.exists(schematic, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(schematic.toString());
        }
        TreeArchiveFormat.Manifest manifest = TreeArchiveFormat.read(metadataPath);
        if (manifest.kind() != expectedKind || manifest.storedLength() != Files.size(dataPath)) {
            throw new IOException("Archive staging metadata and data do not match");
        }
        DataCompression.decode(dataPath, java.io.OutputStream.nullOutputStream(), manifest);
        int metadataBooks = TreeArchiveFormat.metadataBookCount(Files.size(metadataPath));
        if (metadataBooks > INVENTORY_SLOTS) {
            throw new IOException("Metadata needs more than one barrel");
        }
        int dataBooks = manifest.dataBookCount();
        int barrelCount = 1 + barrelCountForBooks(dataBooks);
        writeCompressedAtomically(metadataPath, dataPath, schematic, manifest.archiveName(),
                metadataBooks, dataBooks, barrelCount);
        return new ArchiveInfo(manifest.archiveName(), manifest.dataLength(), manifest.dataSha256(),
                metadataBooks, dataBooks, barrelCount, manifest.kind(), manifest.storedLength());
    }

    static DecodedArchive restoreTransport(
            Path input,
            Path metadataOutput,
            Path dataOutput
    ) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Schematic is not a regular file: " + source);
        }
        Path storedOutput = Files.createTempFile(dataOutput.toAbsolutePath().getParent(), ".cloudarchive-data-", ".tmp");
        try {
            TreeArchiveFormat.Manifest manifest;
            try (FileChannel metadata = FileChannel.open(metadataOutput,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 FileChannel data = FileChannel.open(storedOutput,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                RestoreState state = new RestoreState(metadata, data);
                ArchiveScanner scanner = new ArchiveScanner(state::acceptBook);
                try {
                    net.minecraft.nbt.NbtIo.scanCompressed(source.toFile(), scanner);
                } catch (java.io.UncheckedIOException exception) {
                    throw exception.getCause();
                }
                ArchiveScanner.Layout layout = scanner.finish();
                scanner.emitBooks(layout);
                manifest = state.finish(metadataOutput, storedOutput, layout.barrelCount());
            }
            try (java.io.OutputStream output = new BufferedOutputStream(Files.newOutputStream(dataOutput,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                DataCompression.decode(storedOutput, output, manifest);
            }
            ArchiveInfo info = new ArchiveInfo(manifest.archiveName(), manifest.dataLength(),
                    manifest.dataSha256(), TreeArchiveFormat.metadataBookCount(Files.size(metadataOutput)),
                    manifest.dataBookCount(), 1 + barrelCountForBooks(manifest.dataBookCount()), manifest.kind(),
                    manifest.storedLength());
            return new DecodedArchive(manifest, info);
        } finally {
            Files.deleteIfExists(storedOutput);
        }
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

    static int barrelCountForBooks(int bookCount) {
        return (int) (((long) bookCount + INVENTORY_SLOTS - 1) / INVENTORY_SLOTS);
    }

    static NbtCompound createSchematic(String name, int barrelCount) {
        long now = System.currentTimeMillis();
        NbtCompound metadata = new NbtCompound();
        metadata.putString("Name", name);
        metadata.putString("Author", AUTHOR);
        metadata.putString("Description", "");
        metadata.putInt("RegionCount", 1);
        metadata.putInt("TotalVolume", barrelCount);
        metadata.putInt("TotalBlocks", barrelCount);
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);
        metadata.put("EnclosingSize", positionTag(barrelCount, 1, 1));

        NbtList palette = new NbtList();
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

        long[] blockStates = new long[(int) (((long) barrelCount * 2 + 63) / 64)];
        for (int barrelIndex = 0; barrelIndex < barrelCount; barrelIndex++) {
            int bitOffset = barrelIndex * 2;
            blockStates[bitOffset / Long.SIZE] |= 1L << (bitOffset % Long.SIZE);
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

    private static void writeCompressedAtomically(
            Path metadata,
            Path data,
            Path destination,
            String archiveName,
            int metadataBookCount,
            int dataBookCount,
            int barrelCount
    ) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".cloudarchive-archive-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel metadataChannel = FileChannel.open(metadata, StandardOpenOption.READ);
                 FileChannel dataChannel = FileChannel.open(data, StandardOpenOption.READ);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                         new ParallelGzipOutputStream(Files.newOutputStream(temporary))))) {
                NbtCompound schematic = createSchematic(archiveName, barrelCount);
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
                writeListStart(output, "TileEntities", NbtElement.COMPOUND_TYPE, barrelCount);
                for (int barrelIndex = 0; barrelIndex < barrelCount; barrelIndex++) {
                    writeTag(output, "id", NbtString.of(BARREL_ID));
                    NbtCompound position = positionTag(barrelIndex, 0, 0);
                    for (String key : position.getKeys()) {
                        writeTag(output, key, position.get(key));
                    }
                    int firstBook = barrelIndex == 0 ? 0 : (barrelIndex - 1) * INVENTORY_SLOTS;
                    int availableBooks = barrelIndex == 0
                            ? metadataBookCount
                            : Math.min(INVENTORY_SLOTS, dataBookCount - firstBook);
                    writeListStart(output, "Items", NbtElement.COMPOUND_TYPE, availableBooks);
                    for (int slot = 0; slot < availableBooks; slot++) {
                        int book = firstBook + slot;
                        if (barrelIndex == 0) {
                            writeBook(output, metadataChannel, book * BYTES_PER_BOOK,
                                    Math.min(BYTES_PER_BOOK, metadataChannel.size() - book * BYTES_PER_BOOK),
                                    slot, METADATA_TITLE);
                        } else {
                            writeBook(output, dataChannel, book * BYTES_PER_BOOK,
                                    Math.min(BYTES_PER_BOOK, dataChannel.size() - book * BYTES_PER_BOOK),
                                    slot, DATA_TITLE);
                        }
                    }
                    output.writeByte(NbtElement.END_TYPE);
                }
                output.writeByte(NbtElement.END_TYPE);
                output.writeByte(NbtElement.END_TYPE);
                output.writeByte(NbtElement.END_TYPE);
            }
            moveWithoutReplacing(temporary, destination);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void writeBook(
            DataOutput output,
            FileChannel source,
            long offset,
            long length,
            int slot,
            String title
    ) throws IOException {
        if (length <= 0 || length > BYTES_PER_BOOK) {
            throw new IOException("Archive book has an invalid length");
        }
        writeTag(output, "id", NbtString.of(BOOK_ID));
        writeTag(output, "Count", NbtByte.of((byte) 1));
        writeTag(output, "Slot", NbtByte.of((byte) slot));
        writeCompoundStart(output, "tag");
        writeTag(output, "author", NbtString.of(AUTHOR));
        writeTag(output, "title", NbtString.of(title));
        writeTag(output, "resolved", NbtByte.of((byte) 1));
        int pageCount = (int) ((length + BYTES_PER_PAGE - 1) / BYTES_PER_PAGE);
        writeListStart(output, "pages", NbtElement.STRING_TYPE, pageCount);
        source.position(offset);
        byte[] chunk = new byte[(int) Math.min(length, BYTES_PER_PAGE)];
        ByteBuffer buffer = ByteBuffer.wrap(chunk);
        byte[] page = new byte[(chunk.length * 4 + 2) / 3];
        long remaining = length;
        while (remaining > 0) {
            int chunkSize = (int) Math.min(remaining, BYTES_PER_PAGE);
            buffer.clear().limit(chunkSize);
            readFully(source, buffer);
            byte[] input = chunkSize == chunk.length ? chunk : Arrays.copyOf(chunk, chunkSize);
            int pageLength = BASE64_ENCODER.encode(input, page);
            // Base64 ASCII is identical to its NBT modified UTF-8 representation.
            output.writeShort(pageLength);
            output.write(page, 0, pageLength);
            remaining -= chunkSize;
        }
        output.writeByte(NbtElement.END_TYPE);
        output.writeByte(NbtElement.END_TYPE);
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

    private static void writeListStart(DataOutput output, String name, int entryType, int length)
            throws IOException {
        output.writeByte(NbtElement.LIST_TYPE);
        output.writeUTF(name);
        output.writeByte(entryType);
        output.writeInt(length);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                throw new IOException("Archive staging data is truncated");
            }
        }
    }

    private static NbtCompound positionTag(int x, int y, int z) {
        NbtCompound position = new NbtCompound();
        position.putInt("x", x);
        position.putInt("y", y);
        position.putInt("z", z);
        return position;
    }

    private static byte[] decodePage(String page) throws IOException {
        if (page.length() > MAX_PAGE_CHARS) {
            throw new IOException("Archive page exceeds the supported page capacity");
        }
        int last = -1;
        for (int i = 0; i < page.length(); i++) {
            char character = page.charAt(i);
            last = character < BASE64_VALUES.length ? BASE64_VALUES[character] : -1;
            if (last < 0) {
                throw new IOException("Archive page contains invalid Base64 data");
            }
        }
        try {
            int remainder = page.length() & 3;
            if (page.isEmpty() || remainder == 1
                    || remainder == 2 && (last & 15) != 0 || remainder == 3 && (last & 3) != 0) {
                throw new IOException("Archive page contains empty or noncanonical Base64 data");
            }
            return BASE64_DECODER.decode(page);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Archive page contains invalid Base64 data", exception);
        }
    }

    private static int[] createBase64Values() {
        int[] values = new int[128];
        Arrays.fill(values, -1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int i = 0; i < alphabet.length(); i++) {
            values[alphabet.charAt(i)] = i;
        }
        return values;
    }

    private static NbtList readBook(NbtCompound stack, String expectedTitle) throws IOException {
        if (!stack.getKeys().equals(Set.of("id", "Count", "Slot", "tag"))
                || !BOOK_ID.equals(stack.getString("id"))
                || !stack.contains("Count", NbtElement.BYTE_TYPE)
                || stack.getByte("Count") != 1
                || !stack.contains("Slot", NbtElement.BYTE_TYPE)
                || (stack.getByte("Slot") & 0xFF) >= INVENTORY_SLOTS
                || !stack.contains("tag", NbtElement.COMPOUND_TYPE)) {
            throw new IOException("Archive book item NBT is invalid");
        }
        NbtCompound tag = stack.getCompound("tag");
        if (!tag.getKeys().equals(Set.of("author", "title", "resolved", "pages"))
                || !tag.contains("author", NbtElement.STRING_TYPE)
                || !tag.contains("title", NbtElement.STRING_TYPE)
                || !tag.contains("resolved", NbtElement.BYTE_TYPE)
                || !tag.contains("pages", NbtElement.LIST_TYPE)
                || !AUTHOR.equals(tag.getString("author"))
                || !expectedTitle.equals(tag.getString("title"))
                || !tag.getBoolean("resolved")) {
            throw new IOException("Archive book uses unsupported or invalid written-book fields");
        }
        NbtList pages = tag.getList("pages", NbtElement.STRING_TYPE);
        if (pages.isEmpty() || pages.size() > MAX_PAGES_PER_BOOK) {
            throw new IOException("Archive book has an invalid page count");
        }
        return pages;
    }

    private static long writePages(FileChannel channel, NbtList pages, long offset) throws IOException {
        long position = offset;
        for (int i = 0; i < pages.size(); i++) {
            byte[] bytes = decodePage(pages.getString(i));
            if (i < pages.size() - 1 && bytes.length != BYTES_PER_PAGE) {
                throw new IOException("Archive book contains an incomplete non-final page");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            channel.position(position);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            position += bytes.length;
        }
        return position - offset;
    }

    private static void moveWithoutReplacing(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination);
        } catch (FileAlreadyExistsException exception) {
            throw new FileAlreadyExistsException(destination.toString());
        }
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (java.io.InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class RestoreState {
        private final FileChannel metadata;
        private final FileChannel data;
        private final Map<Integer, Long> metadataBooks = new HashMap<>();
        private final Map<Integer, Long> dataBooks = new HashMap<>();

        private RestoreState(FileChannel metadata, FileChannel data) {
            this.metadata = metadata;
            this.data = data;
        }

        private void acceptBook(ArchiveScanner.ArchiveBook archiveBook) throws IOException {
            if (archiveBook.y() != 0 || archiveBook.z() != 0) {
                throw new IOException("Archive barrel is outside the supported layout");
            }
            if (archiveBook.x() < 0) {
                throw new IOException("Archive barrel has an invalid X coordinate");
            }
            String title = archiveBook.x() == 0 ? METADATA_TITLE : DATA_TITLE;
            NbtList pages = readBook(archiveBook.stack(), title);
            int slot = archiveBook.stack().getByte("Slot") & 0xFF;
            if (archiveBook.x() == 0) {
                if (!metadataBooks.containsKey(slot)) {
                    metadataBooks.put(slot, writePages(metadata, pages, (long) slot * BYTES_PER_BOOK));
                } else {
                    throw new IOException("Archive metadata contains duplicate inventory slots");
                }
            } else {
                long index = ((long) archiveBook.x() - 1) * INVENTORY_SLOTS + slot;
                if (index > Integer.MAX_VALUE) {
                    throw new IOException("Archive data book index is too large");
                }
                int dataIndex = (int) index;
                if (!dataBooks.containsKey(dataIndex)) {
                    dataBooks.put(dataIndex, writePages(data, pages, index * BYTES_PER_BOOK));
                } else {
                    throw new IOException("Archive data contains duplicate book positions");
                }
            }
        }

        private TreeArchiveFormat.Manifest finish(Path metadataPath, Path dataPath, int barrelCount)
                throws IOException {
            if (metadataBooks.isEmpty()) {
                throw new IOException("Archive metadata barrel is missing");
            }
            TreeArchiveFormat.Manifest manifest = TreeArchiveFormat.read(metadataPath);
            if (barrelCount != 1 + barrelCountForBooks(manifest.dataBookCount())) {
                throw new IOException("Archive barrel count does not match metadata");
            }
            int expectedMetadataBooks = TreeArchiveFormat.metadataBookCount(Files.size(metadataPath));
            if (metadataBooks.size() != expectedMetadataBooks) {
                throw new IOException("Archive metadata books are missing");
            }
            long metadataLength = Files.size(metadataPath);
            for (int index = 0; index < expectedMetadataBooks; index++) {
                Long size = metadataBooks.get(index);
                long expected = Math.min(BYTES_PER_BOOK, metadataLength - index * BYTES_PER_BOOK);
                if (size == null || size != expected) {
                    throw new IOException("Archive metadata book is missing or incomplete");
                }
            }
            if (dataBooks.size() != manifest.dataBookCount()) {
                throw new IOException("Archive data books are missing");
            }
            for (int index = 0; index < manifest.dataBookCount(); index++) {
                Long size = dataBooks.get(index);
                long expected = Math.min(BYTES_PER_BOOK,
                        manifest.storedLength() - index * BYTES_PER_BOOK);
                if (size == null || size != expected) {
                    throw new IOException("Archive data book is missing or incomplete");
                }
            }
            if (Files.size(dataPath) != manifest.storedLength()) {
                throw new IOException("Archive stored data size verification failed");
            }
            return manifest;
        }
    }

    record DecodedArchive(TreeArchiveFormat.Manifest manifest, ArchiveInfo info) {
    }

    public record ArchiveInfo(
            String archiveName,
            long dataSize,
            String sha256,
            int metadataBookCount,
            int dataBookCount,
            int barrelCount,
            TreeArchiveFormat.ArchiveKind kind,
            long storedSize
    ) {
        public int totalBookCount() {
            return metadataBookCount + dataBookCount;
        }

    }
}
