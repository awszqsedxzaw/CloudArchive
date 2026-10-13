package awszqsedxzaw.cloudarchive.codec;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;

public final class FileCodecCli {
    private FileCodecCli() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && (args[0].equals("help") || args[0].equals("--help"))) {
                printUsage();
                return;
            }

            if (args.length == 0) {
                printUsage();
                System.exit(2);
                return;
            }

            switch (args[0]) {
                case "pack" -> {
                    requireArguments(args, 3);
                    Path schematic = LitematicaFileCodec.normalizeSchematicPath(Path.of(args[2]));
                    if (Files.isDirectory(Path.of(args[1]), LinkOption.NOFOLLOW_LINKS)) {
                        printTreePack(FileTreeCodec.pack(Path.of(args[1]), schematic), schematic);
                        break;
                    }
                    LitematicaFileCodec.ArchiveInfo info =
                            LitematicaFileCodec.pack(Path.of(args[1]), schematic);
                    printPack(info, 1, 0, info.dataSize(), schematic);
                }
                case "pack-tree" -> {
                    if (args.length < 3) {
                        throw new IllegalArgumentException("pack-tree requires a root and output path");
                    }
                    Path schematic = LitematicaFileCodec.normalizeSchematicPath(Path.of(args[2]));
                    printTreePack(FileTreeCodec.pack(Path.of(args[1]),
                            Arrays.stream(args).skip(3).map(Path::of).toList(), schematic), schematic);
                }
                case "restore" -> {
                    requireArguments(args, 3);
                    FileTreeCodec.Summary summary = FileTreeCodec.restore(Path.of(args[1]), Path.of(args[2]));
                    LitematicaFileCodec.ArchiveInfo info = summary.archive();
                    System.out.printf(
                            "Restored %d bytes in %d files and %d directories from %d books: %s%n",
                            summary.contentSize(), summary.fileCount(), summary.directoryCount(),
                            info.totalBookCount(), Path.of(args[2]).toAbsolutePath()
                    );
                }
                case "list" -> {
                    requireArguments(args, 2);
                    FileTreeCodec.Listing listing = FileTreeCodec.list(Path.of(args[1]));
                    System.out.printf("%d files, %d directories, %d content bytes%n",
                            listing.summary().fileCount(), listing.summary().directoryCount(),
                            listing.summary().contentSize());
                    printLayout(listing.summary().archive());
                    for (FileTreeCodec.Entry entry : listing.entries()) {
                        System.out.printf("%s %12d %s%n", entry.directory() ? "dir " : "file",
                                entry.size(), entry.path());
                    }
                }
                default -> {
                    printUsage();
                    System.exit(2);
                }
            }
        } catch (FileAlreadyExistsException exception) {
            System.err.println("Output already exists; choose a new output path: " + exception.getFile());
            System.exit(1);
        } catch (Exception exception) {
            System.err.println("File codec failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void requireArguments(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException(args[0] + " expects " + (count - 1) + " arguments");
        }
    }

    private static void printTreePack(FileTreeCodec.Summary summary, Path schematic) {
        printPack(summary.archive(), summary.fileCount(), summary.directoryCount(), summary.contentSize(), schematic);
    }

    private static void printPack(
            LitematicaFileCodec.ArchiveInfo info, int files, int directories, long contentSize, Path schematic
    ) {
        System.out.printf("Packed %d bytes in %d files and %d directories: %s%n",
                contentSize, files, directories, schematic.toAbsolutePath());
        printLayout(info);
    }

    private static void printLayout(LitematicaFileCodec.ArchiveInfo info) {
        System.out.printf("Unique data: %d bytes; stored data after compression: %d bytes%n",
                info.dataSize(), info.storedSize());
        System.out.printf("Metadata: %d books in 1 barrel; data: %d books in %d barrels; total: %d barrels%n",
                info.metadataBookCount(), info.dataBookCount(), info.barrelCount() - 1, info.barrelCount());
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  gradlew runFileCodec --args="pack <input-file-or-directory> <output.litematic>"
                  gradlew runFileCodec --args="pack-tree <root-directory> <output.litematic> [relative-path ...]"
                  gradlew runFileCodec --args="list <input.litematic>"
                  gradlew runFileCodec --args="restore <input.litematic> <output-file-or-directory>"
                """);
    }
}
