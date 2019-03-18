/*
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.heap;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.graalvm.compiler.core.common.util.TypeWriter;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.code.CodeInfoQueryResult;
import com.oracle.svm.core.config.ConfigurationValues;

public class CodeReferenceMapEncoder extends ReferenceMapEncoder {

    @Override
    protected void encodeAll(List<Entry<Input, Long>> sortedEntries) {
        /*
         * The table always starts with the empty reference map. This allows clients to actually
         * iterate the empty reference map, making a check for the empty map optional.
         */
        assert CodeInfoQueryResult.EMPTY_REFERENCE_MAP == writeBuffer.getBytesWritten();
        encodeEndOfTable();

        for (Map.Entry<ReferenceMapEncoder.Input, Long> entry : sortedEntries) {
            ReferenceMapEncoder.Input map = entry.getKey();
            encodings.put(map, encode(map.getOffsets()));
        }
    }

    public long lookupEncoding(ReferenceMapEncoder.Input referenceMap) {
        if (referenceMap == null) {
            return CodeInfoQueryResult.NO_REFERENCE_MAP;
        } else if (referenceMap.isEmpty()) {
            return CodeInfoQueryResult.EMPTY_REFERENCE_MAP;
        } else {
            Long result = encodings.get(referenceMap);
            assert result != null && result.longValue() != CodeInfoQueryResult.NO_REFERENCE_MAP && result.longValue() != CodeInfoQueryResult.EMPTY_REFERENCE_MAP;
            return result.longValue();
        }
    }

    /**
     * Build a byte array that encodes the passed list of offsets. The encoding is a run-length
     * encoding of runs of compressed or of uncompressed references, with
     * {@linkplain TypeWriter#putUV(long) variable-length encoding for the lengths}.
     *
     * @return The index into the final bytes.
     */
    private long encode(ReferenceMapEncoder.OffsetIterator offsets) {
        int compressedSize = ConfigurationValues.getObjectLayout().getReferenceSize();
        int uncompressedSize = FrameAccess.uncompressedReferenceSize();

        long startIndex = writeBuffer.getBytesWritten();
        int run = 0;
        int gap = 0;

        boolean expectedCompressed = false;
        int expectedOffset = 0;
        while (offsets.hasNext()) {
            boolean compressed = offsets.isNextCompressed();
            boolean derived = offsets.isNextDerived();
            int offset = offsets.nextInt();
            if (offset == expectedOffset && compressed == expectedCompressed && !derived) {
                // An adjacent offset in this run.
                run += 1;
            } else {
                assert offset >= expectedOffset : "values must be strictly increasing";
                if (run > 0) {
                    // The end of a run. Encode the *previous* gap and this run of offsets.
                    encodeRun(gap, run, expectedCompressed, false);
                }
                // Beginning of the next gap+run pair.
                gap = offset - expectedOffset;
                run = 1;
            }
            int size = (compressed ? compressedSize : uncompressedSize);
            if (derived) {
                encodeDerivedRun(gap, offset, offsets.getDerivedOffsets(offset), compressed, size);
                run = 0;
                gap = 0;
            }
            expectedOffset = offset + size;
            expectedCompressed = compressed;
        }
        if (run > 0) {
            encodeRun(gap, run, expectedCompressed, false);
        }
        encodeEndOfTable();
        return startIndex;
    }

    private void encodeRun(int gap, int refsCount, boolean compressed, boolean derived) {
        assert gap >= 0 && refsCount >= 0;
        writeBuffer.putSV(derived ? -gap - 1 : gap);
        writeBuffer.putSV(compressed ? -refsCount : refsCount);
    }

    private void encodeDerivedRun(int gap, int baseOffset, Set<Integer> derivedOffsets, boolean compressed, int size) {
        encodeRun(gap, derivedOffsets.size(), compressed, true);
        for (int derivedOffset : derivedOffsets) {
            assert baseOffset % size == 0 && derivedOffset % size == 0 && derivedOffset != baseOffset;
            writeBuffer.putSV((derivedOffset - baseOffset) / size);
        }
    }

    private void encodeEndOfTable() {
        encodeRun(0, 0, false, false);
    }
}
