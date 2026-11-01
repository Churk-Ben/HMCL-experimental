/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/// Builds one RPM header block in the version 4 on-disk format.
///
/// A header is a generic key/value store made of three consecutive sections:
///
/// - an intro of eight magic bytes, a 32-bit index length and a 32-bit data length,
/// - an index of `(tag, type, offset, count)` entries sorted by tag,
/// - a data section holding the aligned, NUL-terminated values.
///
/// Version 4 introduced immutable regions. This builder always emits a single
/// region covering every entry: the region tag is the first index entry and a
/// sixteen-byte region trailer is appended to the data section. The trailer's
/// offset field is the negated byte length of the complete index, which lets RPM
/// recover the number of original entries.
///
/// All integers are written in network byte order.
@NotNullByDefault
final class RpmHeader {

    /// RPM type code for a signed 16-bit integer.
    private static final int TYPE_INT16 = 3;

    /// RPM type code for a signed 32-bit integer.
    private static final int TYPE_INT32 = 4;

    /// RPM type code for a single NUL-terminated string.
    private static final int TYPE_STRING = 6;

    /// RPM type code for an opaque binary blob.
    private static final int TYPE_BIN = 7;

    /// RPM type code for an array of NUL-terminated strings.
    private static final int TYPE_STRING_ARRAY = 8;

    /// RPM type code for a locale-aware NUL-terminated string.
    private static final int TYPE_I18NSTRING = 9;

    /// Magic bytes that start every header block.
    private static final byte @Unmodifiable [] MAGIC = {
            (byte) 0x8e, (byte) 0xad, (byte) 0xe8, 0x01, 0x00, 0x00, 0x00, 0x00
    };

    /// Number of bytes occupied by one index entry.
    private static final int INDEX_ENTRY_SIZE = 16;

    /// Region tag written as the first index entry, for example 63 for headers.
    private final int regionTag;

    /// Entries accumulated by the caller, in insert order.
    private final List<Entry> entries = new ArrayList<>();

    /// Creates an empty header whose immutable region is identified by `regionTag`.
    ///
    /// @param regionTag 63 for a package header, 62 for a signature header
    public RpmHeader(int regionTag) {
        this.regionTag = regionTag;
    }

    /// Adds a single NUL-terminated UTF-8 string.
    ///
    /// @param tag   numeric RPM tag
    /// @param value the string value
    /// @return this builder
    public RpmHeader putString(int tag, String value) {
        return put(tag, TYPE_STRING, encodeString(value), 1, 1);
    }

    /// Adds a locale-aware NUL-terminated UTF-8 string.
    ///
    /// @param tag   numeric RPM tag
    /// @param value the string value
    /// @return this builder
    public RpmHeader putI18nString(int tag, String value) {
        return put(tag, TYPE_I18NSTRING, encodeString(value), 1, 1);
    }

    /// Adds an array of NUL-terminated UTF-8 strings.
    ///
    /// @param tag    numeric RPM tag
    /// @param values the string values
    /// @return this builder
    public RpmHeader putStringArray(int tag, String @Unmodifiable [] values) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (String value : values)
            data.writeBytes(encodeString(value));
        return put(tag, TYPE_STRING_ARRAY, data.toByteArray(), values.length, 1);
    }

    /// Adds an array of signed 16-bit integers.
    ///
    /// @param tag    numeric RPM tag
    /// @param values the integer values
    /// @return this builder
    public RpmHeader putInt16(int tag, int @Unmodifiable [] values) {
        ByteBuffer data = ByteBuffer.allocate(values.length * 2);
        for (int value : values)
            data.putShort((short) value);
        return put(tag, TYPE_INT16, data.array(), values.length, 2);
    }

    /// Adds an array of signed 32-bit integers.
    ///
    /// @param tag    numeric RPM tag
    /// @param values the integer values
    /// @return this builder
    public RpmHeader putInt32(int tag, int @Unmodifiable [] values) {
        ByteBuffer data = ByteBuffer.allocate(values.length * 4);
        for (int value : values)
            data.putInt(value);
        return put(tag, TYPE_INT32, data.array(), values.length, 4);
    }

    /// Adds a single signed 32-bit integer.
    ///
    /// @param tag   numeric RPM tag
    /// @param value the integer value
    /// @return this builder
    public RpmHeader putInt32(int tag, int value) {
        return putInt32(tag, new int[]{value});
    }

    /// Adds an opaque binary value.
    ///
    /// @param tag   numeric RPM tag
    /// @param value the raw bytes
    /// @return this builder
    public RpmHeader putBin(int tag, byte @Unmodifiable [] value) {
        return put(tag, TYPE_BIN, value, value.length, 1);
    }

    /// Serializes all entries into one header block.
    ///
    /// Entries are sorted by tag, their data is aligned to the natural boundary
    /// of the type, and a region trailer is appended after the last value.
    ///
    /// @return the complete header bytes, magic included
    public byte @Unmodifiable [] build() {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::tag));

        int indexCount = sorted.size() + 1;
        int[] index = new int[indexCount * 4];
        index[0] = regionTag;
        index[1] = TYPE_BIN;

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < sorted.size(); i++) {
            Entry entry = sorted.get(i);
            padTo(data, entry.alignment());
            int offset = data.size();
            data.writeBytes(entry.data());

            int base = (i + 1) * 4;
            index[base] = entry.tag();
            index[base + 1] = entry.type();
            index[base + 2] = offset;
            index[base + 3] = entry.count();
        }

        int trailerOffset = data.size();
        index[2] = trailerOffset;
        index[3] = INDEX_ENTRY_SIZE;
        ByteBuffer trailer = ByteBuffer.allocate(INDEX_ENTRY_SIZE);
        trailer.putInt(regionTag);
        trailer.putInt(TYPE_BIN);
        trailer.putInt(-(indexCount * INDEX_ENTRY_SIZE));
        trailer.putInt(INDEX_ENTRY_SIZE);
        data.writeBytes(trailer.array());

        ByteBuffer out = ByteBuffer.allocate(MAGIC.length + 8 + index.length * 4 + data.size());
        out.put(MAGIC);
        out.putInt(indexCount);
        out.putInt(data.size());
        for (int value : index)
            out.putInt(value);
        out.put(data.toByteArray());
        return out.array();
    }

    /// Stores one already-encoded entry.
    private RpmHeader put(int tag, int type, byte @Unmodifiable [] data, int count, int alignment) {
        entries.add(new Entry(tag, type, data, count, alignment));
        return this;
    }

    /// Encodes a string as UTF-8 followed by a NUL byte.
    private static byte @Unmodifiable [] encodeString(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(text, text.length + 1);
    }

    /// Pads `out` with NUL bytes until its size is a multiple of `alignment`.
    private static void padTo(ByteArrayOutputStream out, int alignment) {
        if (alignment > 1) {
            int remainder = out.size() % alignment;
            if (remainder != 0)
                out.writeBytes(new byte[alignment - remainder]);
        }
    }

    /// One typed value stored in the header data section.
    ///
    /// @param tag       numeric RPM tag
    /// @param type      RPM type code
    /// @param data      pre-encoded bytes placed in the data section
    /// @param count     number of values represented by `data`
    /// @param alignment required byte alignment of the value in the data section
    @NotNullByDefault
    private record Entry(
            int tag,
            int type,
            byte @Unmodifiable [] data,
            int count,
            int alignment
    ) {
    }
}
