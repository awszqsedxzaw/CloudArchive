package awszqsedxzaw.cloudarchive.codec;

import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Extent;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Node;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

final class TreeCodecSelfTest {
    private TreeCodecSelfTest() {
    }

    static void run(Path directory) throws IOException {
        verifyEmptyTree(directory);
        verifyTreeAndSelections(directory);
        verifyLargeIndex(directory);
        verifyExtentAddressing(directory);
        verifyMalformedContainers(directory);
        verifyCorruptionAndMixedBooks(directory);
        verifyInputAndOutputSafety(directory);
        verifySourceChangeDetection(directory);
        verifySymbolicLinks(directory);
        verifyMultiBarrelTree(directory);
        System.out.println("File tree checks passed");
    }

    private static void verifyEmptyTree(Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("empty-tree-source"));
        Path schematic = directory.resolve("empty-tree.litematic");
        FileTreeCodec.Summary packed = FileTreeCodec.pack(root, schematic);
        require(packed.fileCount() == 0 && packed.directoryCount() == 0 && packed.contentSize() == 0,
                "Empty tree statistics differ");
        require(packed.archive().fileSize() == TreeArchiveFormat.HEADER_BYTES,
                "Empty tree container should contain only its header");
        require(FileTreeCodec.list(schematic).entries().isEmpty(), "Empty tree listing is not empty");
        Path restored = directory.resolve("empty-tree-restored");
        FileTreeCodec.restore(schematic, restored);
        compareTrees(root, restored);
    }

    private static void verifyTreeAndSelections(Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("tree-source"));
        Path nested = Files.createDirectory(root.resolve("nested-\u6570\u636e"));
        Files.createDirectories(nested.resolve("empty/deeper"));
        Files.write(nested.resolve("empty.bin"), new byte[0]);
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        Files.write(root.resolve("all-bytes.bin"), binary);
        Files.write(root.resolve("emoji-\ud83d\udce6.bin"), binary);
        byte[] large = new byte[Math.toIntExact(LitematicaFileCodec.BYTES_PER_BOOK + 51)];
        new Random(27L).nextBytes(large);
        Files.write(nested.resolve("large file.bin"), large);
        Files.writeString(nested.resolve("notes.txt"), "test\r\nsecond line\n", StandardCharsets.UTF_8);
        Path schematic = directory.resolve("tree.litematic");
        FileTreeCodec.Summary packed = FileTreeCodec.pack(root, schematic);
        require(packed.fileCount() == 5 && packed.directoryCount() == 3, "Incorrect tree node counts");
        require(packed.archive().bookCount() == 2, "Tree does not span books");
        Path restored = directory.resolve("tree-restored");
        FileTreeCodec.Summary result = FileTreeCodec.restore(schematic, restored);
        require(result.contentSize() == packed.contentSize(), "Tree content size differs");
        compareTrees(root, restored);
        require(FileTreeCodec.list(schematic).entries().size() == 8, "Tree listing is incomplete");
        verifyTreePages(schematic);

        Path selected = directory.resolve("selected-tree.litematic");
        List<Path> selections = List.of(Path.of("all-bytes.bin"),
                Path.of("nested-\u6570\u636e/notes.txt"), Path.of("all-bytes.bin"));
        FileTreeCodec.Summary subset = FileTreeCodec.pack(root, selections, selected);
        require(subset.fileCount() == 2 && subset.directoryCount() == 1,
                "Selected files or inferred parent directories differ");
        Path selectedOutput = directory.resolve("selected-tree-restored");
        FileTreeCodec.restore(selected, selectedOutput);
        require(Files.mismatch(root.resolve("all-bytes.bin"), selectedOutput.resolve("all-bytes.bin")) == -1,
                "Selected file data differs");
        require(Files.mismatch(nested.resolve("notes.txt"),
                selectedOutput.resolve("nested-\u6570\u636e/notes.txt")) == -1, "Selected nested file differs");
        require(!Files.exists(selectedOutput.resolve("nested-\u6570\u636e/large file.bin")),
                "Unselected files were included");
        FileTreeCodec.Summary overlapping = FileTreeCodec.pack(root,
                List.of(Path.of("nested-\u6570\u636e"), Path.of("nested-\u6570\u636e/notes.txt")),
                directory.resolve("overlapping-tree.litematic"));
        require(overlapping.fileCount() == 3 && overlapping.directoryCount() == 3,
                "Overlapping selections were not deduplicated");
        Path reversed = directory.resolve("reversed-selection.litematic");
        FileTreeCodec.pack(root, List.of(Path.of("nested-\u6570\u636e/notes.txt"), Path.of("all-bytes.bin")), reversed);
        Path firstPayload = directory.resolve("selected-first.payload");
        Path secondPayload = directory.resolve("selected-second.payload");
        LitematicaFileCodec.restorePayload(selected, firstPayload, LitematicaFileCodec.TREE_FORMAT);
        LitematicaFileCodec.restorePayload(reversed, secondPayload, LitematicaFileCodec.TREE_FORMAT);
        require(Files.mismatch(firstPayload, secondPayload) == -1, "Selection order changes the tree container");

        NbtCompound shuffled = NbtIo.readCompressed(schematic.toFile());
        NbtList items = firstItems(shuffled);
        NbtCompound second = (NbtCompound) items.remove(1);
        second.putByte("Slot", (byte) 19);
        items.add(0, second);
        Path reordered = directory.resolve("tree-reordered.litematic");
        NbtIo.writeCompressed(shuffled, reordered.toFile());
        Path reorderedOutput = directory.resolve("tree-reordered-restored");
        FileTreeCodec.restore(reordered, reorderedOutput);
        compareTrees(root, reorderedOutput);

        Path rawRestored = directory.resolve("raw-dispatch-restored");
        FileTreeCodec.Summary raw = FileTreeCodec.restore(directory.resolve("empty.bin.litematic"), rawRestored);
        require(raw.fileCount() == 1 && Files.size(rawRestored) == 0, "Single-file restore dispatch regressed");
        require(FileTreeCodec.list(directory.resolve("empty.bin.litematic")).entries().size() == 1,
                "Single-file listing regressed");
    }

    private static void verifyLargeIndex(Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("many-files-source"));
        for (int i = 0; i < 1500; i++) {
            Files.write(root.resolve(String.format("tiny-file-%04d.bin", i)), new byte[]{(byte) i});
        }
        Path schematic = directory.resolve("many-files.litematic");
        FileTreeCodec.Summary packed = FileTreeCodec.pack(root, schematic);
        require(packed.fileCount() == 1500 && packed.contentSize() == 1500, "Small-file statistics differ");
        require(packed.archive().bookCount() == 1, "Small files should share one book");
        require(packed.archive().fileSize() > LitematicaFileCodec.BYTES_PER_PAGE,
                "Large index fixture must span pages");
        Path restored = directory.resolve("many-files-restored");
        FileTreeCodec.restore(schematic, restored);
        compareTrees(root, restored);
        verifyTreePages(schematic);
        System.out.printf("Large index round trip passed: 1,500 files, %d container bytes, 1 book%n",
                packed.archive().fileSize());
    }

    private static void verifyExtentAddressing(Path directory) throws IOException {
        Path payload = directory.resolve("extents.payload");
        List<Node> nodes = List.of(
                new Node(17, 0, "fragmented.bin", false, 6, List.of(new Extent(5, 3), new Extent(1, 3))),
                new Node(42, 0, "empty", true, 0, List.of()));
        writeRaw(payload, nodes, "01234567".getBytes(StandardCharsets.US_ASCII), 43);
        try (RandomAccessFile file = new RandomAccessFile(payload.toFile(), "rw")) {
            file.seek(8);
            file.writeLong(7);
        }
        Path schematic = directory.resolve("extents.litematic");
        LitematicaFileCodec.packPayload(payload, schematic, "extent-test", LitematicaFileCodec.TREE_FORMAT);
        Path restored = directory.resolve("extents-restored");
        FileTreeCodec.restore(schematic, restored);
        require(Files.readString(restored.resolve("fragmented.bin")).equals("567123"),
                "Explicit out-of-order extents were not restored");
        require(Files.isDirectory(restored.resolve("empty")), "Extent fixture lost its empty directory");
        List<FileTreeCodec.Entry> listed = FileTreeCodec.list(schematic).entries();
        require(listed.get(0).id() == 17 && listed.get(1).id() == 42,
                "Stable IDs were replaced with list positions");
        require(TreeArchiveFormat.read(payload).generation() == 7, "Generation field was discarded");
        List<Node> expandedNodes = new ArrayList<>(nodes);
        expandedNodes.add(new Node(43, 0, "another-directory", true, 0, List.of()));
        Path expanded = directory.resolve("expanded-index.payload");
        writeRaw(expanded, expandedNodes, "01234567".getBytes(StandardCharsets.US_ASCII), 44);
        byte[] originalBytes = Files.readAllBytes(payload);
        byte[] expandedBytes = Files.readAllBytes(expanded);
        for (int offset = 44; offset < 52; offset++) {
            require(originalBytes[offset] == expandedBytes[offset], "Index growth moved stored data addresses");
        }
        Path expandedSchematic = directory.resolve("expanded-index.litematic");
        LitematicaFileCodec.packPayload(expanded, expandedSchematic, "expanded-index", LitematicaFileCodec.TREE_FORMAT);
        Path expandedOutput = directory.resolve("expanded-index-restored");
        FileTreeCodec.restore(expandedSchematic, expandedOutput);
        require(Files.mismatch(restored.resolve("fragmented.bin"), expandedOutput.resolve("fragmented.bin")) == -1,
                "Index growth changed extent restoration");
    }

    private static void verifyMalformedContainers(Path directory) throws IOException {
        List<List<Node>> invalid = new ArrayList<>();
        for (String name : List.of("..", "../escaped", "/absolute", "C:\\absolute", "NUL", "CON .txt", "bad:name",
                "trailing.", "trailing ", "a/b", "a\\b")) {
            invalid.add(List.of(new Node(1, 0, name, true, 0, List.of())));
        }
        invalid.add(List.of(new Node(1, 0, "one", true, 0, List.of()),
                new Node(1, 0, "two", true, 0, List.of())));
        invalid.add(List.of(new Node(1, 0, "Case", true, 0, List.of()),
                new Node(2, 0, "case", true, 0, List.of())));
        invalid.add(List.of(new Node(1, 2, "child", true, 0, List.of()),
                new Node(2, 0, "parent", true, 0, List.of())));
        invalid.add(List.of(new Node(1, 0, "file", false, 0, List.of()),
                new Node(2, 1, "child", true, 0, List.of())));
        invalid.add(List.of(new Node(-1, 0, "negative", true, 0, List.of())));
        invalid.add(List.of(new Node(1, 0, "outside", false, 2, List.of(new Extent(3, 2)))));
        invalid.add(List.of(new Node(1, 0, "negative-offset", false, 1, List.of(new Extent(-1, 1)))));
        invalid.add(List.of(new Node(1, 0, "bad-size", false, 2, List.of(new Extent(0, 1)))));
        invalid.add(List.of(new Node(1, 0, "zero-extent", false, 0, List.of(new Extent(0, 0)))));
        invalid.add(List.of(new Node(1, 0, "negative-size", false, -1, List.of())));
        for (int i = 0; i < invalid.size(); i++) {
            Path payload = directory.resolve("bad-tree-" + i + ".payload");
            writeRaw(payload, invalid.get(i), new byte[]{1, 2, 3, 4}, 100);
            rejectPayload(directory, payload, "bad-tree-" + i);
        }
        List<Node> valid = List.of(new Node(1, 0, "aa", true, 0, List.of()));
        for (int test = 0; test < 13; test++) {
            Path payload = directory.resolve("bad-header-" + test + ".payload");
            writeRaw(payload, valid, new byte[0], 2);
            try (RandomAccessFile file = new RandomAccessFile(payload.toFile(), "rw")) {
                switch (test) {
                    case 0 -> file.writeInt(0);
                    case 1 -> { file.seek(4); file.writeShort(2); }
                    case 2 -> { file.seek(6); file.writeShort(1); }
                    case 3 -> { file.seek(8); file.writeLong(-1); }
                    case 4 -> { file.seek(16); file.writeLong(1); }
                    case 5 -> { file.seek(24); file.writeInt(Integer.MAX_VALUE); }
                    case 6 -> { file.seek(28); file.writeLong(Long.MAX_VALUE); }
                    case 7 -> { file.seek(36); file.writeLong(1); }
                    case 8 -> { file.seek(65); file.write(new byte[]{(byte) 0xC0, (byte) 0xAF}); }
                    case 9 -> { file.seek(44); file.writeByte(2); }
                    case 10 -> { file.seek(61); file.writeInt(Integer.MAX_VALUE); }
                    case 11 -> { file.seek(53); file.writeLong(1); }
                    case 12 -> { file.seek(24); file.writeInt(0); }
                    default -> throw new AssertionError();
                }
            }
            rejectPayload(directory, payload, "bad-header-" + test);
        }
        Path truncated = directory.resolve("truncated-index.payload");
        writeRaw(truncated, valid, new byte[0], 2);
        try (RandomAccessFile file = new RandomAccessFile(truncated.toFile(), "rw")) {
            file.setLength(file.length() - 1);
        }
        rejectPayload(directory, truncated, "truncated-index");
        try (Stream<Path> children = Files.list(directory)) {
            require(children.noneMatch(path -> path.getFileName().toString().startsWith(".cloudarchive-tree-")),
                    "Rejected restore left a temporary directory");
        }
    }

    private static void verifyCorruptionAndMixedBooks(Path directory) throws IOException {
        Path original = directory.resolve("tree.litematic");
        for (int test = 0; test < 3; test++) {
            NbtCompound root = NbtIo.readCompressed(original.toFile());
            NbtList books = firstItems(root);
            if (test == 0) {
                NbtList pages = books.getCompound(1).getCompound("tag")
                        .getList("pages", NbtElement.STRING_TYPE);
                String text = pages.getString(0);
                pages.set(0, NbtString.of((text.charAt(0) == 'A' ? "B" : "A") + text.substring(1)));
            } else if (test == 1) {
                books.getCompound(1).getCompound("tag").getCompound("CloudArchive")
                        .putString("kind", LitematicaFileCodec.FILE_FORMAT);
            } else {
                books.remove(1);
            }
            Path corrupt = directory.resolve("corrupt-tree-" + test + ".litematic");
            NbtIo.writeCompressed(root, corrupt.toFile());
            rejectRestore(corrupt, directory.resolve("corrupt-tree-" + test + "-restored"));
        }
    }

    private static void verifyInputAndOutputSafety(Path directory) throws IOException {
        Path root = directory.resolve("tree-source");
        List<List<Path>> badSelections = List.of(
                List.of(Path.of("../outside")), List.of(root.resolve("all-bytes.bin")),
                List.of(Path.of("missing.bin")));
        for (int i = 0; i < badSelections.size(); i++) {
            Path output = directory.resolve("bad-selection-" + i + ".litematic");
            try {
                FileTreeCodec.pack(root, badSelections.get(i), output);
                throw new AssertionError("Invalid selection was accepted");
            } catch (IOException expected) {
                require(!Files.exists(output), "Invalid selection produced an output");
            }
        }
        Path inside = root.resolve("recursive-output.litematic");
        try {
            FileTreeCodec.pack(root, inside);
            throw new AssertionError("Output inside the source tree was accepted");
        } catch (IOException expected) {
            require(!Files.exists(inside), "Recursive output was created");
        }
        Path existing = Files.createDirectory(directory.resolve("existing-tree"));
        Files.write(existing.resolve("sentinel"), new byte[]{12});
        rejectRestore(directory.resolve("tree.litematic"), existing, false);
        require(Files.readAllBytes(existing.resolve("sentinel"))[0] == 12, "Existing directory was changed");
        Path output = directory.resolve("tree.litematic");
        String hash = LitematicaFileCodec.sha256(output);
        try {
            FileTreeCodec.pack(root, output);
            throw new AssertionError("Existing schematic was overwritten");
        } catch (IOException expected) {
            require(hash.equals(LitematicaFileCodec.sha256(output)), "Existing schematic changed");
        }
    }

    private static void verifySymbolicLinks(Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("link-source"));
        Path link = root.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, directory.resolve("tree-source/all-bytes.bin"));
        } catch (UnsupportedOperationException | IOException exception) {
            System.out.println("Symbolic link creation unavailable; ancestor and special-path checks remain enabled");
            return;
        }
        try {
            FileTreeCodec.pack(root, directory.resolve("linked-tree.litematic"));
            throw new AssertionError("Symbolic link source was accepted");
        } catch (IOException expected) {
            require(!Files.exists(directory.resolve("linked-tree.litematic")), "Linked source produced an output");
        }
        Path directoryLink = directory.resolve("linked-source-directory");
        Files.createSymbolicLink(directoryLink, directory.resolve("tree-source"));
        try {
            FileTreeCodec.pack(directoryLink, directory.resolve("linked-root.litematic"));
            throw new AssertionError("Linked root directory was accepted");
        } catch (IOException expected) {
            require(!Files.exists(directory.resolve("linked-root.litematic")), "Linked root produced an output");
        }
        rejectRestore(directory.resolve("tree.litematic"), directoryLink.resolve("linked-restore"));
        require(!Files.exists(directory.resolve("tree-source/linked-restore")), "Linked restore wrote outside staging");
        Path dangling = directory.resolve("dangling-output");
        Files.createSymbolicLink(dangling, directory.resolve("nonexistent-target"));
        rejectRestore(directory.resolve("tree.litematic"), dangling, false);
        require(Files.isSymbolicLink(dangling), "Dangling output link was overwritten");
    }

    private static void verifySourceChangeDetection(Path directory) throws IOException {
        Path source = directory.resolve("changing-source.bin");
        Files.write(source, new byte[]{1, 2, 3});
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        String hash = LitematicaFileCodec.sha256(source);
        Files.write(source, new byte[]{3, 2, 1});
        Files.setLastModifiedTime(source, attributes.lastModifiedTime());
        try {
            FileTreeCodec.copyVerifiedSource(source, attributes, hash, new ByteArrayOutputStream());
            throw new AssertionError("Same-size source modification was not detected");
        } catch (IOException expected) {
            require(expected.getMessage().contains("Source changed"), "Incorrect source-change rejection");
        }
    }

    private static void verifyMultiBarrelTree(Path directory) throws IOException {
        Path root = Files.createDirectory(directory.resolve("multi-tree-source"));
        Files.move(directory.resolve("multi-barrel.bin"), root.resolve("large.bin"));
        Files.createDirectory(root.resolve("empty"));
        Files.write(root.resolve("small.bin"), new byte[]{1, 2, 3});
        Path schematic = directory.resolve("multi-tree.litematic");
        FileTreeCodec.Summary packed = FileTreeCodec.pack(root, schematic);
        require(packed.archive().bookCount() == 28 && packed.archive().barrelCount() == 2,
                "Multi-barrel tree counts are incorrect");
        Path restored = directory.resolve("multi-tree-restored");
        FileTreeCodec.restore(schematic, restored);
        compareTrees(root, restored);
        require(FileTreeCodec.list(schematic).entries().size() == 3, "Multi-barrel tree listing differs");
        System.out.printf("Multi-barrel tree round trip passed: %d content bytes, 28 books, 2 barrels%n",
                packed.contentSize());
    }

    private static void verifyTreePages(Path schematic) throws IOException {
        NbtCompound root = NbtIo.readCompressed(schematic.toFile());
        NbtList entities = root.getCompound("Regions").getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE);
        int index = 0;
        for (int barrel = 0; barrel < entities.size(); barrel++) {
            NbtList items = entities.getCompound(barrel).getList("Items", NbtElement.COMPOUND_TYPE);
            for (int slot = 0; slot < items.size(); slot++, index++) {
                NbtCompound book = items.getCompound(slot);
                require(book.getString("id").equals("minecraft:written_book") && book.getByte("Count") == 1,
                        "Invalid tree book stack");
                NbtCompound tag = book.getCompound("tag");
                NbtCompound marker = tag.getCompound("CloudArchive");
                require(marker.getString("kind").equals(LitematicaFileCodec.TREE_FORMAT)
                        && marker.getInt("index") == index, "Invalid tree book marker");
                require(marker.contains("header") == (index == 0), "Tree header is duplicated");
                NbtList pages = tag.getList("pages", NbtElement.STRING_TYPE);
                require(!pages.isEmpty() && pages.size() <= 100, "Invalid tree page count");
                for (int page = 0; page < pages.size(); page++) {
                    String text = pages.getString(page);
                    require(text.length() <= LitematicaFileCodec.MAX_PAGE_CHARS
                            && new JsonPrimitive(text).toString().length() <= 32_767,
                            "Tree page exceeds capacity");
                    require(Base64.getUrlDecoder().decode(text).length <= LitematicaFileCodec.BYTES_PER_PAGE,
                            "Tree page decoded size exceeds capacity");
                }
            }
        }
    }

    private static void writeRaw(Path payload, List<Node> nodes, byte[] data, long nextId) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(payload))) {
            output.writeInt(TreeArchiveFormat.MAGIC);
            output.writeShort(1);
            output.writeShort(0);
            output.writeLong(0);
            output.writeLong(nextId);
            output.writeInt(nodes.size());
            output.writeLong(TreeArchiveFormat.indexSize(nodes));
            output.writeLong(data.length);
            output.write(data);
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
    }

    private static void rejectPayload(Path directory, Path payload, String name) throws IOException {
        Path schematic = directory.resolve(name + ".litematic");
        LitematicaFileCodec.packPayload(payload, schematic, name, LitematicaFileCodec.TREE_FORMAT);
        rejectRestore(schematic, directory.resolve(name + "-restored"));
        try {
            FileTreeCodec.list(schematic);
            throw new AssertionError("Listing accepted a malformed tree");
        } catch (IOException expected) {
            // A valid outer SHA-256 must not allow malformed inner metadata.
        }
    }

    private static void rejectRestore(Path input, Path output) throws IOException {
        rejectRestore(input, output, true);
    }

    private static void rejectRestore(Path input, Path output, boolean expectAbsent) throws IOException {
        try {
            FileTreeCodec.restore(input, output);
            throw new AssertionError("Invalid restore was accepted: " + input);
        } catch (IOException expected) {
            if (expectAbsent) {
                require(!Files.exists(output), "Failed tree restore left an output");
            }
        }
    }

    private static NbtList firstItems(NbtCompound root) {
        return root.getCompound("Regions").getCompound("FileStorage")
                .getList("TileEntities", NbtElement.COMPOUND_TYPE).getCompound(0)
                .getList("Items", NbtElement.COMPOUND_TYPE);
    }

    private static void compareTrees(Path source, Path restored) throws IOException {
        List<Path> sourcePaths;
        List<Path> restoredPaths;
        try (Stream<Path> paths = Files.walk(source)) {
            sourcePaths = paths.map(source::relativize).sorted().toList();
        }
        try (Stream<Path> paths = Files.walk(restored)) {
            restoredPaths = paths.map(restored::relativize).sorted().toList();
        }
        require(sourcePaths.equals(restoredPaths), "Restored tree structure differs");
        for (Path relative : sourcePaths) {
            Path original = source.resolve(relative);
            Path actual = restored.resolve(relative);
            require(Files.isDirectory(original) == Files.isDirectory(actual), "Restored node type differs");
            if (Files.isRegularFile(original)) {
                require(Files.mismatch(original, actual) == -1, "Restored tree file bytes differ: " + relative);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
