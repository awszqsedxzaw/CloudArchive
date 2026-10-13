# File Tree Container v1

The Litematica transport marker is `tag.CloudArchive.kind = "tree-archive-v1"`.
The existing single-file marker remains `file-archive-v2`. A transport must not
mix these markers.

Book pages encode one binary payload with URL-safe unpadded Base64. The
transport header's `file_size` and `sha256` cover the entire container, including
the index and data. Litematica and Minecraft data versions are unchanged.

All integer fields use Java DataInput/DataOutput big-endian encoding. Lengths
and IDs must be nonnegative unless a stricter rule is given below. Names use
strict UTF-8, not Java modified UTF-8.

## Header

The header is exactly 44 bytes.

| Offset | Bytes | Field | Current Value or Meaning |
| --- | --- | --- | --- |
| 0 | 4 | magic | `0x43415452`, ASCII `CATR` |
| 4 | 2 | version | 1 |
| 6 | 2 | feature_flags | 0; unknown flags are rejected |
| 8 | 8 | generation | Writer emits 0; reader accepts nonnegative values |
| 16 | 8 | next_id | Greater than every allocated node ID |
| 24 | 4 | node_count | Number of records, excluding the implicit root |
| 28 | 8 | index_length | Header plus all node records |
| 36 | 8 | data_length | Bytes after the index |

`index_length + data_length` must exactly equal the decoded payload length.
The physical layout is the 44-byte header, the data area, then node records.
`index_length` counts the header and node records, even though they are not
adjacent. Node records begin at `44 + data_length`; the data area always begins
at byte 44 so growing an index does not move existing data addresses.
There is no implicit padding. The root directory has ID 0 and no stored name.
An empty tree consists only of this header with `node_count = 0`, `next_id = 1`,
`index_length = 44`, and `data_length = 0`.

## Node Records

Each record begins with:

| Bytes | Field |
| --- | --- |
| 1 | type: 0 for directory, 1 for regular file |
| 8 | id: unique, strictly positive |
| 8 | parent_id: root or a previously recorded directory |
| 4 | name_length: between 1 and 1,024 UTF-8 bytes |
| name_length | name: one path component, not a full path |

Files append:

| Bytes | Field |
| --- | --- |
| 8 | file_length |
| 4 | extent_count |
| 16 per extent | offset (8 bytes), length (8 bytes) |

Directories do not append these fields. Empty files have zero extents. Every
extent must have positive length and lie entirely inside the data area.
Extent lengths must sum exactly to the file length. Their listed order is
the logical file order; physical offsets do not need to be consecutive or
in ascending order. Extents may reference existing data, and unused bytes in
the data area are permitted.

An extent's physical payload address is `44 + offset`. Offsets are
explicit addresses relative to the data area, not positions inferred from
other file lengths. One extent can cross page, book, and barrel boundaries.

The current writer stores nodes in sorted root-relative path order, assigns
IDs from 1 upward, and emits one extent per nonempty file. Files share a tightly
packed data area. The reader must not replace stored IDs with record positions.
Overlapping input selections are deduplicated before allocating IDs.

## Validation and Restoration

All nodes and extents are validated before extracting files. Parents must
precede children, preventing cycles and missing parents. Duplicate IDs,
duplicate sibling names, case-insensitive sibling collisions, invalid UTF-8,
invalid lengths, overflow, trailing index records, unsupported versions, and
unknown flags are rejected.

Names cannot be empty, `.`, `..`, contain path separators, control characters,
Windows-invalid characters or reserved device names, or end in a dot or space.
The reader rejects unsafe names instead of renaming them. Host filesystem
limits can impose additional restrictions.

Sources are regular files and directories only; symbolic links and special
paths are rejected. File contents, names, and hierarchy are preserved, but
timestamps, permissions, ownership, link relationships, and filesystem-specific
attributes are not.

The entire transport is restored to disk and checked with SHA-256 before the
tree index is interpreted. Files are created in a fresh private staging
directory. The staging tree is moved to the requested nonexistent destination
only after extraction succeeds. Existing destinations are never merged into
or overwritten. This is not a guarantee of durability across power loss.

## Future Updates

Stable node IDs, explicit extent lists, `next_id`, `generation`, versioning, and
feature flags reserve representation for later work. New commands currently
create immutable snapshots; repacking does not preserve old node IDs.

No allocator, free-space map, update journal, copy-on-write operation,
transaction protocol, or in-game update mechanism is implemented. Future
changes may require a new container or transport version.
