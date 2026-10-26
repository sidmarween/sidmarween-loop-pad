package de.marween.looppad

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV → AAC (.m4a) im Musik-Ordner „Marv PAD“, damit es WhatsApp, Instagram & Co. annehmen. */
object ShareAudio {

    private class Wav(val rate: Int, val ch: Int, val dataStart: Long, val dataLen: Long)

    private fun parse(f: File): Wav {
        FileInputStream(f).use { inp ->
            val head = ByteArray(12)
            if (inp.read(head) != 12) throw IllegalStateException("Keine WAV-Datei")
            var pos = 12L
            var rate = 48000
            var ch = 2
            val hdr = ByteArray(8)
            while (true) {
                if (inp.read(hdr) != 8) throw IllegalStateException("WAV ohne Daten")
                pos += 8
                val id = String(hdr, 0, 4, Charsets.US_ASCII)
                val len = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fb = ByteArray(len.toInt())
                    inp.read(fb)
                    val bb = ByteBuffer.wrap(fb).order(ByteOrder.LITTLE_ENDIAN)
                    ch = bb.getShort(2).toInt()
                    rate = bb.getInt(4)
                    pos += len
                } else if (id == "data") {
                    val real = f.length() - pos
                    return Wav(rate, ch, pos, if (len == 0L || len > real) real else len)
                } else {
                    inp.skip(len); pos += len
                }
            }
        }
    }

    /** Kodiert [wav] (16 Bit) als AAC und legt es unter Musik/Marv PAD ab. [progress] 0..1. */
    fun toMusic(ctx: Context, wav: File, title: String, progress: (Float) -> Unit): Uri {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Teilen braucht Android 10 oder neuer")
        val w = parse(wav)
        val tmp = File(ctx.cacheDir, "share.m4a")
        tmp.delete()
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, w.rate, w.ch)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val frameBytes = 2 * w.ch
        try {
            BufferedInputStream(FileInputStream(wav), 1 shl 16).use { inp ->
                inp.skip(w.dataStart)
                var left = w.dataLen
                var frames = 0L
                var inDone = false
                var outDone = false
                val info = MediaCodec.BufferInfo()
                val tmpBuf = ByteArray(16384)
                while (!outDone) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            ib.clear()
                            val want = minOf(ib.capacity().toLong(), left, tmpBuf.size.toLong()).toInt() / frameBytes * frameBytes
                            val n = if (want > 0) inp.read(tmpBuf, 0, want) else -1
                            val pts = frames * 1_000_000L / w.rate
                            if (n <= 0) {
                                codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                val nn = n / frameBytes * frameBytes
                                ib.put(tmpBuf, 0, nn)
                                codec.queueInputBuffer(ii, 0, nn, pts, 0)
                                frames += nn / frameBytes
                                left -= nn
                                if (frames % (w.rate * 2L) < 4096) progress(1f - left.toFloat() / w.dataLen)
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 5_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(codec.outputFormat)
                        mux.start()
                        started = true
                    } else if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            mux.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (ignored: Exception) {}
            codec.release()
            try { if (started) mux.stop() } catch (ignored: Exception) {}
            mux.release()
        }
        // in den Musik-Ordner kopieren
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.m4a")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, "Marv PAD")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Marv PAD")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val res = ctx.contentResolver
        val uri = res.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Musik-Ordner nicht beschreibbar")
        res.openOutputStream(uri)?.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
        cv.clear()
        cv.put(MediaStore.Audio.Media.IS_PENDING, 0)
        res.update(uri, cv, null, null)
        tmp.delete()
        progress(1f)
        return uri
    }
}
