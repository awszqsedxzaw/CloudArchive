package awszqsedxzaw.cloudarchive.codec;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

public final class FileCodecCli {
    private FileCodecCli() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && (args[0].equals("help") || args[0].equals("--help"))) {
                printUsage();
                return;
            }

            if (args.length != 3) {
                printUsage();
                System.exit(2);
                return;
            }

            switch (args[0]) {
                case "pack" -> {
                    Path schematic = LitematicaFileCodec.normalizeSchematicPath(Path.of(args[2]));
                    LitematicaFileCodec.ArchiveInfo info =
                            LitematicaFileCodec.pack(Path.of(args[1]), schematic);
                    System.out.printf(
                            "Packed %d bytes into %d books in %d barrels: %s%n",
                            info.fileSize(), info.bookCount(), info.barrelCount(), schematic.toAbsolutePath()
                    );
                }
                case "restore" -> {
                    LitematicaFileCodec.ArchiveInfo info =
                            LitematicaFileCodec.restore(Path.of(args[1]), Path.of(args[2]));
                    System.out.printf(
                            "Restored %d bytes from %d books (%s): %s%n",
                            info.fileSize(), info.bookCount(), info.fileName(), Path.of(args[2]).toAbsolutePath()
                    );
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

    private static void printUsage() {
        System.out.println("""
                Usage:
                  gradlew runFileCodec --args="pack <input-file> <output.litematic>"
                  gradlew runFileCodec --args="restore <input.litematic> <output-file>"
                """);
    }
}
