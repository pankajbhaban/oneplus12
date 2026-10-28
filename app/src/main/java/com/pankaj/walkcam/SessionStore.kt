package com.pankaj.walkcam

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * Remembers the recording session on disk so it survives the app being closed,
 * swiped away, or killed. Each start/resume writes a new clip ("segment") into
 * [dir]; "End & Save" joins all clips into one video.
 */
class SessionStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("walkcam_session", Context.MODE_PRIVATE)

    val dir: File = File(
        ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir,
        "session"
    ).apply { mkdirs() }

    /** A session exists (started and not yet ended). */
    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(v) { prefs.edit().putBoolean("active", v).commit() }

    /** The user wants the camera to be recording (false after Pause). */
    var wantRecording: Boolean
        get() = prefs.getBoolean("want", false)
        set(v) { prefs.edit().putBoolean("want", v).commit() }

    /** Total recorded time of finished clips, in ms. */
    var totalMs: Long
        get() = prefs.getLong("total", 0L)
        set(v) { prefs.edit().putLong("total", v).apply() }

    var startedAt: Long
        get() = prefs.getLong("started", 0L)
        set(v) { prefs.edit().putLong("started", v).apply() }

    /** Camera mode used by this session, so every clip has the same format. */
    var configKey: String?
        get() = prefs.getString("config", null)
        set(v) { prefs.edit().putString("config", v).apply() }

    var torchMode: TorchMode
        get() = runCatching { TorchMode.valueOf(prefs.getString("torch", "AUTO")!!) }.getOrDefault(TorchMode.AUTO)
        set(v) { prefs.edit().putString("torch", v.name).apply() }

    fun segments(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") && f.length() > 0 }
            ?.sortedBy { it.name } ?: emptyList()

    fun nextSegmentFile(): File {
        val last = dir.listFiles()
            ?.mapNotNull { it.name.removePrefix("seg_").removeSuffix(".mp4").toIntOrNull() }
            ?.maxOrNull() ?: 0
        return File(dir, String.format(Locale.US, "seg_%05d.mp4", last + 1))
    }

    fun begin() {
        dir.listFiles()?.forEach { it.delete() }
        totalMs = 0L
        startedAt = System.currentTimeMillis()
        configKey = null
        active = true
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        wantRecording = false
        totalMs = 0L
        startedAt = 0L
        configKey = null
        active = false
    }
}
