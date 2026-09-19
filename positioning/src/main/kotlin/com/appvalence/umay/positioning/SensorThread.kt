package com.appvalence.umay.positioning

import android.os.Handler
import android.os.HandlerThread

/**
 * Where raw sensor events are delivered — never the main thread. Registering a
 * listener without a Handler puts every event on the main thread, and step
 * detection alone listens to two sensors at game rate (~100 events a second
 * combined), all for as long as a walk or a survey lasts: steady load on the
 * thread that also has to draw every frame. Only the occasional *result* (a
 * detected step, a pressure reading) reaches collectors, on whatever context
 * they collect in.
 *
 * One thread for all sensor listeners in the app, started on first use and
 * kept for the process's lifetime.
 */
internal object SensorThread {
    val handler: Handler by lazy {
        val thread = HandlerThread("umay-sensors").apply { start() }
        Handler(thread.looper)
    }
}
