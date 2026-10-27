package com.mocklocation.lab

import android.os.Handler
import android.os.Looper
import android.util.Log

/** In-process snapshot: it is never persisted as proof that the service survived a process death. */
object MockLocationStatus {
    enum class Phase { IDLE, PREPARING, REGISTERING, SUCCESS, FAILED, STOPPED }
    data class Status(val phase: Phase, val message: String)

    @Volatile var current = Status(Phase.IDLE, "尚未开始")
        private set
    private val listeners = mutableSetOf<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (Status) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Status) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.remove(listener)
    }

    internal fun update(phase: Phase, message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { update(phase, message) }
            return
        }
        current = Status(phase, message)
        Log.i("MockLocationStatus", "$phase: $message")
        listeners.toList().forEach { it(current) }
    }
}
