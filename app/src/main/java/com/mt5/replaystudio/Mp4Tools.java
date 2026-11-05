package com.mt5.replaystudio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Recorded video ko TikTok / Gallery friendly standard MP4 (H.264 + AAC) banata hai.
 *  - remux():          MP4 -> standard MP4 (re-encode nahi, sirf container theek)
 *  - transcodeWebm():  WebM (VP8/VP9 + Opus) -> MP4 (H.264 + AAC)
 */
final class Mp4Tools {

    private static final long TIMEOUT_US = 10000;
    private static final long MAX_MS = 15 * 60 * 1000;

    private Mp4Tools() { }

    // ------------------------------------------------------------------
    static void remux(String src, String dst) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(src);
            mux = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            int added = 0;
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && (m.startsWith("video/") || m.startsWith("audio/"))) {
                    map[i] = mux.addTrack(f);
                    ex.selectTrack(i);
                    added++;
                } else {
                    map[i] = -1;
                }
            }
            if (added == 0) throw new Exception("koi track nahi mila");
            mux.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                int ti = ex.getSampleTrackIndex();
                if (ti < 0) break;
                buf.clear();
                int sz = ex.readSampleData(buf, 0);
                if (sz < 0) break;
                int fl = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                bi.set(0, sz, ex.getSampleTime(), fl);
                if (map[ti] >= 0) mux.writeSampleData(map[ti], buf, bi);
                ex.advance();
            }
            mux.stop();
            started = false;
        } finally {
            if (mux != null) {
                try { if (started) mux.stop(); } catch (Exception ignored) { }
                try { mux.release(); } catch (Exception ignored) { }
            }
            ex.release();
        }
    }

    // ------------------------------------------------------------------
    static void transcodeWebm(String src, String dst, File tmpDir) throws Exception {
        File v = new File(tmpDir, "tv_" + System.nanoTime() + ".mp4");
        File a = new File(tmpDir, "ta_" + System.nanoTime() + ".mp4");
        try {
            transcodeVideo(src, v.getPath());
            boolean hasAudio = false;
            try {
                hasAudio = transcodeAudio(src, a.getPath());
            } catch (Throwable t) {
                hasAudio = false; // audio fail ho to bhi video save ho jaye
            }
            merge(v.getPath(), hasAudio ? a.getPath() : null, dst);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            v.delete();
            //noinspection ResultOfMethodCallIgnored
            a.delete();
        }
    }

    // ------------------------------------------------------------------
    private static void transcodeVideo(String src, String dst) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        Surface surf = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(src);
            int vi = -1;
            MediaFormat vf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { vi = i; vf = f; break; }
            }
            if (vi < 0) throw new Exception("video track nahi mila");
            ex.selectTrack(vi);

            int w = vf.getInteger(MediaFormat.KEY_WIDTH) & ~1;
            int h = vf.getInteger(MediaFormat.KEY_HEIGHT) & ~1;
            MediaFormat ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surf = enc.createInputSurface();
            enc.start();

            dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME));
            dec.configure(vf, surf, null, 0);
            dec.start();

            mux = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            boolean inDone = false, decDone = false, encDone = false;
            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo();
            long t0 = System.currentTimeMillis();

            while (!encDone) {
                if (System.currentTimeMillis() - t0 > MAX_MS) throw new Exception("timeout");

                if (!inDone) {
                    int ib = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ib >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ib);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                if (!decDone) {
                    int ob = dec.dequeueOutputBuffer(di, TIMEOUT_US);
                    if (ob >= 0) {
                        boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        dec.releaseOutputBuffer(ob, di.size > 0);
                        if (eos) {
                            enc.signalEndOfInputStream();
                            decDone = true;
                        }
                    }
                }

                while (true) {
                    int eb = enc.dequeueOutputBuffer(ei, decDone ? TIMEOUT_US : 0);
                    if (eb == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (eb == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                        continue;
                    }
                    if (eb < 0) continue;
                    ByteBuffer out = enc.getOutputBuffer(eb);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                    if (ei.size > 0 && muxStarted && out != null) {
                        out.position(ei.offset);
                        out.limit(ei.offset + ei.size);
                        mux.writeSampleData(track, out, ei);
                    }
                    enc.releaseOutputBuffer(eb, false);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encDone = true;
                        break;
                    }
                }
            }
            if (!muxStarted) throw new Exception("encoder se koi frame nahi aaya");
            mux.stop();
            muxStarted = false;
        } finally {
            try { if (dec != null) { dec.stop(); } } catch (Exception ignored) { }
            try { if (dec != null) { dec.release(); } } catch (Exception ignored) { }
            try { if (enc != null) { enc.stop(); } } catch (Exception ignored) { }
            try { if (enc != null) { enc.release(); } } catch (Exception ignored) { }
            try { if (surf != null) surf.release(); } catch (Exception ignored) { }
            if (mux != null) {
                try { if (muxStarted) mux.stop(); } catch (Exception ignored) { }
                try { mux.release(); } catch (Exception ignored) { }
            }
            ex.release();
        }
    }

    // ------------------------------------------------------------------
    /** @return true agar audio track mili aur AAC ban gayi */
    private static boolean transcodeAudio(String src, String dst) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(src);
            int ai = -1;
            MediaFormat af = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { ai = i; af = f; break; }
            }
            if (ai < 0) return false;
            ex.selectTrack(ai);

            int sr = af.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = af.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            dec = MediaCodec.createDecoderByType(af.getString(MediaFormat.KEY_MIME));
            dec.configure(af, null, null, 0);
            dec.start();

            MediaFormat ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            mux = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            byte[] pcm = null;
            int off = 0;
            long fed = 0, base = 0;
            boolean first = true;
            boolean inDone = false, decEos = false, encEosSent = false, encDone = false;
            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo();
            long t0 = System.currentTimeMillis();
            long bytesPerSec = (long) sr * 2L * ch;

            while (!encDone) {
                if (System.currentTimeMillis() - t0 > MAX_MS) throw new Exception("audio timeout");

                if (!inDone) {
                    int ib = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ib >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ib);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                if (pcm == null && !decEos) {
                    int ob = dec.dequeueOutputBuffer(di, TIMEOUT_US);
                    if (ob >= 0) {
                        if (di.size > 0) {
                            ByteBuffer b = dec.getOutputBuffer(ob);
                            b.position(di.offset);
                            b.limit(di.offset + di.size);
                            pcm = new byte[di.size];
                            b.get(pcm);
                            off = 0;
                            if (first) { base = di.presentationTimeUs; first = false; }
                        }
                        if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decEos = true;
                        dec.releaseOutputBuffer(ob, false);
                    }
                }

                if (pcm != null) {
                    int ib = enc.dequeueInputBuffer(0);
                    if (ib >= 0) {
                        ByteBuffer b = enc.getInputBuffer(ib);
                        b.clear();
                        int n = Math.min(b.remaining(), pcm.length - off);
                        b.put(pcm, off, n);
                        long pts = base + fed * 1000000L / bytesPerSec;
                        enc.queueInputBuffer(ib, 0, n, pts, 0);
                        fed += n;
                        off += n;
                        if (off >= pcm.length) pcm = null;
                    }
                } else if (decEos && !encEosSent) {
                    int ib = enc.dequeueInputBuffer(0);
                    if (ib >= 0) {
                        long pts = base + fed * 1000000L / bytesPerSec;
                        enc.queueInputBuffer(ib, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encEosSent = true;
                    }
                }

                while (true) {
                    int eb = enc.dequeueOutputBuffer(ei, 2000);
                    if (eb == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (eb == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                        continue;
                    }
                    if (eb < 0) continue;
                    ByteBuffer out = enc.getOutputBuffer(eb);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                    if (ei.size > 0 && muxStarted && out != null) {
                        out.position(ei.offset);
                        out.limit(ei.offset + ei.size);
                        mux.writeSampleData(track, out, ei);
                    }
                    enc.releaseOutputBuffer(eb, false);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encDone = true;
                        break;
                    }
                }
            }
            if (!muxStarted) return false;
            mux.stop();
            muxStarted = false;
            return true;
        } finally {
            try { if (dec != null) { dec.stop(); } } catch (Exception ignored) { }
            try { if (dec != null) { dec.release(); } } catch (Exception ignored) { }
            try { if (enc != null) { enc.stop(); } } catch (Exception ignored) { }
            try { if (enc != null) { enc.release(); } } catch (Exception ignored) { }
            if (mux != null) {
                try { if (muxStarted) mux.stop(); } catch (Exception ignored) { }
                try { mux.release(); } catch (Exception ignored) { }
            }
            ex.release();
        }
    }

    // ------------------------------------------------------------------
    /** video-only mp4 + (optional) audio-only mp4 -> ek mp4 */
    private static void merge(String vPath, String aPath, String dst) throws Exception {
        MediaExtractor ev = new MediaExtractor();
        MediaExtractor ea = null;
        MediaMuxer m = null;
        boolean started = false;
        try {
            ev.setDataSource(vPath);
            ev.selectTrack(0);
            if (aPath != null) {
                ea = new MediaExtractor();
                ea.setDataSource(aPath);
                ea.selectTrack(0);
            }
            m = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = m.addTrack(ev.getTrackFormat(0));
            int at = ea != null ? m.addTrack(ea.getTrackFormat(0)) : -1;
            m.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                long tv = ev.getSampleTime();
                long ta = ea != null ? ea.getSampleTime() : -1;
                if (tv < 0 && ta < 0) break;
                boolean useV = tv >= 0 && (ta < 0 || tv <= ta);
                MediaExtractor e = useV ? ev : ea;
                int tr = useV ? vt : at;
                buf.clear();
                int sz = e.readSampleData(buf, 0);
                if (sz < 0) break;
                int fl = (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                bi.set(0, sz, e.getSampleTime(), fl);
                m.writeSampleData(tr, buf, bi);
                e.advance();
            }
            m.stop();
            started = false;
        } finally {
            if (m != null) {
                try { if (started) m.stop(); } catch (Exception ignored) { }
                try { m.release(); } catch (Exception ignored) { }
            }
            ev.release();
            if (ea != null) ea.release();
        }
    }
}
