package awszqsedxzaw.cloudarchive.codec;

import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtType;
import net.minecraft.nbt.scanner.NbtCollector;

public final class FileCodecSelfTest {
    private FileCodecSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("cloudarchive-codec-test-");
        try {
            roundTrip(directory, "empty.bin", new byte[0]);
            roundTrip(directory, "exact-page.bin", randomBytes(LitematicaFileCodec.BYTES_PER_PAGE, 5L));
            roundTrip(directory, "unicode-数据.bin", randomBytes(LitematicaFileCodec.BYTES_PER_PAGE + 17, 1L));
            roundTrip(directory, "exact-book.bin",
                    randomBytes(Math.toIntExact(LitematicaFileCodec.BYTES_PER_BOOK), 6L));
            roundTrip(
                    directory,
                    "cross-book.bin",
                    randomBytes(Math.toIntExact(LitematicaFileCodec.BYTES_PER_BOOK + 31), 2L)
            );
            verifyPageCapacity(directory);
            verifyBookAndBarrelReordering(directory);
            verifyMissingAndDuplicateBooks(directory);
            verifyOldMarkerRejected(directory);
            verifyOldFormatRejected(directory);
            verifyPackedStateBoundary();
            verifyLegacyExtensionNormalization(directory);
            verifyIntegrityAndNoOverwrite(directory);
            verifyMultiBarrelRoundTrip(directory);
            System.out.println("File codec checks passed");
        } finally {
            deleteTree(directory);
        }
    }

    private static void roundTrip(Path directory, String name, byte[] contents) throws IOException {
        Path input = directory.resolve(name);
        Path schematic = directory.resolve(name + ".litematic");
        Path restored = directory.resolve(name + ".restored");
        Files.write(input, contents);

        LitematicaFileCodec.ArchiveInfo packed = LitematicaFileCodec.pack(input, schematic);
        LitematicaFileCodec.ArchiveInfo extracted = LitematicaFileCodec.restore(schematic, restored);

        require(packed.fileSize() == contents.length, "Packed size mismatch");
        require(packed.bookCount() == Math.max(1,
                (contents.length + LitematicaFileCodec.BYTES_PER_BOOK - 1)
                        / LitematicaFileCodec.BYTES_PER_BOOK), "Incorrect book count");
        require(extracted.fileSize() == contents.length, "Restored size mismatch");
        require(extracted.fileName().equals(name), "Original filename mismatch");
        require(Files.mismatch(input, restored) == -1, "Round-trip bytes differ");
        verifySchematic(schematic);

        boolean overwriteRejected = false;
        try {
            LitematicaFileCodec.pack(input, schematic);
        } catch (FileAlreadyExistsException expected) {
            overwriteRejected = true;
        }
        require(overwriteRejected, "Packing should not overwrite an existing output");

        overwriteRejected = false;
        try {
            LitematicaFileCodec.restore(schematic, restored);
        } catch (FileAlreadyExistsException expected) {
            overwriteRejected = true;
        }
        require(overwriteRejected, "Restoration should not overwrite an existing output");
        require(Files.mismatch(input, restored) == -1, "Rejected restore changed the existing output");
    }

    private static void verifySchematic(Path schematic) throws IOException {
        require(
                schematic.getFileName().toString().endsWith(LitematicaFileCodec.SCHEMATIC_EXTENSION),
                "Schematic does not use Litematica's file extension"
        );
        NbtCompound root = NbtIo.readCompressed(schematic.toFile());
        require(root.getInt("Version") == 6, "Incorrect Litematica version");
        require(root.getInt("SubVersion") == 1, "Incorrect Litematica sub-version");
        require(root.getInt("MinecraftDataVersion") == 3465, "Incorrect Minecraft data version");

        NbtCompound metadata = root.getCompound("Metadata");
        require(metadata.getInt("RegionCount") == 1, "Incorrect metadata region count");
        NbtCompound region = root.getCompound("Regions").getCompound("FileStorage");
        int barrelCount = region.getCompound("Size").getInt("x");
        require(barrelCount > 0, "No barrel blocks");
        require(metadata.getInt("TotalVolume") == barrelCount, "Incorrect metadata volume");
        require(metadata.getInt("TotalBlocks") == barrelCount, "Incorrect metadata block count");
        verifyPosition(metadata.getCompound("EnclosingSize"), barrelCount, 1, 1, "metadata size");
        verifyPosition(region.getCompound("Position"), 0, 0, 0, "region position");
        verifyPosition(region.getCompound("Size"), barrelCount, 1, 1, "region size");
        NbtList palette = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
        require(palette.size() == 2, "Expected air and barrel palette entries");
        require(palette.getCompound(0).getString("Name").equals("minecraft:air"),
                "Litematica requires air at palette index zero");
        NbtCompound barrelState = palette.getCompound(1);
        require(barrelState.getString("Name").equals("minecraft:barrel"), "Barrel block is missing");
        NbtCompound properties = barrelState.getCompound("Properties");
        require(properties.getString("facing").equals("north"), "Barrel facing property is invalid");
        require(properties.getString("open").equals("false"), "Barrel open property is invalid");

        long[] packedStates = region.getLongArray("BlockStates");
        int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int expectedLongCount = (barrelCount * bitsPerEntry + Long.SIZE - 1) / Long.SIZE;
        require(packedStates.length == expectedLongCount, "Packed block-state array has the wrong size");
        for (int index = 0; index < barrelCount; index++) {
            int bitOffset = index * bitsPerEntry;
            long paletteIndex = packedStates[bitOffset / Long.SIZE]
                    >>> (bitOffset % Long.SIZE) & ((1L << bitsPerEntry) - 1);
            require(paletteIndex == 1, "Litematica would decode an incorrect block at " + index);
        }
        NbtList tileEntities = region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
        require(tileEntities.size() == barrelCount, "Expected a block entity for each barrel");
        Set<Integer> bookIndices = new HashSet<>();
        NbtCompound header = null;
        for (int barrelIndex = 0; barrelIndex < barrelCount; barrelIndex++) {
            NbtCompound barrel = tileEntities.getCompound(barrelIndex);
            require(barrel.getString("id").equals("minecraft:barrel"), "Barrel NBT is invalid");
            verifyPosition(barrel, barrelIndex, 0, 0, "barrel block entity position");
            NbtList items = barrel.getList("Items", NbtElement.COMPOUND_TYPE);
            require(!items.isEmpty() && items.size() <= 27, "Invalid barrel inventory");
            if (barrelIndex < barrelCount - 1) {
                require(items.size() == 27, "Non-final barrel is not full");
            }
            Set<Integer> slots = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                NbtCompound book = items.getCompound(i);
                int slot = book.getByte("Slot") & 0xFF;
                require(slot < 27 && slots.add(slot), "Invalid or duplicate inventory slot");
                verifyBook(book, bookIndices);
                NbtCompound marker = book.getCompound("tag").getCompound("CloudArchive");
                require(marker.getInt("index") == barrelIndex * 27 + slot, "Book placed in incorrect barrel or slot");
                if (marker.getInt("index") == 0) {
                    header = marker.getCompound("header");
                }
            }
        }
        require(header != null, "Archive header book is missing");
        require(bookIndices.size() == header.getInt("book_count"), "Book count does not match archive metadata");
        for (int index = 0; index < bookIndices.size(); index++) {
            require(bookIndices.contains(index), "Archive book index is missing: " + index);
        }
    }

    private static void verifyBook(NbtCompound book, Set<Integer> bookIndices) {
        require(book.getString("id").equals("minecraft:written_book"), "Books must be directly in barrels");
        require(book.getByte("Count") == 1, "Expected a single book per inventory stack");
        verifyArchiveTag(book.getCompound("tag"), bookIndices);
    }

    private static void verifyArchiveTag(NbtCompound tag, Set<Integer> bookIndices) {
        require(!tag.contains("newproject") && !tag.contains("BlockEntityTag"), "Unexpected old marker or nesting");
        require(tag.contains("CloudArchive", NbtElement.COMPOUND_TYPE), "CloudArchive marker is missing");
        NbtCompound marker = tag.getCompound("CloudArchive");
        int index = marker.getInt("index");
        require(marker.getString("kind").equals("file-archive-v2"), "Archive book marker is invalid");
        require(bookIndices.add(index), "Archive book index is duplicated");
        require(marker.contains("header") == (index == 0), "Only the first book should contain the file header");
        NbtList pages = tag.getList("pages", NbtElement.STRING_TYPE);
        require(!pages.isEmpty() && pages.size() <= 100, "Archive book has an invalid page count");
        for (int page = 0; page < pages.size(); page++) {
            String text = pages.getString(page);
            require(text.length() <= LitematicaFileCodec.MAX_PAGE_CHARS,
                    "Archive page exceeds the supported page capacity");
            require(new JsonPrimitive(text).toString().length() <= 32_767,
                    "Archive page exceeds Minecraft's serialized JSON limit");
        }
    }

    private static void verifyPageCapacity(Path directory) throws IOException {
        NbtCompound root = NbtIo.readCompressed(directory.resolve("exact-page.bin.litematic").toFile());
        NbtList pages = root.getCompound("Regions").getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(0)
                .getList("Items", NbtElement.COMPOUND_TYPE).getCompound(0).getCompound("tag")
                .getList("pages", NbtElement.STRING_TYPE);
        require(pages.size() == 1 && pages.getString(0).length() == 32_764,
                "Full page must contain 32,764 Base64 characters");
        require(Files.size(directory.resolve("exact-page.bin")) == 24_573,
                "Full page must hold 24,573 original bytes");
        require(new JsonPrimitive(pages.getString(0)).toString().length() == 32_766,
                "Full page must leave space for JSON quotes");

        pages.set(0, NbtString.of("A".repeat(LitematicaFileCodec.MAX_PAGE_CHARS + 1)));
        Path oversized = directory.resolve("oversized-page.litematic");
        NbtIo.writeCompressed(root, oversized.toFile());
        requireRestoreRejected(oversized, directory.resolve("oversized-page.restored"), "page capacity");
        System.out.println("Page capacity checks passed: 32,764 Base64 characters, 24,573 original bytes");
    }

    private static void verifyPosition(NbtCompound position, int x, int y, int z, String name) {
        require(position.getInt("x") == x, name + " x is invalid");
        require(position.getInt("y") == y, name + " y is invalid");
        require(position.getInt("z") == z, name + " z is invalid");
    }

    private static void verifyBookAndBarrelReordering(Path directory) throws IOException {
        NbtCompound root = NbtIo.readCompressed(directory.resolve("cross-book.bin.litematic").toFile());
        NbtCompound region = root.getCompound("Regions").getCompound("FileStorage");
        NbtCompound firstBarrel = region.getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(0);
        NbtList books = firstBarrel.getList("Items", NbtElement.COMPOUND_TYPE);
        NbtCompound lastBook = (NbtCompound) books.remove(1);
        lastBook.putByte("Slot", (byte) 13);
        NbtList movedBooks = new NbtList();
        movedBooks.add(lastBook);
        NbtCompound movedBarrel = firstBarrel.copy();
        movedBarrel.put("Items", movedBooks);
        firstBarrel.putInt("x", 1);
        region.getCompound("Size").putInt("x", 2);
        region.putLongArray("BlockStates", new long[]{5L});
        NbtCompound metadata = root.getCompound("Metadata");
        metadata.putInt("TotalVolume", 2);
        metadata.putInt("TotalBlocks", 2);
        metadata.getCompound("EnclosingSize").putInt("x", 2);
        // Visit book 1 before the header book and change its physical location and slot.
        region.getList("TileEntities", NbtElement.COMPOUND_TYPE).add(0, movedBarrel);
        Path shuffled = directory.resolve("reordered.litematic");
        Path restored = directory.resolve("reordered.restored");
        NbtIo.writeCompressed(root, shuffled.toFile());
        LitematicaFileCodec.restore(shuffled, restored);
        require(Files.mismatch(directory.resolve("cross-book.bin"), restored) == -1,
                "Restore depends on barrel or inventory order");
    }

    private static void verifyMissingAndDuplicateBooks(Path directory) throws IOException {
        Path original = directory.resolve("cross-book.bin.litematic");
        NbtCompound root = NbtIo.readCompressed(original.toFile());
        NbtList items = root.getCompound("Regions").getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(0)
                .getList("Items", NbtElement.COMPOUND_TYPE);
        items.remove(1);
        Path missing = directory.resolve("missing-book.litematic");
        NbtIo.writeCompressed(root, missing.toFile());
        requireRestoreRejected(missing, directory.resolve("missing-book.restored"), "missing");

        items.add(items.getCompound(0).copy());
        Path duplicate = directory.resolve("duplicate-book.litematic");
        NbtIo.writeCompressed(root, duplicate.toFile());
        requireRestoreRejected(duplicate, directory.resolve("duplicate-book.restored"), "duplicate");
    }

    private static void verifyOldMarkerRejected(Path directory) throws IOException {
        NbtCompound root = NbtIo.readCompressed(directory.resolve("empty.bin.litematic").toFile());
        NbtCompound tag = root.getCompound("Regions").getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(0)
                .getList("Items", NbtElement.COMPOUND_TYPE).getCompound(0).getCompound("tag");
        tag.put("newproject", tag.get("CloudArchive"));
        tag.remove("CloudArchive");
        Path old = directory.resolve("old-marker.litematic");
        NbtIo.writeCompressed(root, old.toFile());
        requireRestoreRejected(old, directory.resolve("old-marker.restored"), "No CloudArchive");
    }

    private static void verifyOldFormatRejected(Path directory) throws IOException {
        NbtCompound root = NbtIo.readCompressed(directory.resolve("empty.bin.litematic").toFile());
        NbtCompound marker = root.getCompound("Regions").getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(0)
                .getList("Items", NbtElement.COMPOUND_TYPE).getCompound(0).getCompound("tag")
                .getCompound("CloudArchive");
        marker.putString("kind", "file-archive-v1");
        Path old = directory.resolve("old-format.litematic");
        NbtIo.writeCompressed(root, old.toFile());
        requireRestoreRejected(old, directory.resolve("old-format.restored"), "unsupported format");
    }

    private static void requireRestoreRejected(Path source, Path output, String reason) throws IOException {
        try {
            LitematicaFileCodec.restore(source, output);
            throw new AssertionError("Restore should reject " + source.getFileName());
        } catch (IOException expected) {
            require(expected.getMessage().contains(reason), "Incorrect rejection reason: " + expected.getMessage());
            require(!Files.exists(output), "Failed restore left a partial output file");
        }
    }

    private static void verifyPackedStateBoundary() throws IOException {
        NbtCompound root = LitematicaFileCodec.createSchematic("state-boundary", 27 * 32 + 1);
        long[] states = root.getCompound("Regions").getCompound("FileStorage").getLongArray("BlockStates");
        require(states.length == 2, "33 barrels require two packed state longs");
        require(states[0] == 0x5555555555555555L && states[1] == 1L,
                "Packed states do not match Litematica's continuous two-bit layout");
        require(LitematicaFileCodec.bookCountForSize(LitematicaFileCodec.BYTES_PER_BOOK * 730) == 730,
                "The old single-barrel book limit is still present");
    }

    private static void verifyMultiBarrelRoundTrip(Path directory) throws IOException {
        long size = LitematicaFileCodec.BYTES_PER_BARREL + 31;
        Path input = directory.resolve("multi-barrel.bin");
        Path schematic = directory.resolve("multi-barrel.litematic");
        Path restored = directory.resolve("multi-barrel.restored");
        Random random = new Random(7L);
        try (OutputStream stream = Files.newOutputStream(input)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int count = (int) Math.min(remaining, buffer.length);
                stream.write(buffer, 0, count);
                remaining -= count;
            }
        }
        LitematicaFileCodec.ArchiveInfo packed = LitematicaFileCodec.pack(input, schematic);
        require(packed.bookCount() == 28 && packed.barrelCount() == 2, "Multi-barrel packing count is incorrect");
        AtomicInteger index = new AtomicInteger();
        Set<Integer> bookIndices = new HashSet<>();
        NbtIo.scanCompressed(schematic.toFile(), new ArchiveBookScanner(book -> {
            verifyArchiveTag(book.getCompound("tag"), bookIndices);
            NbtCompound marker = book.getCompound("tag").getCompound("CloudArchive");
            int expected = index.getAndIncrement();
            require(marker.getInt("index") == expected, "Multi-barrel book index is incorrect");
            NbtList pages = book.getCompound("tag").getList("pages", NbtElement.STRING_TYPE);
            require(pages.size() == (expected < 27 ? 100 : 1), "Incorrect multi-barrel page count");
            if (expected < 27) {
                for (int page = 0; page < pages.size(); page++) {
                    require(pages.getString(page).length() == LitematicaFileCodec.MAX_PAGE_CHARS,
                            "Non-final multi-barrel book contains a partial page");
                }
            }
        }));
        require(index.get() == 28, "Multi-barrel books are missing");

        // Skip inventories so the complete page tree does not consume the test heap.
        BarrelLayoutCollector layout = new BarrelLayoutCollector();
        NbtIo.scanCompressed(schematic.toFile(), layout);
        NbtCompound root = (NbtCompound) layout.getRoot();
        NbtCompound metadata = root.getCompound("Metadata");
        NbtCompound region = root.getCompound("Regions").getCompound("FileStorage");
        verifyPosition(metadata.getCompound("EnclosingSize"), 2, 1, 1, "multi-barrel enclosing size");
        verifyPosition(region.getCompound("Size"), 2, 1, 1, "multi-barrel region size");
        require(metadata.getInt("TotalBlocks") == 2 && metadata.getInt("TotalVolume") == 2,
                "Incorrect multi-barrel metadata");
        require(region.getLongArray("BlockStates")[0] == 5L, "Second barrel is not encoded in block states");
        NbtList entities = region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
        require(entities.size() == 2, "Expected two barrel block entities");
        for (int i = 0; i < entities.size(); i++) {
            verifyPosition(entities.getCompound(i), i, 0, 0, "multi-barrel entity");
            require(entities.getCompound(i).getString("id").equals("minecraft:barrel"), "Incorrect block entity type");
        }
        LitematicaFileCodec.ArchiveInfo extracted = LitematicaFileCodec.restore(schematic, restored);
        require(extracted.fileSize() == size && extracted.bookCount() == 28, "Multi-barrel restore count is incorrect");
        require(Files.mismatch(input, restored) == -1, "Multi-barrel round-trip bytes differ");
        System.out.printf("Multi-barrel round trip passed: %d bytes, 28 books, 2 barrels%n", size);
    }

    private static void verifyLegacyExtensionNormalization(Path directory) throws IOException {
        Path input = directory.resolve("legacy-extension.bin");
        Path requested = directory.resolve("legacy-extension.litematica");
        Path schematic = directory.resolve("legacy-extension.litematic");
        Path restored = directory.resolve("legacy-extension.restored");
        byte[] contents = randomBytes(257, 4L);
        Files.write(input, contents);

        LitematicaFileCodec.pack(input, requested);
        require(Files.exists(schematic), "Incorrect legacy extension was not normalized");
        require(!Files.exists(requested), "Incorrect legacy extension output was created");
        LitematicaFileCodec.restore(schematic, restored);
        require(Files.mismatch(input, restored) == -1, "Normalized-extension round trip differs");
    }

    private static void verifyIntegrityAndNoOverwrite(Path directory) throws IOException {
        Path input = directory.resolve("integrity.bin");
        Path schematic = directory.resolve("integrity.litematic");
        Path corrupted = directory.resolve("integrity-corrupted.litematic");
        Path restored = directory.resolve("integrity-restored.bin");
        Files.write(input, randomBytes(128, 3L));
        LitematicaFileCodec.pack(input, schematic);

        NbtCompound root = NbtIo.readCompressed(schematic.toFile());
        NbtCompound barrel = root.getCompound("Regions")
                .getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE)
                .getCompound(0);
        NbtCompound bookTag = barrel.getList("Items", NbtElement.COMPOUND_TYPE)
                .getCompound(0)
                .getCompound("tag");
        bookTag.getCompound("CloudArchive")
                .getCompound("header")
                .putString("sha256", "0".repeat(64));
        NbtIo.writeCompressed(root, corrupted.toFile());

        try {
            LitematicaFileCodec.restore(corrupted, restored);
            throw new AssertionError("Restore should reject a bad SHA-256");
        } catch (IOException expected) {
            require(!Files.exists(restored), "Failed restore left a partial output file");
        }
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static final class BarrelLayoutCollector extends NbtCollector {
        @Override
        public NestedResult startSubNbt(NbtType<?> type, String key) {
            if (key.equals("Items")) {
                return NestedResult.SKIP;
            }
            return super.startSubNbt(type, key);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
