package awszqsedxzaw.cloudarchive.codec;

import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.ArchiveKind;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Extent;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Manifest;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Node;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public final class FileTreeCodec {
    private FileTreeCodec() {
    }

    public static Summary pack(Path root, Path output) throws IOException {
        return pack(root, List.of(), output);
    }

    public static Summary pack(Path root, List<Path> selections, Path output) throws IOException {
        Path source = root.toAbsolutePath().normalize();
        rejectLinkedAncestors(source);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tree root is not a directory: " + source);
        }
        source = source.toRealPath();
        Path destination = LitematicaFileCodec.normalizeSchematicPath(output).toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        rejectLinkedAncestors(destination.getParent());
        if (destination.startsWith(source)) {
            throw new IOException("Tree output must be outside the source root");
        }
        Snapshot snapshot = collect(source, selections);
        return packSnapshot(source, snapshot, destination, ArchiveKind.TREE,
                source.getFileName() == null ? "File tree" : source.getFileName().toString());
    }

    static Summary packSingleFile(Path input, Path output) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        rejectLinkedAncestors(source);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Input is not a regular file: " + source);
        }
        Path destination = LitematicaFileCodec.normalizeSchematicPath(output).toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        rejectLinkedAncestors(destination.getParent());
        BasicFileAttributes attributes = readSourceAttributes(source);
        requireUnchanged(source, attributes);
        Snapshot snapshot = new Snapshot(List.of(source), Map.of(source, attributes), Map.of());
        return packSnapshot(source.getParent(), snapshot, destination, ArchiveKind.FILE,
                source.getFileName().toString());
    }

    private static Summary packSnapshot(
            Path root,
            Snapshot snapshot,
            Path destination,
            ArchiveKind kind,
            String archiveName
    ) throws IOException {
        long contentSize = 0;
        List<Node> nodes = new ArrayList<>();
        Map<Path, Long> ids = new HashMap<>();
        ids.put(root, 0L);
        Map<String, Extent> duplicateFiles = new HashMap<>();
        long nextId = 1;
        Path work = Files.createTempDirectory(".cloudarchive-tree-");
        try {
            Path metadata = work.resolve("metadata");
            Path stored = work.resolve("stored");
            HashSet<Long> seenSizes = new HashSet<>();
            DataCompression.Encoded compressed;
            String dataHash;
            byte[] copyBuffer = new byte[64 * 1024];
            try (DataCompression.Encoder encoder = new DataCompression.Encoder(stored)) {
                for (Path path : snapshot.paths()) {
                    BasicFileAttributes attributes = snapshot.attributes().get(path);
                    TreeArchiveFormat.validateName(path.getFileName().toString());
                    Long parentId = ids.get(path.getParent());
                    if (parentId == null) {
                        throw new IOException("Source parent directory is missing");
                    }
                    boolean directory = attributes.isDirectory();
                    long size = directory ? 0 : attributes.size();
                    String hash = "";
                    List<Extent> extents = List.of();
                    if (!directory) {
                        boolean candidate = size > 0 && seenSizes.contains(size);
                        if (candidate) {
                            encoder.checkpoint();
                        }
                        hash = copyAndHashSource(path, attributes, encoder, copyBuffer);
                        if (size > 0) {
                            String contentKey = hash + ":" + size;
                            Extent previous = duplicateFiles.get(contentKey);
                            if (previous == null) {
                                extents = List.of(new Extent(contentSize, size));
                                duplicateFiles.put(contentKey, extents.get(0));
                                encoder.commit();
                                contentSize = TreeArchiveFormat.add(contentSize, size);
                            } else {
                                extents = List.of(previous);
                                encoder.rollback();
                            }
                            seenSizes.add(size);
                        }
                    }
                    nodes.add(new Node(nextId, parentId, path.getFileName().toString(),
                            directory, size, hash, extents));
                    ids.put(path, nextId++);
                }
                compressed = encoder.finish();
                dataHash = encoder.sha256();
            }
            TreeArchiveFormat.validate(kind, archiveName, nodes, contentSize, nextId);
            TreeArchiveFormat.write(metadata, kind, archiveName, nodes, contentSize, dataHash, nextId,
                    compressed.storedLength(), compressed.blocks());
            LitematicaFileCodec.ArchiveInfo archive = LitematicaFileCodec.packArchive(
                    metadata, stored, destination, kind);
            return summary(archive, nodes);
        } finally {
            deleteWorkDirectory(work);
        }
    }

    public static Summary restore(Path input, Path output) throws IOException {
        return restore(input, output, false);
    }

    static Summary restoreSingle(Path input, Path output) throws IOException {
        return restore(input, output, true);
    }

    private static Summary restore(Path input, Path output, boolean singleFileOnly) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent directory");
        }
        rejectLinkedAncestors(parent);
        Files.createDirectories(parent);
        Path work = Files.createTempDirectory(parent, ".cloudarchive-tree-");
        try {
            LitematicaFileCodec.DecodedArchive decoded = decode(input, work);
            if (singleFileOnly && decoded.manifest().kind() != ArchiveKind.FILE) {
                throw new IOException("Input is a directory-tree archive, not a single-file archive");
            }
            if (decoded.manifest().kind() == ArchiveKind.FILE) {
                Node node = decoded.manifest().nodes().get(0);
                if (node.size() == 0 && node.extents().isEmpty()
                        || node.extents().equals(List.of(new Extent(0, node.size())))) {
                    verifyHash(node, decoded.manifest().dataSha256());
                    Files.move(work.resolve("data"), destination);
                } else {
                    Path staged = work.resolve("file");
                    extractSingle(decoded.manifest(), work.resolve("data"), staged);
                    Files.move(staged, destination);
                }
                return new Summary(decoded.info(), 1, 0, decoded.manifest().dataLength());
            }
            Path tree = Files.createDirectory(work.resolve("tree"));
            extract(decoded.manifest(), work.resolve("data"), tree);
            Files.move(tree, destination);
            return summary(decoded.info(), decoded.manifest().nodes());
        } finally {
            deleteWorkDirectory(work);
        }
    }

    public static Listing list(Path input) throws IOException {
        Path work = Files.createTempDirectory(".cloudarchive-tree-");
        try {
            LitematicaFileCodec.DecodedArchive decoded = decode(input, work);
            try (FileChannel source = FileChannel.open(work.resolve("data"), StandardOpenOption.READ)) {
                for (Node node : decoded.manifest().nodes()) {
                    if (!node.directory()) {
                        verifyHash(node, copyExtents(source, null, node.extents()));
                    }
                }
            }
            if (decoded.manifest().kind() == ArchiveKind.FILE) {
                return new Listing(new Summary(decoded.info(), 1, 0, decoded.manifest().dataLength()),
                        List.of(new Entry(decoded.manifest().nodes().get(0).id(), 0,
                                decoded.manifest().nodes().get(0).name(), false, decoded.manifest().dataLength())));
            }
            List<Entry> entries = new ArrayList<>();
            for (Node node : decoded.manifest().nodes()) {
                entries.add(new Entry(node.id(), node.parentId(),
                        decoded.manifest().paths().get(node.id()).toString().replace('\\', '/'),
                        node.directory(), node.size()));
            }
            return new Listing(summary(decoded.info(), decoded.manifest().nodes()), List.copyOf(entries));
        } finally {
            deleteWorkDirectory(work);
        }
    }

    private static LitematicaFileCodec.DecodedArchive decode(Path input, Path work) throws IOException {
        Path metadata = work.resolve("metadata");
        Path data = work.resolve("data");
        return LitematicaFileCodec.restoreTransport(input, metadata, data);
    }

    private static Snapshot collect(Path root, List<Path> selections) throws IOException {
        TreeSet<Path> paths = new TreeSet<>(Comparator.comparing(
                path -> root.relativize(path).toString().replace('\\', '/')));
        List<Path> selected = new ArrayList<>();
        if (selections.isEmpty()) {
            selected.add(root);
        } else {
            for (Path selection : selections) {
                if (selection.isAbsolute()) {
                    throw new IOException("Tree selections must be relative to the root");
                }
                for (Path part : selection) {
                    if (part.toString().equals("..")) {
                        throw new IOException("Tree selection escapes the root");
                    }
                }
                Path path = root.resolve(selection).normalize();
                if (!path.startsWith(root)) {
                    throw new IOException("Tree selection escapes the root");
                }
                rejectLinkedAncestors(path);
                selected.add(path.toRealPath());
            }
        }
        for (Path selection : selected) {
            Files.walkFileTree(selection, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    include(directory, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    include(file, attributes);
                    return FileVisitResult.CONTINUE;
                }

                private void include(Path path, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || attributes.isOther()
                            || !(attributes.isDirectory() || attributes.isRegularFile())) {
                        throw new IOException("Links and special files are not supported: " + path);
                    }
                    for (Path current = path; !current.equals(root); current = current.getParent()) {
                        paths.add(current);
                    }
                }
            });
        }
        Map<Path, BasicFileAttributes> attributes = new HashMap<>();
        for (Path path : paths) {
            rejectLinkedAncestors(path);
            BasicFileAttributes before = readSourceAttributes(path);
            attributes.put(path, before);
        }
        return new Snapshot(List.copyOf(paths), attributes, Map.of());
    }

    private static String copyAndHashSource(
            Path source, BasicFileAttributes expected, OutputStream output, byte[] buffer
    ) throws IOException {
        requireUnchanged(source, expected);
        MessageDigest digest = LitematicaFileCodec.newSha256();
        long size = 0;
        try (InputStream input = Files.newInputStream(source)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                size = TreeArchiveFormat.add(size, count);
                if (size > expected.size()) {
                    throw new IOException("Source changed while packing: " + source);
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        requireUnchanged(source, expected);
        if (size != expected.size()) {
            throw new IOException("Source changed while packing: " + source);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static BasicFileAttributes readSourceAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || !(attributes.isDirectory() || attributes.isRegularFile())) {
            throw new IOException("Links and special paths are not supported: " + path);
        }
        return attributes;
    }

    private static void requireUnchanged(Path path, BasicFileAttributes expected) throws IOException {
        rejectLinkedAncestors(path);
        BasicFileAttributes actual = readSourceAttributes(path);
        if (!actual.isRegularFile() || actual.size() != expected.size()
                || !actual.lastModifiedTime().equals(expected.lastModifiedTime())
                || !Objects.equals(actual.fileKey(), expected.fileKey())) {
            throw new IOException("Source changed while packing: " + path);
        }
    }

    private static void rejectLinkedAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("Links and special paths are not supported: " + current);
                }
            } catch (java.nio.file.NoSuchFileException ignored) {
                // Missing output ancestors will be created only after validation.
            }
        }
    }

    private static void extractSingle(Manifest manifest, Path data, Path output) throws IOException {
        Node node = manifest.nodes().get(0);
        try (FileChannel source = FileChannel.open(data, StandardOpenOption.READ);
             FileChannel target = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            verifyHash(node, copyExtents(source, target, node.extents()));
        }
    }

    private static void extract(Manifest manifest, Path data, Path root) throws IOException {
        try (FileChannel source = FileChannel.open(data, StandardOpenOption.READ)) {
            for (Node node : manifest.nodes()) {
                Path target = root.resolve(manifest.paths().get(node.id())).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Tree path escapes the output directory");
                }
                if (node.directory()) {
                    Files.createDirectory(target);
                } else {
                    try (FileChannel output = FileChannel.open(target,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        verifyHash(node, copyExtents(source, output, node.extents()));
                    }
                }
            }
        }
    }

    private static String copyExtents(FileChannel source, FileChannel output, List<Extent> extents)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        for (Extent extent : extents) {
            source.position(extent.offset());
            long remaining = extent.length();
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int count = source.read(buffer);
                if (count < 0) {
                    throw new IOException("Archive data extent is truncated");
                }
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                if (output != null) {
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                }
                remaining -= count;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyHash(Node node, String actual) throws IOException {
        if (!actual.equals(node.sha256())) {
            throw new IOException("File SHA-256 does not match metadata: " + node.name());
        }
    }

    private static Summary summary(LitematicaFileCodec.ArchiveInfo archive, List<Node> nodes)
            throws IOException {
        int files = 0;
        int directories = 0;
        long contentSize = 0;
        for (Node node : nodes) {
            if (node.directory()) {
                directories++;
            } else {
                files++;
                contentSize = TreeArchiveFormat.add(contentSize, node.size());
            }
        }
        return new Summary(archive, files, directories, contentSize);
    }

    private static void deleteWorkDirectory(Path work) throws IOException {
        Path absolute = work.toAbsolutePath().normalize();
        if (!absolute.getFileName().toString().startsWith(".cloudarchive-tree-")
                || Files.isSymbolicLink(absolute)
                || !absolute.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(absolute)) {
            throw new IOException("Refusing to delete an unexpected temporary directory: " + work);
        }
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record Snapshot(List<Path> paths, Map<Path, BasicFileAttributes> attributes,
                            Map<Path, String> hashes) {
    }

    public record Summary(LitematicaFileCodec.ArchiveInfo archive, int fileCount, int directoryCount,
                          long contentSize) {
    }

    public record Entry(long id, long parentId, String path, boolean directory, long size) {
    }

    public record Listing(Summary summary, List<Entry> entries) {
    }
}
