package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtType;
import net.minecraft.nbt.scanner.NbtCollector;

final class ArchiveBookScanner extends NbtCollector {
    private final BookConsumer consumer;
    private String regionName;
    private NbtList blockEntities;
    private NbtList items;

    ArchiveBookScanner(BookConsumer consumer) {
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
        // Root -> Regions -> region -> TileEntities[] -> entity -> Items[] -> book -> tag.
        int depth = getDepth();
        boolean include = switch (depth) {
            case 1 -> key.equals("Version") && type == NbtInt.TYPE
                    || key.equals("Regions") && type == NbtCompound.TYPE;
            case 2 -> type == NbtCompound.TYPE;
            case 3 -> key.equals("TileEntities") && type == NbtList.TYPE;
            case 5 -> key.equals("Items") && type == NbtList.TYPE;
            case 7 -> key.equals("id") && type == NbtString.TYPE
                    || key.equals("tag") && type == NbtCompound.TYPE;
            case 8 -> key.equals(LitematicaFileCodec.MOD_TAG) && type == NbtCompound.TYPE
                    || key.equals("pages") && type == NbtList.TYPE;
            default -> depth >= 9;
        };
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
            items = blockEntities.getCompound(0).getList("Items", NbtElement.COMPOUND_TYPE);
        }
        return result;
    }

    @Override
    public Result visitListMeta(NbtType<?> entryType, int length) {
        if (length < 0 || (getDepth() == 4 || getDepth() == 6)
                && length > 0 && entryType != NbtCompound.TYPE) {
            throw new UncheckedIOException(new IOException("Invalid block entity or inventory list"));
        }
        return super.visitListMeta(entryType, length);
    }

    @Override
    public Result endNested() {
        int depth = getDepth();
        if (depth == 7) {
            try {
                consumer.accept(items.getCompound(0));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            // Release each book immediately instead of retaining the complete schematic.
            items.clear();
        } else if (depth == 5) {
            blockEntities.clear();
        }
        return super.endNested();
    }

    @FunctionalInterface
    interface BookConsumer {
        void accept(NbtCompound book) throws IOException;
    }
}
