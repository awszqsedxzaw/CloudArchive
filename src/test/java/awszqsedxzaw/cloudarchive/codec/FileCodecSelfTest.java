package awszqsedxzaw.cloudarchive.codec;

import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

public final class FileCodecSelfTest {
    private FileCodecSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("cloudarchive-codec-test-");
        try {
            verifyPageDecoder();
            roundTrip(work, "empty.bin", 0);
            roundTrip(work, "exact-page.bin", LitematicaFileCodec.BYTES_PER_PAGE);
            roundTrip(work, "unicode-\u6570\u636e.bin", LitematicaFileCodec.BYTES_PER_PAGE + 17);
            roundTrip(work, "exact-book.bin", (int) LitematicaFileCodec.BYTES_PER_BOOK);
            roundTrip(work, "cross-book.bin", (int) LitematicaFileCodec.BYTES_PER_BOOK + 31);
            verifyPageCapacity(work);
            verifyMalformedBooksAndLayout(work);
            verifyMetadataCorruption(work);
            verifyOrderIndependence(work);
            verifyStreamIntegrity(work);
            verifyExtensionsAndStateBoundary(work);
            verifyMultiBarrel(work);
            TreeCodecSelfTest.run(work);
            CompressionSelfTest.run(work);
            try (Stream<Path> children = Files.list(work)) {
                require(children.noneMatch(path -> path.getFileName().toString().startsWith(".cloudarchive-")),
                        "Temporary restore directories leaked");
            }
            System.out.println("All archive checks passed (128 MiB heap)");
        } finally {
            deleteTree(work);
        }
    }

    private static void verifyPageDecoder() throws Exception {
        Method decoder = LitematicaFileCodec.class.getDeclaredMethod("decodePage", String.class);
        decoder.setAccessible(true);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (int character = 0; character <= Character.MAX_VALUE; character++) {
            String page = "AA" + (char) character + "A";
            boolean supported = alphabet.indexOf(character) >= 0;
            try {
                byte[] bytes = (byte[]) decoder.invoke(null, page);
                require(supported && Arrays.equals(bytes, Base64.getUrlDecoder().decode(page)),
                        "Page decoder accepted an unsupported character");
            } catch (InvocationTargetException exception) {
                require(!supported && exception.getCause() instanceof IOException,
                        "Page decoder rejected a supported character");
            }
        }
        for (String prefix : new String[]{"A", "AA"}) {
            for (int i = 0; i < alphabet.length(); i++) {
                String page = prefix + alphabet.charAt(i);
                byte[] bytes = Base64.getUrlDecoder().decode(page);
                boolean canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(page);
                try {
                    byte[] actual = (byte[]) decoder.invoke(null, page);
                    require(canonical && Arrays.equals(bytes, actual), "Noncanonical page tail accepted");
                } catch (InvocationTargetException exception) {
                    require(!canonical && exception.getCause() instanceof IOException, "Canonical page tail rejected");
                }
            }
        }
        System.out.println("All UTF-16 characters and Base64 tail canonicality checks passed");
    }

    private static void roundTrip(Path work, String name, int size) throws IOException {
        Path input = work.resolve(name);
        byte[] bytes = randomBytes(size, size + 5L);
        Files.write(input, bytes);
        Path archive = work.resolve(name + ".litematic");
        LitematicaFileCodec.ArchiveInfo packed = LitematicaFileCodec.pack(input, archive);
        int dataBooks = TreeArchiveFormat.dataBookCount(size);
        require(packed.dataSize() == size && packed.dataBookCount() == dataBooks,
                "Single-file data statistics differ");
        require(packed.metadataBookCount() == 1
                && packed.barrelCount() == 1 + LitematicaFileCodec.barrelCountForBooks(dataBooks),
                "Metadata barrel was not counted separately");
        Path restored = work.resolve(name + ".restored");
        LitematicaFileCodec.ArchiveInfo result = LitematicaFileCodec.restore(archive, restored);
        require(result.archiveName().equals(name) && result.kind() == TreeArchiveFormat.ArchiveKind.FILE,
                "Single-file metadata differs");
        require(Files.mismatch(input, restored) == -1, "Single-file round trip differs");
        NbtCompound root = NbtIo.readCompressed(archive.toFile());
        verifySchematic(root, packed.barrelCount());
        byte[] decodedMetadata = decodeBooks(items(root, 0));
        Path metadata = work.resolve(name + ".metadata");
        Files.write(metadata, decodedMetadata);
        TreeArchiveFormat.Manifest manifest = TreeArchiveFormat.read(metadata);
        require(manifest.nodes().size() == 1 && manifest.dataLength() == size,
                "File metadata was not stored in the metadata barrel");
        ByteArrayOutputStream pure = new ByteArrayOutputStream();
        for (int x = 1; x < packed.barrelCount(); x++) {
            pure.write(decodeBooks(items(root, x)));
        }
        require(Arrays.equals(bytes, pure.toByteArray()), "Data books contain non-file bytes");
        expectFailure(() -> LitematicaFileCodec.pack(input, archive), "Existing schematic was overwritten");
        expectFailure(() -> LitematicaFileCodec.restore(archive, restored), "Existing file was overwritten");
        require(Files.mismatch(input, restored) == -1, "Rejected overwrite changed bytes");
    }

    static void verifySchematic(NbtCompound root, int barrelCount) {
        require(root.getInt("Version") == 6 && root.getInt("SubVersion") == 1
                && root.getInt("MinecraftDataVersion") == 3465, "Incorrect schematic versions");
        NbtCompound region = region(root);
        NbtCompound metadata = root.getCompound("Metadata");
        require(region.getCompound("Size").getInt("x") == barrelCount
                && region.getCompound("Size").getInt("y") == 1
                && region.getCompound("Size").getInt("z") == 1
                && metadata.getInt("TotalBlocks") == barrelCount
                && metadata.getInt("TotalVolume") == barrelCount
                && metadata.getCompound("EnclosingSize").getInt("x") == barrelCount,
                "Schematic dimensions differ");
        NbtList palette = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
        require(palette.size() == 2 && palette.getCompound(0).getString("Name").equals("minecraft:air")
                && palette.getCompound(1).getString("Name").equals("minecraft:barrel"),
                "Litematica air/barrel palette differs");
        NbtCompound properties = palette.getCompound(1).getCompound("Properties");
        require(properties.getString("facing").equals("north") && properties.getString("open").equals("false"),
                "Barrel state properties differ");
        long[] states = region.getLongArray("BlockStates");
        require(states.length == (barrelCount * 2L + 63) / 64, "Block state array size differs");
        for (int x = 0; x < barrelCount; x++) {
            require((states[x / 32] >>> (x % 32 * 2) & 3) == 1, "A barrel decodes as another block");
        }
        NbtList barrels = region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
        require(barrels.size() == barrelCount, "Barrel entity count differs");
        for (int x = 0; x < barrelCount; x++) {
            NbtCompound barrel = barrels.getCompound(x);
            require(barrel.getString("id").equals("minecraft:barrel") && barrel.getInt("x") == x
                    && barrel.getInt("y") == 0 && barrel.getInt("z") == 0, "Barrel position differs");
            NbtList books = items(root, x);
            require(!books.isEmpty() && books.size() <= 27, "Invalid inventory capacity");
            Set<Integer> slots = new HashSet<>();
            for (int i = 0; i < books.size(); i++) {
                NbtCompound book = books.getCompound(i);
                verifyBook(book, x == 0);
                require(slots.add(book.getByte("Slot") & 255), "Duplicate slot");
                require(book.getByte("Slot") == i, "Writer did not fill slots sequentially");
            }
        }
    }

    static void verifyBook(NbtCompound book, boolean metadata) {
        require(book.getKeys().equals(Set.of("id", "Count", "Slot", "tag")), "Custom item fields found");
        require(book.getString("id").equals("minecraft:written_book") && book.getByte("Count") == 1,
                "Invalid written-book stack");
        NbtCompound tag = book.getCompound("tag");
        require(tag.getKeys().equals(Set.of("author", "title", "resolved", "pages")),
                "Custom book tags found");
        require(tag.getString("author").equals("CA")
                && tag.getString("title").equals(metadata ? "M" : "D")
                && tag.getInt("generation") == 0 && tag.getBoolean("resolved"), "Native book fields differ");
        NbtList pages = tag.getList("pages", NbtElement.STRING_TYPE);
        require(!pages.isEmpty() && pages.size() <= 100, "Invalid page count");
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.getString(i);
            require(page.length() <= LitematicaFileCodec.MAX_PAGE_CHARS
                    && new JsonPrimitive(page).toString().length() <= 32_767, "Page exceeds capacity");
            require(Base64.getUrlDecoder().decode(page).length <= LitematicaFileCodec.BYTES_PER_PAGE,
                    "Page exceeds decoded capacity");
        }
    }

    private static void verifyPageCapacity(Path work) throws IOException {
        NbtCompound root = NbtIo.readCompressed(work.resolve("exact-page.bin.litematic").toFile());
        String page = pages(items(root, 1).getCompound(0)).getString(0);
        require(page.length() == 32_764 && new JsonPrimitive(page).toString().length() == 32_766,
                "Full-page encoded length differs");
        require(Base64.getUrlDecoder().decode(page).length == 24_573, "Full-page data length differs");
        mutateAndReject(work, "oversized-page", root, changed ->
                pages(items(changed, 1).getCompound(0)).set(0, NbtString.of("A".repeat(32_765))));
        System.out.println("Page capacity and native book NBT checks passed");
    }

    private static void verifyMalformedBooksAndLayout(Path work) throws IOException {
        NbtCompound valid = NbtIo.readCompressed(work.resolve("cross-book.bin.litematic").toFile());
        mutateAndReject(work, "missing-metadata", valid, root -> items(root, 0).clear());
        mutateAndReject(work, "missing-data", valid, root -> items(root, 1).remove(1));
        mutateAndReject(work, "duplicate-slot", valid, root ->
                items(root, 1).getCompound(1).putByte("Slot", (byte) 0));
        mutateAndReject(work, "moved-slot", valid, root ->
                items(root, 1).getCompound(1).putByte("Slot", (byte) 13));
        mutateAndReject(work, "non-contiguous-barrels", valid, root ->
                region(root).getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(1).putInt("x", 2));
        mutateAndReject(work, "duplicate-barrel", valid, root ->
                region(root).getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(1).putInt("x", 0));
        mutateAndReject(work, "foreign-slot", valid, root ->
                items(root, 1).getCompound(0).putByte("Slot", (byte) 27));
        mutateAndReject(work, "wrong-item", valid, root ->
                items(root, 1).getCompound(0).putString("id", "minecraft:stone"));
        mutateAndReject(work, "extra-item-field", valid, root ->
                items(root, 1).getCompound(0).putInt("custom", 1));
        mutateAndReject(work, "empty-pages", valid, root ->
                pages(items(root, 1).getCompound(0)).clear());
        mutateAndReject(work, "too-many-pages", valid, root ->
                pages(items(root, 1).getCompound(0)).add(NbtString.of("AQ")));
        mutateAndReject(work, "invalid-base64", valid, root ->
                pages(items(root, 1).getCompound(1)).set(0, NbtString.of("%%")));
        mutateAndReject(work, "partial-non-final-page", valid, root ->
                pages(items(root, 1).getCompound(0)).set(0, NbtString.of("AQ")));
        mutateAndReject(work, "corrupt-data", valid, root -> {
            NbtList pages = pages(items(root, 1).getCompound(1));
            byte[] data = Base64.getUrlDecoder().decode(pages.getString(0));
            data[0] ^= 1;
            pages.set(0, NbtString.of(Base64.getUrlEncoder().withoutPadding().encodeToString(data)));
        });
        for (String marker : new String[]{"CloudArchive", "newproject", "BlockEntityTag"}) {
            mutateAndReject(work, "custom-" + marker, valid, root ->
                    items(root, 1).getCompound(0).getCompound("tag").put(marker, new NbtCompound()));
        }
        for (String oldKind : new String[]{"file-archive-v1", "file-archive-v2", "tree-archive-v1"}) {
            mutateAndReject(work, "old-" + oldKind, valid, root -> {
                NbtCompound marker = new NbtCompound();
                marker.putString("kind", oldKind);
                marker.putInt("index", 0);
                items(root, 0).getCompound(0).getCompound("tag").put("CloudArchive", marker);
            });
        }
    }

    private static void verifyMetadataCorruption(Path work) throws IOException {
        NbtCompound valid = NbtIo.readCompressed(work.resolve("exact-page.bin.litematic").toFile());
        for (int test = 0; test < 13; test++) {
            int scenario = test;
            mutateAndReject(work, "bad-metadata-" + test, valid, root -> {
                NbtList pages = pages(items(root, 0).getCompound(0));
                byte[] bytes = Base64.getUrlDecoder().decode(pages.getString(0));
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                int record = TreeArchiveFormat.HEADER_BYTES + buffer.getInt(52);
                int nameLength = buffer.getInt(record + 17);
                int fileFields = record + 21 + nameLength;
                switch (scenario) {
                    case 0 -> buffer.putInt(0, 0);
                    case 1 -> buffer.putShort(4, (short) 1);
                    case 2 -> buffer.putShort(6, (short) 1);
                    case 3 -> buffer.put(8, (byte) 2);
                    case 4 -> buffer.put(9, (byte) 1);
                    case 5 -> buffer.putLong(12, -1);
                    case 6 -> buffer.putLong(20, 1);
                    case 7 -> buffer.putInt(28, Integer.MAX_VALUE);
                    case 8 -> buffer.putLong(32, bytes.length + 1);
                    case 9 -> buffer.putInt(48, 2);
                    case 10 -> buffer.put(56, (byte) (bytes[56] ^ 1));
                    case 11 -> buffer.put(fileFields + 8, (byte) (bytes[fileFields + 8] ^ 1));
                    case 12 -> buffer.putLong(fileFields + 44, Long.MAX_VALUE);
                    default -> throw new AssertionError();
                }
                pages.set(0, NbtString.of(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));
            });
        }
    }

    private static void verifyOrderIndependence(Path work) throws IOException {
        NbtCompound root = NbtIo.readCompressed(work.resolve("cross-book.bin.litematic").toFile());
        NbtList barrels = region(root).getList("TileEntities", NbtElement.COMPOUND_TYPE);
        barrels.add(0, barrels.remove(1));
        NbtList books = barrels.getCompound(0).getList("Items", NbtElement.COMPOUND_TYPE);
        books.add(0, books.remove(1));
        Path shuffled = work.resolve("reordered.litematic");
        writeInventoriesFirst(root, shuffled);
        Path output = work.resolve("reordered.restored");
        LitematicaFileCodec.restore(shuffled, output);
        require(Files.mismatch(work.resolve("cross-book.bin"), output) == -1,
                "NBT field, barrel or list order affected decoding");
    }

    // Serialize Items before id/x/y/z to exercise arbitrary compound order.
    private static void writeInventoriesFirst(NbtCompound root, Path path) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeByte(NbtElement.COMPOUND_TYPE);
            out.writeUTF("");
            for (String key : root.getKeys()) {
                if (!key.equals("Regions")) {
                    writeTag(out, key, root.get(key));
                }
            }
            out.writeByte(NbtElement.COMPOUND_TYPE);
            out.writeUTF("Regions");
            out.writeByte(NbtElement.COMPOUND_TYPE);
            out.writeUTF("FileStorage");
            NbtCompound region = region(root);
            for (String key : region.getKeys()) {
                if (!key.equals("TileEntities")) {
                    writeTag(out, key, region.get(key));
                }
            }
            out.writeByte(NbtElement.LIST_TYPE);
            out.writeUTF("TileEntities");
            out.writeByte(NbtElement.COMPOUND_TYPE);
            NbtList barrels = region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
            out.writeInt(barrels.size());
            for (int i = 0; i < barrels.size(); i++) {
                NbtCompound barrel = barrels.getCompound(i);
                writeTag(out, "Items", barrel.get("Items"));
                for (String key : barrel.getKeys()) {
                    if (!key.equals("Items")) {
                        writeTag(out, key, barrel.get(key));
                    }
                }
                out.writeByte(0);
            }
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
        }
    }

    private static void writeTag(DataOutputStream out, String key, NbtElement value) throws IOException {
        out.writeByte(value.getType());
        out.writeUTF(key);
        value.write(out);
    }

    private static void verifyExtensionsAndStateBoundary(Path work) throws IOException {
        require(LitematicaFileCodec.normalizeSchematicPath(Path.of("test")).toString().equals("test.litematic"),
                "Missing extension not normalized");
        require(LitematicaFileCodec.normalizeSchematicPath(Path.of("test.litematica")).toString()
                .equals("test.litematic"), "Misspelled extension not normalized");
        long[] states = region(LitematicaFileCodec.createSchematic("boundary", 33)).getLongArray("BlockStates");
        require(states.length == 2 && states[0] == 0x5555555555555555L && states[1] == 1,
                "33-barrel bit-array boundary differs");
        require(TreeArchiveFormat.dataBookCount(0) == 0
                && TreeArchiveFormat.dataBookCount(LitematicaFileCodec.BYTES_PER_BOOK * 730) == 730,
                "Data count still contains a minimum or single-barrel limit");
        LitematicaFileCodec.pack(work.resolve("empty.bin"), work.resolve("extension.litematica"));
        require(Files.exists(work.resolve("extension.litematic")), "Output extension differs");
    }

    private static void verifyStreamIntegrity(Path work) throws IOException {
        byte[] bytes = Files.readAllBytes(work.resolve("exact-page.bin.litematic"));
        for (int removed : new int[]{1, 8, bytes.length / 2}) {
            Path truncated = work.resolve("truncated-" + removed + ".litematic");
            Files.write(truncated, Arrays.copyOf(bytes, bytes.length - removed));
            rejectArchive(truncated, work.resolve("truncated-" + removed + ".restored"));
        }
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length - 8] ^= 1;
        Path crc = work.resolve("bad-gzip-crc.litematic");
        Files.write(crc, corrupt);
        rejectArchive(crc, work.resolve("bad-gzip-crc.restored"));
        Path trailing = work.resolve("trailing-nbt.litematic");
        Files.write(trailing, bytes);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(trailing,
                java.nio.file.StandardOpenOption.APPEND))) {
            output.write(0);
        }
        rejectArchive(trailing, work.resolve("trailing-nbt.restored"));
        System.out.println("Truncated gzip, checksum and trailing NBT checks passed");
    }

    private static void verifyMultiBarrel(Path work) throws IOException {
        long size = LitematicaFileCodec.BYTES_PER_BARREL + 31;
        Path input = work.resolve("multi-barrel.bin");
        Random random = new Random(7);
        try (OutputStream out = Files.newOutputStream(input)) {
            byte[] chunk = new byte[64 * 1024];
            for (long remaining = size; remaining > 0;) {
                random.nextBytes(chunk);
                int count = (int) Math.min(remaining, chunk.length);
                out.write(chunk, 0, count);
                remaining -= count;
            }
        }
        Path archive = work.resolve("multi-barrel.litematic");
        LitematicaFileCodec.ArchiveInfo info = LitematicaFileCodec.pack(input, archive);
        require(info.metadataBookCount() == 1 && info.dataBookCount() == 28
                && info.totalBookCount() == 29 && info.barrelCount() == 3, "Multi-barrel counts differ");
        AtomicInteger metadataBooks = new AtomicInteger();
        AtomicInteger dataBooks = new AtomicInteger();
        StreamingArchiveScanner scanner = new StreamingArchiveScanner((page, count, encoded) -> {
            require(encoded.length() <= LitematicaFileCodec.MAX_PAGE_CHARS, "Oversized streaming page");
        }, book -> {
            if (book.x() == 0) {
                metadataBooks.incrementAndGet();
            } else {
                int index = (book.x() - 1) * 27 + book.slot();
                dataBooks.incrementAndGet();
                require(book.pageCount() == (index < 27 ? 100 : 1),
                        "Multi-barrel page count differs");
            }
        }, x -> { });
        NbtIo.scanCompressed(archive.toFile(), scanner);
        require(scanner.barrelCount() == 3, "Multi-barrel layout differs");
        require(metadataBooks.get() == 1 && dataBooks.get() == 28, "Streaming scan lost books");
        Path restored = work.resolve("multi-barrel.restored");
        LitematicaFileCodec.restore(archive, restored);
        require(Files.mismatch(input, restored) == -1, "Multi-barrel bytes differ");
        System.out.printf("Multi-barrel round trip passed: %d bytes, 1 metadata book, 28 data books, 3 barrels%n",
                size);
    }

    static void mutateAndReject(Path work, String name, NbtCompound original, Consumer<NbtCompound> mutation)
            throws IOException {
        NbtCompound root = original.copy();
        mutation.accept(root);
        Path path = work.resolve(name + ".litematic");
        NbtIo.writeCompressed(root, path.toFile());
        rejectArchive(path, work.resolve(name + ".restored"));
    }

    static void rejectArchive(Path path, Path output) throws IOException {
        expectFailure(() -> FileTreeCodec.restore(path, output), "Malformed archive was restored: " + path);
        require(!Files.exists(output), "Failed restore left an output");
        expectFailure(() -> FileTreeCodec.list(path), "Malformed archive was listed: " + path);
    }

    static byte[] decodeBooks(NbtList books) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < books.size(); i++) {
            NbtList pages = pages(books.getCompound(i));
            for (int j = 0; j < pages.size(); j++) {
                out.write(Base64.getUrlDecoder().decode(pages.getString(j)));
            }
        }
        return out.toByteArray();
    }

    static NbtCompound region(NbtCompound root) {
        return root.getCompound("Regions").getCompound("FileStorage");
    }

    static NbtList items(NbtCompound root, int x) {
        NbtList entities = region(root).getList("TileEntities", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < entities.size(); i++) {
            if (entities.getCompound(i).getInt("x") == x) {
                return entities.getCompound(i).getList("Items", NbtElement.COMPOUND_TYPE);
            }
        }
        throw new AssertionError("Missing barrel " + x);
    }

    static NbtList pages(NbtCompound book) {
        return book.getCompound("tag").getList("pages", NbtElement.STRING_TYPE);
    }

    static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    static void expectFailure(IoAction action, String message) throws IOException {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            // Rejection is required; no production parser for old formats exists.
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }
}
