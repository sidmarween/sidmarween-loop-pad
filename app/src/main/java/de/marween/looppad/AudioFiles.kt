package de.marween.looppad

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Import (WAV/MP3/…) und Export (WAV) von Loops. Alles mono, auf die Engine-Samplerate umgerechnet. */
object AudioFiles {

    private class FloatList(cap: Int) {
        var data = FloatArray(cap)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** Dekodiert eine Audiodatei zu mono Float-Samples mit [targetRate], höchstens [maxSeconds] lang. */
    fun decode(context: Context, uri: Uri, targetRate: Int, maxSeconds: Int): FloatArray {
        val head = ByteArray(12)
        val headLen = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
        val isWav = headLen >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
        if (isWav) {
            try {
                return decodeWav(context, uri, targetRate, maxSeconds)
            } catch (e: Exception) {
                // Ungewöhnliches WAV-Format -> Android-Decoder versuchen
            }
        }
        return decodeWithCodec(context, uri, targetRate, maxSeconds)
    }

    // ------------------------------------------------------------------ WAV

    private fun decodeWav(context: Context, uri: Uri, targetRate: Int, maxSeconds: Int): FloatArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Datei nicht lesbar")
        return decodeWavBytes(bytes, targetRate, maxSeconds)
    }

    /** Lädt eine gespeicherte WAV-Datei aus dem App-Speicher. */
    fun decodeFile(file: java.io.File, targetRate: Int, maxSeconds: Int): FloatArray =
        decodeWavBytes(file.readBytes(), targetRate, maxSeconds)

    /** Lädt ein mitgeliefertes Sample aus assets/samples. */
    fun decodeAsset(context: Context, file: String, targetRate: Int, maxSeconds: Int): FloatArray {
        val bytes = context.assets.open("samples/$file").use { it.readBytes() }
        return decodeWavBytes(bytes, targetRate, maxSeconds)
    }

    private fun decodeWavBytes(bytes: ByteArray, targetRate: Int, maxSeconds: Int): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 1
        var channels = 1
        var rate = 44100
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                format = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
                if (format == 0xFFFE && len >= 26) format = bb.getShort(body + 24).toInt() and 0xFFFF
            } else if (id == "data") {
                dataOff = body
                dataLen = min(if (len < 0) Int.MAX_VALUE else len, bytes.size - body)
                break
            }
            pos = body + len + (len and 1)
            if (len < 0) break
        }
        if (dataOff < 0 || channels < 1) throw IllegalStateException("Kein Audio in der WAV-Datei")
        val bytesPer = bits / 8
        if (bytesPer < 1) throw IllegalStateException("Unbekanntes WAV-Format")
        val frameBytes = bytesPer * channels
        val maxFrames = rate.toLong() * maxSeconds
        val frames = min((dataLen / frameBytes).toLong(), maxFrames).toInt()
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val o = dataOff + f * frameBytes + c * bytesPer
                sum += when {
                    format == 3 && bits == 32 -> bb.getFloat(o)
                    format == 3 && bits == 64 -> bb.getDouble(o).toFloat()
                    bits == 8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 24 -> {
                        val v = (bytes[o].toInt() and 0xFF) or
                            ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                            (bytes[o + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    else -> throw IllegalStateException("Unbekanntes WAV-Format")
                }
            }
            out[f] = sum / channels
        }
        return resample(out, rate, targetRate)
    }

    // ------------------------------------------------------------------ MP3 & Co.

    private fun decodeWithCodec(context: Context, uri: Uri, targetRate: Int, maxSeconds: Int): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("Keine Audiospur gefunden")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val list = FloatList(rate * 4)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frames = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val b = ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val maxFrames = rate.toLong() * maxSeconds
                        if (floatPcm) {
                            val fb = b.asFloatBuffer()
                            val n = fb.remaining() / channels
                            for (f in 0 until n) {
                                if (frames >= maxFrames) break
                                var s = 0f
                                for (ch in 0 until channels) s += fb.get(f * channels + ch)
                                list.add(s / channels)
                                frames++
                            }
                        } else {
                            val sb = b.asShortBuffer()
                            val n = sb.remaining() / channels
                            for (f in 0 until n) {
                                if (frames >= maxFrames) break
                                var s = 0f
                                for (ch in 0 until channels) s += sb.get(f * channels + ch) / 32768f
                                list.add(s / channels)
                                frames++
                            }
                        }
                        if (frames >= maxFrames) outputDone = true
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            if (list.size == 0) throw IllegalStateException("Datei enthält kein Audio")
            return resample(list.toArray(), rate, targetRate)
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            ex.release()
        }
    }

    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || src.isEmpty()) return src
        val outLen = (src.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val p = i * step
            val i0 = p.toInt()
            val i1 = min(i0 + 1, src.size - 1)
            val fr = (p - i0).toFloat()
            out[i] = src[i0] + (src[i1] - src[i0]) * fr
        }
        return out
    }

    // ------------------------------------------------------------------ Export

    /** Schreibt bereits gewandelte 16-Bit-Samples (mono) als WAV. */
    fun writeWav(out: OutputStream, data: ShortArray, rate: Int) {
        val dataBytes = data.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())
        val body = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) {
            if (body.remaining() < 2) {
                out.write(body.array(), 0, body.position())
                body.clear()
            }
            body.putShort(v)
        }
        out.write(body.array(), 0, body.position())
        out.flush()
    }

    /** Schreibt mono 16-Bit-WAV. */
    fun writeWav(out: OutputStream, data: FloatArray, rate: Int) {
        val dataBytes = data.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) body.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(body.array())
        out.flush()
    }
}
