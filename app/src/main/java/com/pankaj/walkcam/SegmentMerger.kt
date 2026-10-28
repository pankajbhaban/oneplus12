package com.pankaj.walkcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Joins the session's clips into ONE video without re-encoding (lossless and fast,
 * even for 4K60), and saves it to the gallery under Movies/WalkCam.
 */
object SegmentMerger {

    private const val ALBUM = "Movies/WalkCam"
    private const val BUFFER_BYTES = 24 * 1024 * 1024

    class Result(
        val width: Int, val height: Int, val fps: Double,
        val durationUs: Long, val clips: Int, val skipped: Int
    ) {
        fun summary(): String {
            val s = durationUs / 1_000_000
            val time = if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                       else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
            val res = if (width >= 3840 || height >= 3840) "4K" else "${width}x$height"
            var msg = String.format(Locale.US, "%s · %.0f fps · %s", res, fps, time)
            if (clips > 1) msg += " · $clips clips joined"
            if (skipped > 0) msg += " · $skipped damaged clip(s) skipped"
            return msg
        }
    }

    private class Probe(
        val videoFormat: MediaFormat, val audioFormat: MediaFormat?,
        val videoMime: String, val audioMime: String?,
        val width: Int, val height: Int, val rotation: Int, val frameRate: Int
    )

    private fun probe(f: File): Probe? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            var v: MediaFormat? = null
            var a: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && v == null) v = fmt
                if (mime.startsWith("audio/") && a == null) a = fmt
            }
            val vf = v ?: return null
            Probe(
                vf, a,
                vf.getString(MediaFormat.KEY_MIME)!!, a?.getString(MediaFormat.KEY_MIME),
                vf.getInteger(MediaFormat.KEY_WIDTH), vf.getInteger(MediaFormat.KEY_HEIGHT),
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0,
                if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { vf.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30) else 30
            )
        } catch (e: Exception) {
            null // e.g. a clip cut off by a crash / dead battery
        } finally {
            ex.release()
        }
    }

    fun merge(ctx: Context, segments: List<File>, name: String): Result {
        val probes = segments.map { it to probe(it) }
        val usable = probes.filter { it.second != null }.map { it.first to it.second!! }
        val skipped = probes.size - usable.size
        if (usable.isEmpty()) throw IllegalStateException("no playable clips")

        val ref = usable.first().second
        usable.forEach { (_, p) ->
            require(p.videoMime == ref.videoMime && p.width == ref.width && p.height == ref.height &&
                    p.audioMime == ref.audioMime) { "clips have different formats" }
        }

        val resolver = ctx.contentResolver
        val uri = newVideo(ctx, "$name.mp4")
        var muxer: MediaMuxer? = null
        var started = false
        try {
            var durationUs = 0L
            var videoFrames = 0L
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val mx = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mx
                val vOut = mx.addTrack(ref.videoFormat)
                val aOut = ref.audioFormat?.let { mx.addTrack(it) } ?: -1
                mx.setOrientationHint(ref.rotation)
                mx.start()
                started = true

                val buf = ByteBuffer.allocateDirect(BUFFER_BYTES)
                val info = MediaCodec.BufferInfo()
                val frameUs = 1_000_000L / ref.frameRate.coerceAtLeast(1)
                var offsetUs = 0L

                for ((file, _) in usable) {
                    val ex = MediaExtractor()
                    ex.setDataSource(file.absolutePath)
                    val map = HashMap<Int, Int>()
                    for (t in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME) ?: continue
                        val out = when {
                            mime.startsWith("video/") -> vOut
                            mime.startsWith("audio/") && aOut >= 0 -> aOut
                            else -> -1
                        }
                        if (out >= 0 && !map.containsValue(out)) {
                            ex.selectTrack(t)
                            map[t] = out
                        }
                    }
                    var base = -1L
                    var segEnd = offsetUs
                    while (true) {
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val out = map[ex.sampleTrackIndex]
                        val t = ex.sampleTime
                        if (out != null && t >= 0) {
                            if (base < 0) base = t
                            val pts = offsetUs + (t - base).coerceAtLeast(0L)
                            val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, size, pts, flags)
                            mx.writeSampleData(out, buf, info)
                            if (out == vOut) videoFrames++
                            if (pts > segEnd) segEnd = pts
                        }
                        ex.advance()
                    }
                    ex.release()
                    offsetUs = segEnd + frameUs
                }
                durationUs = offsetUs

                mx.stop()
                started = false
                mx.release()
                muxer = null
            }
            publish(ctx, uri)
            val fps = if (durationUs > 0) videoFrames * 1_000_000.0 / durationUs else 0.0
            val (w, h) = if (ref.rotation % 180 != 0) ref.height to ref.width else ref.width to ref.height
            return Result(w, h, fps, durationUs, usable.size, skipped)
        } catch (e: Exception) {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /** Fallback if joining is impossible: save each clip to the gallery as-is. */
    fun exportSeparately(ctx: Context, segments: List<File>, name: String): Int {
        var n = 0
        segments.forEachIndexed { i, f ->
            val uri = newVideo(ctx, "${name}_part${i + 1}.mp4")
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
            publish(ctx, uri)
            n++
        }
        return n
    }

    private fun newVideo(ctx: Context, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: throw IllegalStateException("could not create gallery file")
    }

    private fun publish(ctx: Context, uri: Uri) {
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
    }
}
