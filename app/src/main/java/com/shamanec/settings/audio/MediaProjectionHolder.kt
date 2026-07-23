package com.shamanec.settings.audio

import android.media.projection.MediaProjection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton that holds the active MediaProjection instance and notifies listeners when it becomes available.
 *
 * AudioService sets the projection (obtained via AudioProjectionActivity) when internal audio
 * capture is requested, and registers a listener to start capture once the projection is ready.
 */
object MediaProjectionHolder {
    @Volatile private var mediaProjection: MediaProjection? = null
    private val listeners = CopyOnWriteArrayList<(MediaProjection) -> Unit>()

    fun set(mp: MediaProjection) {
        mediaProjection = mp
        listeners.forEach { it(mp) }
        listeners.clear()
    }

    fun clear() {
        mediaProjection = null
    }

    /**
     * Invokes [callback] immediately if a MediaProjection is already available,
     * otherwise registers [callback] to be called when one becomes available.
     */
    fun getOrListen(callback: (MediaProjection) -> Unit) {
        val mp = mediaProjection
        if (mp != null) callback(mp)
        else listeners.add(callback)
    }
}
