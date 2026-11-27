/*
 * Cast Craft - AirPlay screen mirroring receiver for Android TV / Google TV.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castcraft.airplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes the H.264 mirroring stream (Annex-B, SPS/PPS prepended to key frames
 * by the native code) with MediaCodec straight onto a Surface. Frames are shown
 * as soon as they are decoded, for the lowest latency.
 */
public final class VideoDecoder {
    private static final String TAG = "CastCraftVideo";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    public interface SizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private static final class Frame {
        final byte[] data;
        final long ptsUs;
        final byte[] sps;
        final byte[] pps;

        Frame(byte[] data, long ptsUs, byte[] sps, byte[] pps) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.sps = sps;
            this.pps = pps;
        }

        boolean isConfig() {
            return sps != null && pps != null;
        }
    }

    private static final Frame FLUSH = new Frame(new byte[0], 0, null, null);

    private final SizeListener sizeListener;
    private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(90);
    private final Object lock = new Object();

    private Surface surface;
    private boolean surfaceChanged;
    private Frame lastKeyFrame;
    private Thread thread;
    private volatile boolean running;

    // owned by the decoder thread
    private MediaCodec codec;
    private byte[] codecSps;
    private byte[] codecPps;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public VideoDecoder(SizeListener sizeListener) {
        this.sizeListener = sizeListener;
    }

    public void setSurface(Surface surface) {
        synchronized (lock) {
            this.surface = surface;
            this.surfaceChanged = true;
        }
    }

    /** Called from the native network thread. */
    public void submit(byte[] annexB, long ptsUs) {
        byte[][] params = findSpsPps(annexB);
        Frame frame = new Frame(annexB, ptsUs, params[0], params[1]);
        synchronized (lock) {
            if (frame.isConfig()) {
                lastKeyFrame = frame;
            }
            if (!running) {
                running = true;
                thread = new Thread(this::loop, "CastCraftVideoDecoder");
                thread.start();
            }
        }
        if (!queue.offer(frame)) {
            // Decoder is too slow: drop the backlog and wait for the decoder to catch up.
            Log.w(TAG, "video queue full, dropping frames");
            queue.clear();
            Frame key = lastKeyFrame;
            if (key != null && key != frame) {
                queue.offer(key);
            }
            queue.offer(frame);
        }
    }

    public void flush() {
        queue.clear();
        queue.offer(FLUSH);
    }

    /** Stops decoding (end of mirroring session). Safe to call at any time. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
            lastKeyFrame = null;
        }
        if (t != null) {
            t.interrupt();
            if (t != Thread.currentThread()) {
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        queue.clear();
    }

    // ------------------------------------------------------------- decoder thread

    private void loop() {
        try {
            while (running) {
                Frame frame = queue.poll(10, TimeUnit.MILLISECONDS);
                handleSurfaceChange();
                if (frame == FLUSH) {
                    if (codec != null) {
                        try {
                            codec.flush();
                        } catch (IllegalStateException e) {
                            releaseCodec();
                        }
                    }
                } else if (frame != null) {
                    decode(frame);
                }
                drain();
            }
        } catch (InterruptedException ignored) {
        } finally {
            releaseCodec();
        }
    }

    private void handleSurfaceChange() {
        Surface s;
        Frame key;
        synchronized (lock) {
            if (!surfaceChanged) return;
            surfaceChanged = false;
            s = surface;
            key = lastKeyFrame;
        }
        releaseCodec();
        if (s != null && key != null) {
            // restart from the most recent key frame so the picture comes back at once
            decode(key);
        }
    }

    private void decode(Frame frame) {
        if (frame.isConfig() && (codec == null
                || !Arrays.equals(frame.sps, codecSps) || !Arrays.equals(frame.pps, codecPps))) {
            // first frame, new resolution (rotation) or new encoder settings
            releaseCodec();
            createCodec(frame.sps, frame.pps);
        }
        if (codec == null) return;
        try {
            int index = codec.dequeueInputBuffer(50_000);
            if (index < 0) {
                Log.w(TAG, "no input buffer, frame dropped");
                return;
            }
            ByteBuffer buf = codec.getInputBuffer(index);
            if (buf == null) return;
            buf.clear();
            if (frame.data.length > buf.capacity()) {
                Log.w(TAG, "frame too large: " + frame.data.length);
                codec.queueInputBuffer(index, 0, 0, frame.ptsUs, 0);
                return;
            }
            buf.put(frame.data);
            codec.queueInputBuffer(index, 0, frame.data.length, frame.ptsUs,
                    frame.isConfig() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        } catch (IllegalStateException e) {
            Log.e(TAG, "decoder error", e);
            releaseCodec();
        }
    }

    private void drain() {
        if (codec == null) return;
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true);
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportSize(codec.getOutputFormat());
                } else {
                    break;
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "decoder error", e);
            releaseCodec();
        }
    }

    private void reportSize(MediaFormat f) {
        int w = f.getInteger(MediaFormat.KEY_WIDTH);
        int h = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        }
        if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        }
        Log.i(TAG, "decoded size " + w + "x" + h);
        sizeListener.onVideoSizeChanged(w, h);
    }

    private void createCodec(byte[] sps, byte[] pps) {
        Surface s;
        synchronized (lock) {
            s = surface;
        }
        if (s == null || !s.isValid()) return;
        int[] size = SpsParser.size(sps);
        int width = size != null ? size[0] : 1920;
        int height = size != null ? size[1] : 1080;
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 1024 * 1024));
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        try {
            codec = MediaCodec.createDecoderByType(MIME);
            codec.configure(format, s, null, 0);
            codec.start();
            codecSps = sps;
            codecPps = pps;
            Log.i(TAG, "decoder " + codec.getName() + " started for " + width + "x" + height);
            sizeListener.onVideoSizeChanged(width, height);
        } catch (Exception e) {
            Log.e(TAG, "cannot start decoder", e);
            releaseCodec();
        }
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
        codecSps = null;
        codecPps = null;
    }

    // ------------------------------------------------------------------ parsing

    /** Returns {sps, pps} (each with a 4-byte start code) or nulls if absent. */
    static byte[][] findSpsPps(byte[] data) {
        byte[] sps = null;
        byte[] pps = null;
        int n = data.length;
        int i = nextStart(data, 0);
        while (i >= 0 && i < n) {
            int payload = i + (data[i + 2] == 1 ? 3 : 4);
            if (payload >= n) break;
            int next = nextStart(data, payload);
            int end = next < 0 ? n : next;
            int type = data[payload] & 0x1f;
            if (type == 7 || type == 8) {
                byte[] nal = new byte[4 + end - payload];
                nal[3] = 1;
                System.arraycopy(data, payload, nal, 4, end - payload);
                if (type == 7) sps = nal;
                else pps = nal;
            } else if (type == 1 || type == 5) {
                break; // slice data: no parameter sets after this
            }
            i = next;
        }
        return new byte[][]{sps, pps};
    }

    /** Index of the next 00 00 01 or 00 00 00 01 start code at or after from, or -1. */
    private static int nextStart(byte[] d, int from) {
        for (int i = from; i + 2 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0) {
                if (d[i + 2] == 1) return i;
                if (d[i + 2] == 0 && i + 3 < d.length && d[i + 3] == 1) return i;
            }
        }
        return -1;
    }
}
