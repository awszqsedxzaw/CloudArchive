package awszqsedxzaw.cloudarchive.codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtType;
import net.minecraft.nbt.scanner.NbtScanner;

// Only container frames survive callbacks; page strings are consumed immediately.
final class StreamingArchiveScanner implements NbtScanner {
    private final PageConsumer pages;
    private final BookConsumer books;
    private final BarrelConsumer barrels;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final BitSet coordinates = new BitSet();
    private int barrelCount = -1;
    private int regionCount;
    private boolean rootFinished;

    StreamingArchiveScanner(PageConsumer pages, BookConsumer books, BarrelConsumer barrels) {
        this.pages = pages;
        this.books = books;
        this.barrels = barrels;
    }

    @Override
    public Result start(NbtType<?> type) {
        require(type == NbtCompound.TYPE && frames.isEmpty() && !rootFinished,
                "Schematic root is not a compound");
        frames.addLast(new Frame(Kind.ROOT, null));
        return Result.CONTINUE;
    }

    @Override
    public NestedResult visitSubNbtType(NbtType<?> type) {
        return NestedResult.ENTER;
    }

    @Override
    public NestedResult startSubNbt(NbtType<?> type, String key) {
        Frame parent = frames.getLast();
        if (parent.kind == Kind.REGIONS) {
            require(type == NbtCompound.TYPE && regionCount++ == 0, "Archive must occupy one region");
            frames.addLast(new Frame(Kind.REGION, parent));
            return NestedResult.ENTER;
        }
        Field field = switch (parent.kind) {
            case ROOT -> switch (key) {
                case "Version" -> Field.VERSION;
                case "SubVersion" -> Field.SUB_VERSION;
                case "MinecraftDataVersion" -> Field.MC_VERSION;
                case "Regions" -> Field.REGIONS;
                default -> null;
            };
            case REGION -> switch (key) {
                case "Size" -> Field.SIZE;
                case "TileEntities" -> Field.TILE_ENTITIES;
                default -> null;
            };
            case SIZE -> coordinateField(key);
            case BARREL -> switch (key) {
                case "id" -> Field.ID;
                case "Items" -> Field.ITEMS;
                default -> coordinateField(key);
            };
            case BOOK -> switch (key) {
                case "id" -> Field.ID;
                case "Count" -> Field.COUNT;
                case "Slot" -> Field.SLOT;
                case "tag" -> Field.TAG;
                default -> null;
            };
            case TAG -> switch (key) {
                case "author" -> Field.AUTHOR;
                case "title" -> Field.TITLE;
                case "resolved" -> Field.RESOLVED;
                case "pages" -> Field.PAGES;
                default -> null;
            };
            default -> null;
        };
        if (field == null) {
            require(parent.kind != Kind.BOOK && parent.kind != Kind.TAG,
                    "Archive book contains unsupported fields: " + key);
            return NestedResult.SKIP;
        }
        require(type == field.type && !parent.seen(field),
                "Archive contains an invalid or duplicate field: " + key);
        parent.fields |= field.mask();
        if (field.child != null) {
            frames.addLast(new Frame(field.child, parent));
        } else {
            parent.pending = field;
        }
        return NestedResult.ENTER;
    }

    private static Field coordinateField(String key) {
        return switch (key) {
            case "x" -> Field.X;
            case "y" -> Field.Y;
            case "z" -> Field.Z;
            default -> null;
        };
    }

    @Override
    public Result visitListMeta(NbtType<?> type, int length) {
        Frame frame = frames.getLast();
        switch (frame.kind) {
            case TILE_ENTITIES -> {
                require(type == NbtCompound.TYPE && length > 0
                                && length <= 1 + LitematicaFileCodec.barrelCountForBooks(Integer.MAX_VALUE),
                        "Invalid archive barrel list");
                barrelCount = length;
            }
            case ITEMS -> require(type == NbtCompound.TYPE && length > 0
                    && length <= LitematicaFileCodec.INVENTORY_SLOTS, "Invalid archive inventory list");
            case PAGES -> require(type == NbtString.TYPE && length > 0
                    && length <= LitematicaFileCodec.MAX_PAGES_PER_BOOK, "Invalid archive page list");
            default -> throw fail("Unexpected archive list");
        }
        frame.length = length;
        return Result.CONTINUE;
    }

    @Override
    public NestedResult startListItem(NbtType<?> type, int index) {
        Frame frame = frames.getLast();
        require(index == frame.visited++ && index < frame.length, "Invalid archive list index");
        switch (frame.kind) {
            case TILE_ENTITIES -> frames.addLast(new Frame(Kind.BARREL, frame));
            case ITEMS -> frames.addLast(new Frame(Kind.BOOK, frame));
            case PAGES -> { }
            default -> throw fail("Unexpected archive list item");
        }
        return NestedResult.ENTER;
    }

    @Override
    public Result visitString(String value) {
        Frame frame = frames.getLast();
        if (frame.kind == Kind.PAGES) {
            try {
                pages.accept(frame.visited - 1, frame.length, value);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        } else {
            switch (takeField(frame)) {
                case ID -> frame.id = value;
                case AUTHOR -> frame.author = value;
                case TITLE -> frame.title = value;
                default -> throw fail("Unexpected archive string");
            }
        }
        return Result.CONTINUE;
    }

    @Override
    public Result visitByte(byte value) {
        Frame frame = frames.getLast();
        switch (takeField(frame)) {
            case COUNT -> frame.count = value;
            case SLOT -> frame.slot = value & 255;
            case RESOLVED -> frame.resolved = value != 0;
            default -> throw fail("Unexpected archive byte");
        }
        return Result.CONTINUE;
    }

    @Override
    public Result visitInt(int value) {
        Frame frame = frames.getLast();
        switch (takeField(frame)) {
            case VERSION -> frame.version = value;
            case SUB_VERSION -> frame.subVersion = value;
            case MC_VERSION -> frame.mcVersion = value;
            case X -> frame.x = value;
            case Y -> frame.y = value;
            case Z -> frame.z = value;
            default -> throw fail("Unexpected archive integer");
        }
        return Result.CONTINUE;
    }

    private static Field takeField(Frame frame) {
        Field field = frame.pending;
        require(field != null, "Unexpected archive scalar");
        frame.pending = null;
        return field;
    }

    @Override
    public Result endNested() {
        Frame frame = frames.removeLast();
        try {
            switch (frame.kind) {
                case ROOT -> {
                    require(frame.has(Field.VERSION, Field.SUB_VERSION, Field.MC_VERSION, Field.REGIONS)
                            && frame.version == 6 && frame.subVersion == 1 && frame.mcVersion == 3465
                            && regionCount == 1, "Input is not a supported 1.20.1 Litematica schematic");
                    rootFinished = true;
                }
                case REGIONS -> require(regionCount == 1, "Archive must occupy one region");
                case REGION -> require(frame.has(Field.SIZE, Field.TILE_ENTITIES)
                        && frame.x == barrelCount && frame.y == 1 && frame.z == 1,
                        "Archive region dimensions or block entities are invalid");
                case SIZE -> {
                    require(frame.has(Field.X, Field.Y, Field.Z) && frame.x > 0
                            && frame.y == 1 && frame.z == 1, "Archive region size is invalid");
                    frame.parent.x = frame.x;
                    frame.parent.y = frame.y;
                    frame.parent.z = frame.z;
                }
                case TILE_ENTITIES, PAGES -> require(frame.visited == frame.length,
                        "Archive list is truncated");
                case ITEMS -> {
                    require(frame.visited == frame.length && frame.slots == (1 << frame.length) - 1,
                            "Archive inventory slots must be unique and contiguous from zero");
                    frame.parent.bookCount = frame.length;
                }
                case BARREL -> {
                    require(frame.has(Field.ID, Field.X, Field.Y, Field.Z, Field.ITEMS)
                            && LitematicaFileCodec.BARREL_ID.equals(frame.id) && frame.y == 0 && frame.z == 0,
                            "Archive barrel block entity is invalid");
                    require(frame.x >= 0 && frame.x < barrelCount && !coordinates.get(frame.x),
                            "Archive barrel coordinates must be unique and contiguous from X=0");
                    coordinates.set(frame.x);
                    require(frame.x == 0 || frame.x == barrelCount - 1
                                    || frame.bookCount == LitematicaFileCodec.INVENTORY_SLOTS,
                            "Archive contains an incomplete non-final data barrel");
                    barrels.accept(frame.x);
                }
                case BOOK -> {
                    require(frame.has(Field.ID, Field.COUNT, Field.SLOT, Field.TAG)
                            && LitematicaFileCodec.BOOK_ID.equals(frame.id) && frame.count == 1
                            && frame.slot < LitematicaFileCodec.INVENTORY_SLOTS, "Archive book item NBT is invalid");
                    require((frame.parent.slots & (1 << frame.slot)) == 0,
                            "Archive inventory contains duplicate slots");
                    frame.parent.slots |= 1 << frame.slot;
                    Frame barrel = frame.parent.parent;
                    require(!barrel.seen(Field.X) || barrel.x >= 0 && barrel.x < barrelCount,
                            "Archive barrel coordinate is outside the layout");
                    books.accept(new ArchiveBook(barrel.seen(Field.X) ? barrel.x : -1,
                            frame.slot, frame.title, frame.bookCount));
                }
                case TAG -> {
                    require(frame.has(Field.AUTHOR, Field.TITLE, Field.RESOLVED, Field.PAGES)
                            && LitematicaFileCodec.AUTHOR.equals(frame.author) && frame.resolved,
                            "Archive book fields are invalid");
                    frame.parent.title = frame.title;
                }
            }
            if (frame.kind == Kind.PAGES) {
                frame.parent.parent.bookCount = frame.length;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return Result.CONTINUE;
    }

    int barrelCount() throws IOException {
        if (!rootFinished || !frames.isEmpty() || coordinates.cardinality() != barrelCount) {
            throw new IOException("Archive NBT scan is incomplete");
        }
        return barrelCount;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw fail(message);
        }
    }

    private static UncheckedIOException fail(String message) {
        return new UncheckedIOException(new IOException(message));
    }

    @Override public Result visitEnd() { throw fail("Unexpected end tag"); }
    @Override public Result visitShort(short value) { throw fail("Unexpected short tag"); }
    @Override public Result visitLong(long value) { throw fail("Unexpected long tag"); }
    @Override public Result visitFloat(float value) { throw fail("Unexpected float tag"); }
    @Override public Result visitDouble(double value) { throw fail("Unexpected double tag"); }
    @Override public Result visitByteArray(byte[] value) { throw fail("Unexpected byte array"); }
    @Override public Result visitIntArray(int[] value) { throw fail("Unexpected int array"); }
    @Override public Result visitLongArray(long[] value) { throw fail("Unexpected long array"); }

    @FunctionalInterface
    interface PageConsumer {
        void accept(int page, int pageCount, String encoded) throws IOException;
    }

    @FunctionalInterface
    interface BookConsumer {
        void accept(ArchiveBook book) throws IOException;
    }

    @FunctionalInterface
    interface BarrelConsumer {
        void accept(int x) throws IOException;
    }

    record ArchiveBook(int x, int slot, String title, int pageCount) { }

    private enum Kind { ROOT, REGIONS, REGION, SIZE, TILE_ENTITIES, BARREL, ITEMS, BOOK, TAG, PAGES }

    private enum Field {
        VERSION(NbtInt.TYPE), SUB_VERSION(NbtInt.TYPE), MC_VERSION(NbtInt.TYPE),
        X(NbtInt.TYPE), Y(NbtInt.TYPE), Z(NbtInt.TYPE),
        ID(NbtString.TYPE), AUTHOR(NbtString.TYPE), TITLE(NbtString.TYPE),
        COUNT(NbtByte.TYPE), SLOT(NbtByte.TYPE), RESOLVED(NbtByte.TYPE),
        REGIONS(NbtCompound.TYPE, Kind.REGIONS), SIZE(NbtCompound.TYPE, Kind.SIZE),
        TILE_ENTITIES(NbtList.TYPE, Kind.TILE_ENTITIES), ITEMS(NbtList.TYPE, Kind.ITEMS),
        TAG(NbtCompound.TYPE, Kind.TAG), PAGES(NbtList.TYPE, Kind.PAGES);

        final NbtType<?> type;
        final Kind child;

        Field(NbtType<?> type) { this(type, null); }
        Field(NbtType<?> type, Kind child) { this.type = type; this.child = child; }
        int mask() { return 1 << ordinal(); }
    }

    private static final class Frame {
        final Kind kind;
        final Frame parent;
        int fields, length, visited, slots, bookCount;
        int version, subVersion, mcVersion, x, y, z, count, slot;
        String id, author, title;
        boolean resolved;
        Field pending;

        Frame(Kind kind, Frame parent) { this.kind = kind; this.parent = parent; }
        boolean seen(Field field) { return (fields & field.mask()) != 0; }
        boolean has(Field... required) {
            for (Field field : required) {
                if (!seen(field)) { return false; }
            }
            return true;
        }
    }
}
