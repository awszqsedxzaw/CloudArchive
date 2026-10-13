package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtType;
import net.minecraft.nbt.scanner.NbtCollector;

// Read coordinates without inventories first: compound fields have no defined order.
final class ArchiveLayoutScanner extends NbtCollector {
    static Layout read(Path source) throws IOException {
        ArchiveLayoutScanner scanner = new ArchiveLayoutScanner();
        try {
            NbtIo.scanCompressed(source.toFile(), scanner);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        if (!(scanner.getRoot() instanceof NbtCompound root)
                || root.getInt("Version") != 6 || root.getInt("SubVersion") != 1
                || root.getInt("MinecraftDataVersion") != 3465
                || !root.contains("Regions", NbtElement.COMPOUND_TYPE)) {
            throw new IOException("Input is not a supported 1.20.1 Litematica schematic");
        }
        NbtCompound regions = root.getCompound("Regions");
        if (regions.getKeys().size() != 1) {
            throw new IOException("Archive must occupy one region");
        }
        String name = regions.getKeys().iterator().next();
        NbtCompound region = regions.getCompound(name);
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
        return new Layout(name, positions);
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
        boolean include = switch (getDepth()) {
            case 1 -> Set.of("Version", "SubVersion", "MinecraftDataVersion").contains(key)
                    && type == NbtInt.TYPE || key.equals("Regions") && type == NbtCompound.TYPE;
            case 2 -> type == NbtCompound.TYPE;
            case 3 -> key.equals("Size") && type == NbtCompound.TYPE
                    || key.equals("TileEntities") && type == NbtList.TYPE;
            case 4 -> Set.of("x", "y", "z").contains(key) && type == NbtInt.TYPE;
            case 5 -> Set.of("x", "y", "z").contains(key) && type == NbtInt.TYPE
                    || key.equals("id") && type == NbtString.TYPE;
            default -> false;
        };
        return include ? super.startSubNbt(type, key) : NestedResult.SKIP;
    }

    @Override
    public Result visitListMeta(NbtType<?> type, int length) {
        if (length < 1 || type != NbtCompound.TYPE) {
            throw new UncheckedIOException(new IOException("Invalid archive barrel list"));
        }
        return super.visitListMeta(type, length);
    }

    record Layout(String region, int[] positions) {
        int barrelCount() {
            return positions.length;
        }
    }
}
