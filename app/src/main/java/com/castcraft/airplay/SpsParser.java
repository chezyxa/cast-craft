/*
 * Cast Craft - AirPlay screen mirroring receiver for Android TV / Google TV.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castcraft.airplay;

/** Minimal H.264 SPS parser: just enough to get the picture size. */
final class SpsParser {
    private final byte[] rbsp;
    private final int length;
    private int bit;

    private SpsParser(byte[] rbsp, int length) {
        this.rbsp = rbsp;
        this.length = length;
    }

    /** @param sps SPS NAL with or without start code. Returns {width, height} or null. */
    static int[] size(byte[] sps) {
        try {
            int start = 0;
            while (start < sps.length - 1 && sps[start] == 0) start++;
            if (start < sps.length && sps[start] == 1) start++;
            // remove emulation prevention bytes, skip NAL header
            byte[] out = new byte[sps.length];
            int n = 0;
            int zeros = 0;
            for (int i = start + 1; i < sps.length; i++) {
                int b = sps[i] & 0xff;
                if (zeros >= 2 && b == 3) {
                    zeros = 0;
                    continue;
                }
                zeros = b == 0 ? zeros + 1 : 0;
                out[n++] = (byte) b;
            }
            return new SpsParser(out, n).parse();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int[] parse() {
        int profile = u(8);
        u(16); // constraint flags + level
        ue(); // sps id
        int chromaFormat = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                || profile == 139 || profile == 134 || profile == 135) {
            chromaFormat = ue();
            if (chromaFormat == 3) u(1);
            ue(); // bit depth luma
            ue(); // bit depth chroma
            u(1);
            if (u(1) == 1) { // scaling matrix present
                for (int i = 0; i < (chromaFormat != 3 ? 8 : 12); i++) {
                    if (u(1) == 1) skipScalingList(i < 6 ? 16 : 64);
                }
            }
        }
        ue(); // log2 max frame num
        int pocType = ue();
        if (pocType == 0) {
            ue();
        } else if (pocType == 1) {
            u(1);
            se();
            se();
            int cycle = ue();
            for (int i = 0; i < cycle; i++) se();
        }
        ue(); // max ref frames
        u(1);
        int widthMbs = ue() + 1;
        int heightMapUnits = ue() + 1;
        int frameMbsOnly = u(1);
        if (frameMbsOnly == 0) u(1);
        u(1);
        int width = widthMbs * 16;
        int height = (2 - frameMbsOnly) * heightMapUnits * 16;
        if (u(1) == 1) { // cropping
            int l = ue(), r = ue(), t = ue(), b = ue();
            int cropX = chromaFormat == 0 || chromaFormat == 3 ? 1 : 2;
            int cropY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly);
            width -= (l + r) * cropX;
            height -= (t + b) * cropY;
        }
        return new int[]{width, height};
    }

    private void skipScalingList(int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                next = (last + se() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    private int u(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            if (bit >= length * 8) throw new IllegalStateException("SPS too short");
            v = (v << 1) | ((rbsp[bit >> 3] >> (7 - (bit & 7))) & 1);
            bit++;
        }
        return v;
    }

    private int ue() {
        int zeros = 0;
        while (u(1) == 0) {
            zeros++;
            if (zeros > 31) throw new IllegalStateException("bad exp-golomb");
        }
        return (int) ((1L << zeros) - 1 + (zeros == 0 ? 0 : u(zeros)));
    }

    private int se() {
        int k = ue();
        return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
    }
}
