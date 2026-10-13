package awszqsedxzaw.cloudarchive.codec;

import static awszqsedxzaw.cloudarchive.codec.FileCodecSelfTest.expectFailure;
import static awszqsedxzaw.cloudarchive.codec.FileCodecSelfTest.require;

import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.ArchiveKind;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Extent;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Node;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

final class TreeCodecSelfTest {
    private TreeCodecSelfTest() {
    }

    static void run(Path work) throws IOException {
        verifyEmptyTree(work);
        verifyTreeAndSelections(work);
        verifyManyFiles(work);
        verifyMetadataAcrossBooks(work);
        verifyExtentAddressing(work);
        verifyMalformedMetadata(work);
        verifySafety(work);
        verifyMultiBarrelTree(work);
        System.out.println("File tree, metadata isolation, future extent and safety checks passed");
    }

    private static void verifyEmptyTree(Path work) throws IOException {
        Path source = Files.createDirectory(work.resolve("empty-tree-source"));
        Path archive = work.resolve("empty-tree.litematic");
        FileTreeCodec.Summary info = FileTreeCodec.pack(source, archive);
        require(info.fileCount() == 0 && info.contentSize() == 0 && info.archive().dataBookCount() == 0
                && info.archive().barrelCount() == 1 && info.archive().metadataBookCount() == 1,
                "Empty tree must have only a metadata barrel");
        require(FileTreeCodec.list(archive).entries().isEmpty(), "Empty tree listing differs");
        Path output = work.resolve("empty-tree-output");
        FileTreeCodec.restore(archive, output);
        compareTrees(source, output);
        expectFailure(() -> LitematicaFileCodec.restore(archive, work.resolve("wrong-kind")),
                "Single-file API accepted a tree");
    }

    private static void verifyTreeAndSelections(Path work) throws IOException {
        Path source = Files.createDirectory(work.resolve("tree-source"));
        Path nested = Files.createDirectory(source.resolve("nested-\u6570\u636e"));
        Files.createDirectories(nested.resolve("empty/deeper"));
        Files.write(nested.resolve("empty.bin"), new byte[0]);
        Files.writeString(nested.resolve("notes.txt"), "line one\r\nline two\n", StandardCharsets.UTF_8);
        Files.write(nested.resolve("large file.bin"),
                FileCodecSelfTest.randomBytes((int) LitematicaFileCodec.BYTES_PER_BOOK + 51, 27));
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        Files.write(source.resolve("all-bytes.bin"), bytes);
        Files.write(source.resolve("emoji-\ud83d\udce6.bin"), bytes);
        Path archive = work.resolve("tree.litematic");
        FileTreeCodec.Summary info = FileTreeCodec.pack(source, archive);
        require(info.fileCount() == 5 && info.directoryCount() == 3 && info.archive().dataBookCount() == 2
                && info.archive().metadataBookCount() == 1, "Tree counts differ");
        Path output = work.resolve("tree-output");
        FileTreeCodec.restore(archive, output);
        compareTrees(source, output);
        require(FileTreeCodec.list(archive).entries().size() == 8, "Tree listing differs");
        verifyPureData(work, archive, source);

        List<Path> selections = List.of(Path.of("all-bytes.bin"), Path.of("nested-\u6570\u636e/notes.txt"),
                Path.of("all-bytes.bin"));
        Path selected = work.resolve("selected.litematic");
        FileTreeCodec.Summary subset = FileTreeCodec.pack(source, selections, selected);
        require(subset.fileCount() == 2 && subset.directoryCount() == 1, "Selection counts differ");
        Path selectedOutput = work.resolve("selected-output");
        FileTreeCodec.restore(selected, selectedOutput);
        require(Files.mismatch(source.resolve("all-bytes.bin"), selectedOutput.resolve("all-bytes.bin")) == -1,
                "Selected file bytes differ");
        require(!Files.exists(selectedOutput.resolve("nested-\u6570\u636e/large file.bin")),
                "Unselected file was included");
        FileTreeCodec.Summary overlapping = FileTreeCodec.pack(source,
                List.of(Path.of("nested-\u6570\u636e"), Path.of("nested-\u6570\u636e/notes.txt")),
                work.resolve("overlapping.litematic"));
        require(overlapping.fileCount() == 3 && overlapping.directoryCount() == 3, "Overlap was not deduplicated");

        Path reversed = work.resolve("reverse-selection.litematic");
        FileTreeCodec.pack(source, List.of(Path.of("nested-\u6570\u636e/notes.txt"), Path.of("all-bytes.bin")), reversed);
        Path m1 = work.resolve("selected-metadata"), d1 = work.resolve("selected-data");
        Path m2 = work.resolve("reversed-metadata"), d2 = work.resolve("reversed-data");
        LitematicaFileCodec.restoreTransport(selected, m1, d1);
        LitematicaFileCodec.restoreTransport(reversed, m2, d2);
        require(Files.mismatch(m1, m2) == -1 && Files.mismatch(d1, d2) == -1, "Selection order changed content");

        Path one = work.resolve("one-selection.litematic");
        FileTreeCodec.pack(source, List.of(Path.of("all-bytes.bin")), one);
        Path oneOutput = work.resolve("one-selection-output");
        FileTreeCodec.restore(one, oneOutput);
        require(Files.isDirectory(oneOutput) && Files.exists(oneOutput.resolve("all-bytes.bin")),
                "One selected file must remain a tree archive");
    }

    private static void verifyPureData(Path work, Path archive, Path source) throws IOException {
        NbtCompound root = NbtIo.readCompressed(archive.toFile());
        FileCodecSelfTest.verifySchematic(root, 2);
        Path metadata = work.resolve("tree-pure-metadata"), data = work.resolve("tree-pure-data");
        LitematicaFileCodec.DecodedArchive decoded = LitematicaFileCodec.restoreTransport(archive, metadata, data);
        require(Files.size(metadata) == TreeArchiveFormat.indexSize(
                decoded.manifest().archiveName(), decoded.manifest().nodes(), decoded.manifest().blocks()),
                "Metadata contains data bytes");
        byte[] raw = Files.readAllBytes(data);
        for (Node node : decoded.manifest().nodes()) {
            if (!node.directory()) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                for (Extent extent : node.extents()) {
                    content.write(raw, (int) extent.offset(), (int) extent.length());
                }
                require(java.util.Arrays.equals(content.toByteArray(),
                        Files.readAllBytes(source.resolve(decoded.manifest().paths().get(node.id())))),
                        "Data extents contain non-file bytes");
            }
        }
    }

    private static void verifyManyFiles(Path work) throws IOException {
        Path source = Files.createDirectory(work.resolve("many-files"));
        for (int i = 0; i < 1500; i++) {
            Files.write(source.resolve(String.format("tiny-%04d.bin", i)), new byte[]{(byte) i});
        }
        Path archive = work.resolve("many-files.litematic");
        FileTreeCodec.Summary info = FileTreeCodec.pack(source, archive);
        require(info.fileCount() == 1500 && info.archive().dataBookCount() == 1
                && info.archive().metadataBookCount() == 1 && info.archive().barrelCount() == 2,
                "Tiny files do not share one pure-data book");
        NbtCompound root = NbtIo.readCompressed(archive.toFile());
        require(FileCodecSelfTest.pages(FileCodecSelfTest.items(root, 0).getCompound(0)).size() > 1,
                "Metadata fixture must cross pages");
        Path output = work.resolve("many-files-output");
        FileTreeCodec.restore(archive, output);
        compareTrees(source, output);
        System.out.println("1,500-file round trip passed: metadata crosses pages; data uses one book");
    }

    private static void verifyMetadataAcrossBooks(Path work) throws IOException {
        Path data = work.resolve("metadata-many-books-data");
        Files.write(data, new byte[0]);
        List<Node> nodes = new ArrayList<>();
        for (int i = 1; i <= 12_000; i++) {
            nodes.add(new Node(i, 0, "folder-" + i + "-" + "x".repeat(200), true, 0, "", List.of()));
        }
        Path metadata = work.resolve("metadata-many-books");
        TreeArchiveFormat.write(metadata, ArchiveKind.TREE, "metadata-many-books", nodes, 0,
                LitematicaFileCodec.sha256(data), 12_001);
        Path archive = work.resolve("metadata-many-books.litematic");
        LitematicaFileCodec.ArchiveInfo info = LitematicaFileCodec.packArchive(metadata, data, archive, ArchiveKind.TREE);
        require(info.metadataBookCount() == 2 && info.dataBookCount() == 0 && info.barrelCount() == 1,
                "Metadata crossed into a data barrel");
        Path restoredMetadata = work.resolve("metadata-many-books-restored");
        LitematicaFileCodec.restoreTransport(archive, restoredMetadata, work.resolve("metadata-empty-data-restored"));
        require(Files.mismatch(metadata, restoredMetadata) == -1, "Cross-book metadata differs");
        require(FileTreeCodec.list(archive).entries().size() == 12_000, "Cross-book metadata listing differs");
        try (RandomAccessFile oversized = new RandomAccessFile(work.resolve("oversized-metadata").toFile(), "rw")) {
            oversized.setLength(LitematicaFileCodec.BYTES_PER_BARREL + 1);
        }
        expectFailure(() -> LitematicaFileCodec.packArchive(work.resolve("oversized-metadata"), data,
                work.resolve("oversized-metadata.litematic"), ArchiveKind.TREE), "More than one metadata barrel accepted");
        require(!Files.exists(work.resolve("oversized-metadata.litematic")), "Oversized metadata produced output");
        System.out.println("12,000-directory metadata passed: 2 books in one metadata barrel; overflow rejected");
    }

    private static void verifyExtentAddressing(Path work) throws IOException {
        Path data = work.resolve("extent-data");
        Files.writeString(data, "01234567", StandardCharsets.US_ASCII);
        Path expectedFile = work.resolve("extent-expected");
        Files.writeString(expectedFile, "567123", StandardCharsets.US_ASCII);
        List<Node> nodes = new ArrayList<>(List.of(
                new Node(17, 0, "fragmented.bin", false, 6, LitematicaFileCodec.sha256(expectedFile),
                        List.of(new Extent(5, 3), new Extent(1, 3))),
                new Node(42, 0, "empty", true, 0, "", List.of())));
        Path metadata = work.resolve("extent-metadata");
        TreeArchiveFormat.write(metadata, ArchiveKind.TREE, "extents", nodes, 8,
                LitematicaFileCodec.sha256(data), 43);
        try (RandomAccessFile file = new RandomAccessFile(metadata.toFile(), "rw")) {
            file.seek(12);
            file.writeLong(7);
        }
        Path archive = work.resolve("extents.litematic");
        LitematicaFileCodec.packArchive(metadata, data, archive, ArchiveKind.TREE);
        Path output = work.resolve("extents-output");
        FileTreeCodec.restore(archive, output);
        require(Files.mismatch(expectedFile, output.resolve("fragmented.bin")) == -1,
                "Non-contiguous extents lost logical order or used old header offsets");
        require(Files.isDirectory(output.resolve("empty")), "Empty directory missing");
        require(FileTreeCodec.list(archive).entries().get(0).id() == 17
                && TreeArchiveFormat.read(metadata).generation() == 7, "Stable ID/generation were discarded");
        nodes.add(new Node(43, 0, "another", true, 0, "", List.of()));
        Path expanded = work.resolve("expanded-metadata");
        TreeArchiveFormat.write(expanded, ArchiveKind.TREE, "expanded", nodes, 8,
                LitematicaFileCodec.sha256(data), 44);
        Path expandedArchive = work.resolve("expanded.litematic");
        LitematicaFileCodec.packArchive(expanded, data, expandedArchive, ArchiveKind.TREE);
        Path expandedOutput = work.resolve("expanded-output");
        FileTreeCodec.restore(expandedArchive, expandedOutput);
        require(Files.mismatch(output.resolve("fragmented.bin"), expandedOutput.resolve("fragmented.bin")) == -1,
                "Metadata growth moved data addresses");
    }

    private static void verifyMalformedMetadata(Path work) throws IOException {
        String emptyHash = LitematicaFileCodec.sha256(work.resolve("empty.bin"));
        List<List<Node>> invalid = new ArrayList<>();
        for (String name : List.of("..", "../escape", "/absolute", "NUL", "CON .txt", "bad:name", "trailing.",
                "trailing ", "a/b", "a\\b", "\ud800")) {
            invalid.add(List.of(new Node(1, 0, name, true, 0, "", List.of())));
        }
        invalid.add(List.of(new Node(1, 0, "a", true, 0, "", List.of()),
                new Node(1, 0, "b", true, 0, "", List.of())));
        invalid.add(List.of(new Node(1, 0, "Case", true, 0, "", List.of()),
                new Node(2, 0, "case", true, 0, "", List.of())));
        invalid.add(List.of(new Node(1, 2, "child", true, 0, "", List.of())));
        invalid.add(List.of(new Node(1, 0, "file", false, 0, emptyHash, List.of()),
                new Node(2, 1, "child", true, 0, "", List.of())));
        invalid.add(List.of(new Node(-1, 0, "negative-id", true, 0, "", List.of())));
        invalid.add(List.of(new Node(1, 0, "outside", false, 2, emptyHash, List.of(new Extent(3, 2)))));
        invalid.add(List.of(new Node(1, 0, "negative-offset", false, 1, emptyHash, List.of(new Extent(-1, 1)))));
        invalid.add(List.of(new Node(1, 0, "zero-extent", false, 0, emptyHash, List.of(new Extent(0, 0)))));
        invalid.add(List.of(new Node(1, 0, "wrong-size", false, 2, emptyHash, List.of(new Extent(0, 1)))));
        for (List<Node> nodes : invalid) {
            expectFailure(() -> TreeArchiveFormat.validate(ArchiveKind.TREE, "bad", nodes, 4, 100),
                    "Malformed node accepted");
        }
        Path metadata = work.resolve("valid-metadata");
        TreeArchiveFormat.write(metadata, ArchiveKind.TREE, "test",
                List.of(new Node(1, 0, "aa", true, 0, "", List.of())), 0, emptyHash, 2);
        byte[] valid = Files.readAllBytes(metadata);
        for (int test = 0; test < 8; test++) {
            byte[] bytes = valid.clone();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int record = TreeArchiveFormat.HEADER_BYTES + 4;
            switch (test) {
                case 0 -> buffer.put(record, (byte) 2);
                case 1 -> buffer.putLong(record + 1, 0);
                case 2 -> buffer.putLong(record + 9, 1);
                case 3 -> buffer.putInt(record + 17, Integer.MAX_VALUE);
                case 4 -> { buffer.put(record + 21, (byte) 0xC0); buffer.put(record + 22, (byte) 0xAF); }
                case 5 -> buffer.putInt(28, 0);
                case 6 -> bytes = java.util.Arrays.copyOf(bytes, bytes.length - 1);
                case 7 -> { bytes = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                    ByteBuffer.wrap(bytes).putLong(32, bytes.length); }
                default -> throw new AssertionError();
            }
            Path bad = work.resolve("bad-node-" + test);
            Files.write(bad, bytes);
            expectFailure(() -> TreeArchiveFormat.read(bad), "Malformed serialized node accepted");
        }
    }

    private static void verifySafety(Path work) throws IOException {
        Path source = work.resolve("tree-source");
        Path archive = work.resolve("tree.litematic");
        for (List<Path> selections : List.of(List.of(Path.of("../outside")),
                List.of(source.resolve("all-bytes.bin")), List.of(Path.of("missing")))) {
            expectFailure(() -> FileTreeCodec.pack(source, selections, work.resolve("bad-selection.litematic")),
                    "Invalid selection accepted");
        }
        expectFailure(() -> FileTreeCodec.pack(source, source.resolve("recursive.litematic")),
                "Output inside source accepted");
        Path existing = Files.createDirectory(work.resolve("existing-output"));
        Files.writeString(existing.resolve("sentinel"), "unchanged");
        expectFailure(() -> FileTreeCodec.restore(archive, existing), "Existing tree overwritten");
        require(Files.readString(existing.resolve("sentinel")).equals("unchanged"), "Existing data changed");
        Path changing = work.resolve("changing.bin");
        Files.write(changing, new byte[]{1, 2, 3});
        BasicFileAttributes attributes = Files.readAttributes(changing, BasicFileAttributes.class);
        Files.write(changing, new byte[]{3, 2, 1});
        Files.setLastModifiedTime(changing, attributes.lastModifiedTime());
        expectFailure(() -> FileTreeCodec.pack(changing.getParent(), List.of(changing.getFileName()),
                work.resolve("changed.litematic")), "Same-size source modification not detected");
        Path linkRoot = Files.createDirectory(work.resolve("link-root"));
        try {
            Files.createSymbolicLink(linkRoot.resolve("link"), changing);
        } catch (IOException | UnsupportedOperationException exception) {
            System.out.println("Symbolic link fixture unavailable on this host");
            return;
        }
        expectFailure(() -> FileTreeCodec.pack(linkRoot, work.resolve("link.litematic")), "Symbolic link accepted");
        expectFailure(() -> LitematicaFileCodec.pack(linkRoot.resolve("link"), work.resolve("single-link.litematic")),
                "Single-file symbolic link accepted");
        Path linkedDirectory = work.resolve("linked-directory");
        Files.createSymbolicLink(linkedDirectory, source);
        expectFailure(() -> FileTreeCodec.restore(archive, linkedDirectory.resolve("output")), "Linked output accepted");
        require(!Files.exists(source.resolve("output")), "Linked output wrote outside staging");
    }

    private static void verifyMultiBarrelTree(Path work) throws IOException {
        Path source = Files.createDirectory(work.resolve("multi-tree-source"));
        Files.move(work.resolve("multi-barrel.bin"), source.resolve("large.bin"));
        Files.createDirectory(source.resolve("empty"));
        Files.write(source.resolve("small.bin"), new byte[]{1, 2, 3});
        Path archive = work.resolve("multi-tree.litematic");
        FileTreeCodec.Summary info = FileTreeCodec.pack(source, archive);
        require(info.archive().dataBookCount() == 28 && info.archive().metadataBookCount() == 1
                && info.archive().barrelCount() == 3, "Large tree did not reserve a metadata barrel");
        Path output = work.resolve("multi-tree-output");
        FileTreeCodec.restore(archive, output);
        compareTrees(source, output);
        require(FileTreeCodec.list(archive).entries().size() == 3, "Large tree listing differs");
        System.out.printf("Multi-barrel tree round trip passed: %d data bytes, 3 barrels%n", info.contentSize());
    }

    private static void compareTrees(Path source, Path output) throws IOException {
        List<Path> before, after;
        try (Stream<Path> paths = Files.walk(source)) {
            before = paths.map(source::relativize).sorted().toList();
        }
        try (Stream<Path> paths = Files.walk(output)) {
            after = paths.map(output::relativize).sorted().toList();
        }
        require(before.equals(after), "Restored tree structure differs");
        for (Path relative : before) {
            Path original = source.resolve(relative), restored = output.resolve(relative);
            require(Files.isDirectory(original) == Files.isDirectory(restored), "Restored node type differs");
            if (Files.isRegularFile(original)) {
                require(Files.mismatch(original, restored) == -1, "Restored bytes differ: " + relative);
            }
        }
    }
}
