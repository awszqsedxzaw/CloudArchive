package awszqsedxzaw.cloudarchive.codec;

import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Extent;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Manifest;
import awszqsedxzaw.cloudarchive.codec.TreeArchiveFormat.Node;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
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
        long contentSize = 0;
        List<Node> nodes = new ArrayList<>();
        Map<Path, Long> ids = new HashMap<>();
        ids.put(source, 0L);
        long nextId = 1;
        for (Path path : snapshot.paths()) {
            BasicFileAttributes attributes = snapshot.attributes().get(path);
            TreeArchiveFormat.validateName(path.getFileName().toString());
            Long parentId = ids.get(path.getParent());
            if (parentId == null) {
                throw new IOException("Source parent directory is missing");
            }
            long size = attributes.isDirectory() ? 0 : attributes.size();
            List<Extent> extents = size == 0 ? List.of() : List.of(new Extent(contentSize, size));
            nodes.add(new Node(nextId, parentId, path.getFileName().toString(), attributes.isDirectory(),
                    size, extents));
            ids.put(path, nextId++);
            contentSize = TreeArchiveFormat.add(contentSize, size);
        }
        long payloadSize = TreeArchiveFormat.add(TreeArchiveFormat.indexSize(nodes), contentSize);
        LitematicaFileCodec.bookCountForSize(payloadSize);
        TreeArchiveFormat.validate(nodes, contentSize, nextId);
        Path work = Files.createTempDirectory(".cloudarchive-tree-");
        try {
            Path payload = work.resolve("payload");
            try (DataOutputStream stream = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(payload, StandardOpenOption.CREATE_NEW)))) {
                TreeArchiveFormat.writeHeader(stream, nodes, contentSize, nextId);
                for (Path path : snapshot.paths()) {
                    if (!snapshot.attributes().get(path).isDirectory()) {
                        copyVerifiedSource(path, snapshot.attributes().get(path), snapshot.hashes().get(path), stream);
                    }
                }
                TreeArchiveFormat.writeNodes(stream, nodes);
            }
            String name = source.getFileName() == null ? "File tree" : source.getFileName().toString();
            LitematicaFileCodec.ArchiveInfo archive = LitematicaFileCodec.packPayload(
                    payload, destination, name, LitematicaFileCodec.TREE_FORMAT);
            return summary(archive, nodes);
        } finally {
            deleteWorkDirectory(work);
        }
    }

    // Reconstruct the transport once, then dispatch by its explicit format marker.
    public static Summary restore(Path input, Path output) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(destination.toString());
        }
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent directory");
        }
        Files.createDirectories(parent);
        rejectLinkedAncestors(parent);
        Path work = Files.createTempDirectory(parent, ".cloudarchive-tree-");
        try {
            Path payload = work.resolve("payload");
            LitematicaFileCodec.ArchiveInfo archive = LitematicaFileCodec.restorePayload(input, payload, null);
            if (archive.format().equals(LitematicaFileCodec.FILE_FORMAT)) {
                Files.move(payload, destination);
                return new Summary(archive, 1, 0, archive.fileSize());
            }
            Manifest manifest = TreeArchiveFormat.read(payload);
            Summary result = summary(archive, manifest.nodes());
            Path tree = Files.createDirectory(work.resolve("tree"));
            extract(payload, manifest, tree);
            Files.move(tree, destination);
            return result;
        } finally {
            deleteWorkDirectory(work);
        }
    }

    public static Listing list(Path input) throws IOException {
        Path work = Files.createTempDirectory(".cloudarchive-tree-");
        try {
            Path payload = work.resolve("payload");
            LitematicaFileCodec.ArchiveInfo archive = LitematicaFileCodec.restorePayload(input, payload, null);
            if (archive.format().equals(LitematicaFileCodec.FILE_FORMAT)) {
                return new Listing(new Summary(archive, 1, 0, archive.fileSize()),
                        List.of(new Entry(1, 0, archive.fileName(), false, archive.fileSize())));
            }
            Manifest manifest = TreeArchiveFormat.read(payload);
            List<Entry> entries = new ArrayList<>();
            for (Node node : manifest.nodes()) {
                entries.add(new Entry(node.id(), node.parentId(),
                        manifest.paths().get(node.id()).toString().replace('\\', '/'),
                        node.directory(), node.size()));
            }
            return new Listing(summary(archive, manifest.nodes()), List.copyOf(entries));
        } finally {
            deleteWorkDirectory(work);
        }
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
        Map<Path, String> hashes = new HashMap<>();
        for (Path path : paths) {
            rejectLinkedAncestors(path);
            BasicFileAttributes before = readSourceAttributes(path);
            attributes.put(path, before);
            if (before.isRegularFile()) {
                hashes.put(path, LitematicaFileCodec.sha256(path));
                requireUnchanged(path, before);
            }
        }
        return new Snapshot(List.copyOf(paths), attributes, hashes);
    }

    static void copyVerifiedSource(
            Path source, BasicFileAttributes expected, String expectedHash, OutputStream output
    ) throws IOException {
        requireUnchanged(source, expected);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        long size = 0;
        try (InputStream input = Files.newInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
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
        if (size != expected.size() || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) {
            throw new IOException("Source changed while packing: " + source);
        }
    }

    private static BasicFileAttributes readSourceAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()
                || !(attributes.isDirectory() || attributes.isRegularFile())) {
            throw new IOException("Links and special files are not supported: " + path);
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
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not supported: " + current);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther()) {
                    throw new IOException("Special paths are not supported: " + current);
                }
            }
        }
    }

    private static void extract(Path payload, Manifest manifest, Path root) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try (FileChannel source = FileChannel.open(payload, StandardOpenOption.READ)) {
            for (Node node : manifest.nodes()) {
                Path target = root.resolve(manifest.paths().get(node.id())).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Tree path escapes the output directory");
                }
                if (node.directory()) {
                    Files.createDirectory(target);
                    continue;
                }
                try (FileChannel output = FileChannel.open(target,
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
                    for (Extent extent : node.extents()) {
                        source.position(TreeArchiveFormat.HEADER_BYTES + extent.offset());
                        long remaining = extent.length();
                        while (remaining > 0) {
                            buffer.clear();
                            buffer.limit((int) Math.min(buffer.capacity(), remaining));
                            int count = source.read(buffer);
                            if (count < 0) {
                                throw new IOException("Tree extent data is truncated");
                            }
                            buffer.flip();
                            while (buffer.hasRemaining()) {
                                output.write(buffer);
                            }
                            remaining -= count;
                        }
                    }
                }
            }
        }
    }

    private static Summary summary(LitematicaFileCodec.ArchiveInfo archive, List<Node> nodes) throws IOException {
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

    private record Snapshot(List<Path> paths, Map<Path, BasicFileAttributes> attributes, Map<Path, String> hashes) {
    }

    public record Summary(LitematicaFileCodec.ArchiveInfo archive, int fileCount, int directoryCount,
                          long contentSize) {
    }

    public record Entry(long id, long parentId, String path, boolean directory, long size) {
    }

    public record Listing(Summary summary, List<Entry> entries) {
    }
}
