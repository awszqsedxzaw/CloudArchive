package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtType;
import net.minecraft.nbt.scanner.NbtCollector;

final class ArchiveBookScanner extends NbtCollector {
    private final BookConsumer consumer;
    private final ArchiveLayoutScanner.Layout layout;
    private String regionName;
    private NbtList blockEntities;
    private NbtCompound blockEntity;
    private NbtList items;
    private NbtCompound book;
    private boolean unsupportedBookField;
    private int barrelX;

    ArchiveBookScanner(ArchiveLayoutScanner.Layout layout, BookConsumer consumer) {
        this.layout = layout;
        this.consumer = consumer;
    }

    @Override
    public Result start(NbtType<?> type) {
        if (type != NbtCompound.TYPE) {
            throw new UncheckedIOException(new IOException("Schematic root is not a compound"));
        }
        return super.start(type);
    }

    @Override
    public NestedResult startSubNbt(NbtType<?> type, String key) {
        int depth = getDepth();
        boolean include;
        if (depth == 1) {
            include = key.equals("Version") && type == NbtInt.TYPE
                    || key.equals("Regions") && type == NbtCompound.TYPE;
        } else if (depth == 2) {
            include = key.equals(layout.region()) && type == NbtCompound.TYPE;
        } else if (depth == 3) {
            include = key.equals("TileEntities") && type == NbtList.TYPE;
        } else if (depth == 5) {
            include = key.equals("Items") && type == NbtList.TYPE;
        } else if (depth == 7) {
            include = key.equals("id") && type == NbtString.TYPE
                    || key.equals("Count") && type == NbtByte.TYPE
                    || key.equals("Slot") && type == NbtByte.TYPE
                    || key.equals("tag") && type == NbtCompound.TYPE;
            if (!include) {
                unsupportedBookField = true;
            }
        } else if (depth == 8) {
            include = switch (key) {
                case "author", "title" -> type == NbtString.TYPE;
                case "resolved" -> type == NbtByte.TYPE;
                case "pages" -> type == NbtList.TYPE;
                default -> false;
            };
            if (!include) {
                unsupportedBookField = true;
            }
        } else {
            include = false;
        }
        if (!include) {
            return NestedResult.SKIP;
        }
        if (depth == 2) {
            regionName = key;
        }
        NestedResult result = super.startSubNbt(type, key);
        if (depth == 3) {
            blockEntities = ((NbtCompound) getRoot()).getCompound("Regions")
                    .getCompound(regionName).getList("TileEntities", NbtElement.COMPOUND_TYPE);
        } else if (depth == 5) {
            items = blockEntity.getList("Items", NbtElement.COMPOUND_TYPE);
        }
        return result;
    }

    @Override
    public NestedResult startListItem(NbtType<?> type, int index) {
        int depth = getDepth();
        NestedResult result = super.startListItem(type, index);
        if (result == NestedResult.ENTER && depth == 4 && blockEntities != null) {
            if (index >= layout.barrelCount()) {
                throw new UncheckedIOException(new IOException("Archive layout changed during reading"));
            }
            barrelX = layout.positions()[index];
            blockEntity = blockEntities.getCompound(blockEntities.size() - 1);
        } else if (result == NestedResult.ENTER && depth == 6 && items != null) {
            book = items.getCompound(items.size() - 1);
            unsupportedBookField = false;
        }
        return result;
    }

    @Override
    public Result visitListMeta(NbtType<?> entryType, int length) {
        int depth = getDepth();
        if (length < 1 || (depth == 4 || depth == 6) && entryType != NbtCompound.TYPE
                || depth == 4 && length != layout.barrelCount()
                || depth == 6 && length > LitematicaFileCodec.INVENTORY_SLOTS
                || depth == 9 && (entryType != NbtString.TYPE
                    || length > LitematicaFileCodec.MAX_PAGES_PER_BOOK)) {
            throw new UncheckedIOException(new IOException("Invalid block entity or inventory list"));
        }
        return super.visitListMeta(entryType, length);
    }

    @Override
    public Result endNested() {
        int depth = getDepth();
        if (depth == 7 && book != null) {
            try {
                if (unsupportedBookField) {
                    throw new IOException("Archive book contains unsupported custom NBT fields");
                }
                consumer.accept(new ArchiveBook(barrelX, 0, 0, book));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } finally {
                items.clear();
                book = null;
            }
        }
        Result result = super.endNested();
        if (depth == 5) {
            blockEntities.clear();
            blockEntity = null;
            items = null;
        } else if (depth == 2) {
            blockEntities = null;
        }
        return result;
    }

    @FunctionalInterface
    interface BookConsumer {
        void accept(ArchiveBook book) throws IOException;
    }

    record ArchiveBook(int x, int y, int z, NbtCompound stack) {
    }
}
