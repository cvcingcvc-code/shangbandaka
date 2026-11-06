package com.mocklocation.lab

/** Client-side deadline also applies when the UI timeout Runnable is delayed. */
internal class PlaceSearchCompletionGate(private val startedAtMillis: Long, private val timeoutMillis: Long) {
    enum class Completion { ACCEPT, TIMED_OUT, IGNORE }
    private var finished = false
    private var cancelled = false
    @Synchronized fun isActive(): Boolean = !finished && !cancelled
    @Synchronized fun isCancelled(): Boolean = cancelled
    @Synchronized fun expired(nowMillis: Long): Boolean = nowMillis < startedAtMillis || nowMillis - startedAtMillis >= timeoutMillis
    @Synchronized fun complete(nowMillis: Long): Completion {
        if (finished || cancelled) return Completion.IGNORE
        finished = true
        return if (expired(nowMillis)) Completion.TIMED_OUT else Completion.ACCEPT
    }
    @Synchronized fun cancel() { cancelled = true; finished = true }
}
