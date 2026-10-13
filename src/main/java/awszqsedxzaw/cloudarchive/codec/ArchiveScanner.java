package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtType;
import net.minecraft.nbt.scanner.NbtCollector;

// Collects the projection once; layout and books are validated from the same NBT tree.
final class ArchiveScanner extends NbtCollector {
    private final BookConsumer consumer;

    ArchiveScanner(BookConsumer consumer) {
        this.consumer = consumer;
    }

    Layout finish() throws IOException {
        if (!(getRoot() instanceof NbtCompound root)
                || root.getInt("Version") != 6 || root.getInt("SubVersion") != 1
                || root.getInt("MinecraftDataVersion") != 3465
                || !root.contains("Regions", NbtElement.COMPOUND_TYPE)) {
            throw new IOException("Input is not a supported 1.20.1 Litematica schematic");
        }
        NbtCompound regions = root.getCompound("Regions");
        if (regions.getKeys().size() != 1) {
            throw new IOException("Archive must occupy one region");
        }
        String regionName = regions.getKeys().iterator().next();
        NbtCompound region = regions.getCompound(regionName);
        NbtCompound size = region.getCompound("Size");
        int barrels = size.getInt("x");
        if (barrels < 1 || size.getInt("y") != 1 || size.getInt("z") != 1) {
            throw new IOException("Archive region must be a positive X-axis row of barrels");
        }
        NbtList entities = region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
        if (entities.size() != barrels) {
            throw new IOException("Archive barrel block entities are missing or duplicated");
        }
        int[] positions = new int[barrels];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < barrels; i++) {
            NbtCompound entity = entities.getCompound(i);
            int x = entity.getInt("x");
            if (!LitematicaFileCodec.BARREL_ID.equals(entity.getString("id"))
                    || !entity.contains("x", NbtElement.INT_TYPE)
                    || !entity.contains("y", NbtElement.INT_TYPE)
                    || !entity.contains("z", NbtElement.INT_TYPE)
                    || entity.getInt("y") != 0 || entity.getInt("z") != 0
                    || x < 0 || x >= barrels || !seen.add(x)) {
                throw new IOException("Archive barrel coordinates must be unique and contiguous from X=0");
            }
            positions[i] = x;
        }
        return new Layout(regionName, positions);
    }

    void emitBooks(Layout layout) throws IOException {
        NbtCompound root = (NbtCompound) getRoot();
        NbtList entities = root.getCompound("Regions").getCompound(layout.region())
                .getList("TileEntities", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < entities.size(); i++) {
            NbtCompound entity = entities.getCompound(i);
            int x = entity.getInt("x");
            NbtList items = entity.getList("Items", NbtElement.COMPOUND_TYPE);
            if (items.size() > LitematicaFileCodec.INVENTORY_SLOTS) {
                throw new IOException("Invalid archive inventory list");
            }
            for (int j = 0; j < items.size(); j++) {
                consumer.accept(new ArchiveBook(x, 0, 0, items.getCompound(j)));
            }
        }
    }

    @Override
    public Result start(NbtType<?> type) {
        if (type != NbtCompound.TYPE) {
            throw new UncheckedIOException(new IOException("Schematic root is not a compound"));
        }
        return super.start(type);
    }

    @FunctionalInterface
    interface BookConsumer {
        void accept(ArchiveBook book) throws IOException;
    }

    record ArchiveBook(int x, int y, int z, NbtCompound stack) {
    }

    record Layout(String region, int[] positions) {
        int barrelCount() {
            return positions.length;
        }
    }
}
