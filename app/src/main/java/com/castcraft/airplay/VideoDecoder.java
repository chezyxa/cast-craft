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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes the H.264 mirroring stream (Annex-B, SPS/PPS prepended to key frames
 * by the native code) with MediaCodec straight onto a Surface.
 *
 * iOS sends key frames very rarely (often only once per session), so a lost
 * frame would freeze the picture until the next one. To avoid that:
 *  - frames are never dropped before decoding; a feeder thread waits for
 *    input buffers while a separate drain thread keeps output flowing;
 *  - every frame since the last key frame is kept, so the decoder can be
 *    rebuilt (new surface, decoder error) without waiting for a key frame;
 *  - when decoding falls behind, frames are decoded but not shown until the
 *    decoder has caught up, which keeps latency low without breaking references.
 */
public final class VideoDecoder {
    private static final String TAG = "CastCraftVideo";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int MAX_GOP_FRAMES = 1800;
    private static final long MAX_GOP_BYTES = 48L * 1024 * 1024;
    /** Output frames older than this (in frames behind the newest received) are not shown. */
    private static final long MAX_DISPLAY_LAG = 8;

    public interface SizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private static final class Frame {
        final byte[] data;
        final long seq;
        final byte[] sps;
        final byte[] pps;

        Frame(byte[] data, long seq, byte[] sps, byte[] pps) {
            this.data = data;
            this.seq = seq;
            this.sps = sps;
            this.pps = pps;
        }

        boolean isConfig() {
            return sps != null && pps != null;
        }
    }

    private final SizeListener sizeListener;
    private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private final Object lock = new Object();

    // guarded by lock
    private final List<Frame> gop = new ArrayList<>();
    private long gopBytes;
    private boolean gopComplete;
    private long nextSeq;
    private Surface surface;
    private boolean rebuildRequested;
    private Thread feeder;
    private boolean running;

    private volatile long newestSeq;

    // feeder thread state
    private Session session;

    public VideoDecoder(SizeListener sizeListener) {
        this.sizeListener = sizeListener;
    }

    public void setSurface(Surface surface) {
        synchronized (lock) {
            this.surface = surface;
            this.rebuildRequested = true;
        }
    }

    /** Called from the native network thread. Never blocks for long. */
    public void submit(byte[] annexB, long ptsUs) {
        byte[][] params = findSpsPps(annexB);
        synchronized (lock) {
            Frame frame = new Frame(annexB, nextSeq++, params[0], params[1]);
            newestSeq = frame.seq;
            if (frame.isConfig()) {
                gop.clear();
                gopBytes = 0;
                gopComplete = true;
            }
            if (gopComplete) {
                if (gop.size() < MAX_GOP_FRAMES && gopBytes + annexB.length < MAX_GOP_BYTES) {
                    gop.add(frame);
                    gopBytes += annexB.length;
                } else {
                    gopComplete = false; // too long to replay; a rebuild will wait for the next key frame
                    gop.clear();
                    gopBytes = 0;
                }
            }
            queue.offer(frame);
            if (!running) {
                running = true;
                feeder = new Thread(this::feedLoop, "CastCraftVideoFeed");
                feeder.start();
            }
        }
    }

    public void flush() {
        // The stream continues with the same references after a flush; nothing to drop.
    }

    /** Stops decoding (end of mirroring session). Safe to call from any thread. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = feeder;
            feeder = null;
            gop.clear();
            gopBytes = 0;
            gopComplete = false;
            queue.clear();
        }
        if (t != null) {
            t.interrupt();
            if (t != Thread.currentThread()) {
                try {
                    t.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ------------------------------------------------------------- feeder thread

    private boolean isRunning() {
        synchronized (lock) {
            return running && feeder == Thread.currentThread();
        }
    }

    private void feedLoop() {
        try {
            while (isRunning()) {
                boolean rebuild;
                synchronized (lock) {
                    rebuild = rebuildRequested;
                    rebuildRequested = false;
                }
                if (rebuild || (session != null && session.failed)) {
                    rebuild();
                    continue;
                }
                Frame frame = queue.poll(20, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (frame.isConfig() && (session == null
                        || !Arrays.equals(frame.sps, session.sps) || !Arrays.equals(frame.pps, session.pps))) {
                    // first frame, rotation / resolution change or new encoder settings
                    rebuild();
                    continue;
                }
                if (session != null) {
                    session.feed(frame);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            closeSession();
        }
    }

    /** Recreates the decoder and replays everything since the last key frame. */
    private void rebuild() throws InterruptedException {
        closeSession();
        List<Frame> replay;
        Surface s;
        synchronized (lock) {
            s = surface;
            replay = gopComplete ? new ArrayList<>(gop) : new ArrayList<>();
            // everything still queued is part of the replay; later frames go to the queue as usual
            queue.clear();
        }
        if (s == null || !s.isValid() || replay.isEmpty() || !replay.get(0).isConfig()) {
            return; // wait for a surface / the next key frame
        }
        Frame key = replay.get(0);
        session = createSession(key.sps, key.pps, s);
        if (session == null) return;
        Log.i(TAG, "decoder rebuilt, replaying " + replay.size() + " frames");
        for (Frame f : replay) {
            if (!isRunning()) return;
            session.feed(f);
        }
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /** One configured MediaCodec instance with its own output thread. */
    private static final class Session {
        final MediaCodec codec;
        final byte[] sps;
        final byte[] pps;
        final SizeListener sizeListener;
        final VideoDecoder owner;
        volatile boolean active = true;
        volatile boolean failed;
        Thread drainer;

        private Session(MediaCodec codec, byte[] sps, byte[] pps, SizeListener l, VideoDecoder owner) {
            this.codec = codec;
            this.sps = sps;
            this.pps = pps;
            this.sizeListener = l;
            this.owner = owner;
        }

        void feed(Frame frame) throws InterruptedException {
            while (active && !failed) {
                int index;
                try {
                    index = codec.dequeueInputBuffer(100_000);
                } catch (IllegalStateException e) {
                    fail(e);
                    return;
                }
                if (index < 0) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    continue; // keep waiting: dropping a frame would corrupt the picture
                }
                try {
                    ByteBuffer buf = codec.getInputBuffer(index);
                    if (buf == null || frame.data.length > buf.capacity()) {
                        Log.w(TAG, "frame of " + frame.data.length + " bytes does not fit, decoder rebuilt");
                        codec.queueInputBuffer(index, 0, 0, frame.seq, 0);
                        fail(null);
                        return;
                    }
                    buf.clear();
                    buf.put(frame.data);
                    codec.queueInputBuffer(index, 0, frame.data.length, frame.seq,
                            frame.isConfig() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                } catch (IllegalStateException e) {
                    fail(e);
                }
                return;
            }
        }

        void drainLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (active && !failed) {
                try {
                    int index = codec.dequeueOutputBuffer(info, 20_000);
                    if (index >= 0) {
                        boolean show = owner.newestSeq - info.presentationTimeUs <= MAX_DISPLAY_LAG;
                        codec.releaseOutputBuffer(index, show);
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        reportSize(codec.getOutputFormat());
                    }
                } catch (IllegalStateException e) {
                    if (active) fail(e);
                }
            }
        }

        void fail(Exception e) {
            if (e != null) Log.e(TAG, "decoder error, rebuilding", e);
            failed = true;
        }

        void reportSize(MediaFormat f) {
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

        void close() {
            active = false;
            if (drainer != null) {
                drainer.interrupt();
                try {
                    drainer.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Session createSession(byte[] sps, byte[] pps, Surface s) {
        int[] size = SpsParser.size(sps);
        int width = size != null ? size[0] : 1920;
        int height = size != null ? size[1] : 1080;
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height * 2, 2 * 1024 * 1024));
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(MIME);
            codec.configure(format, s, null, 0);
            codec.start();
        } catch (Exception e) {
            Log.e(TAG, "cannot start decoder", e);
            if (codec != null) codec.release();
            return null;
        }
        Log.i(TAG, "decoder " + codec.getName() + " started for " + width + "x" + height);
        sizeListener.onVideoSizeChanged(width, height);
        Session session = new Session(codec, sps, pps, sizeListener, this);
        session.drainer = new Thread(session::drainLoop, "CastCraftVideoDrain");
        session.drainer.start();
        return session;
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
