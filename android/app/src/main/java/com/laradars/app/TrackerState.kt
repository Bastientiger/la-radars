package com.laradars.app

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** What the service knows right now, observed by the screen. */
data class Snapshot(
    val running: Boolean = false,
    val hasFix: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val speedMps: Float = 0f,
    val headingDeg: Double? = null,
    val nearest: AlertEngine.Nearest? = null,
    val speeding: Boolean = false,
    val onFreeway: Boolean = false,
)

object TrackerState {
    private val mutable = MutableLiveData(Snapshot())
    val live: LiveData<Snapshot> get() = mutable
    val current: Snapshot get() = mutable.value ?: Snapshot()

    /** True while the main screen is in front: the overlay banner is then redundant. */
    @Volatile var appVisible: Boolean = false

    /** Set when a trip ends, so the screen can show its summary once. */
    @Volatile var lastTrip: Trip? = null

    /** Synchronous on the main thread, so `current` is right as soon as the service changes state. */
    fun post(s: Snapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) mutable.value = s else mutable.postValue(s)
    }
}
