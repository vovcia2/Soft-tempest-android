package pl.vovcia.softtempest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which backend currently has the noise window attached. Observed by [MainActivity]. */
object OverlayState {

    enum class Backend {
        NONE,
        /** `TYPE_APPLICATION_OVERLAY` from [OverlayService]: capped opacity, below the keyguard. */
        SYSTEM_ALERT_WINDOW,
        /** `TYPE_ACCESSIBILITY_OVERLAY` from [NoiseAccessibilityService]: trusted, full strength. */
        ACCESSIBILITY
    }

    private val _backend = MutableStateFlow(Backend.NONE)
    val backend: StateFlow<Backend> = _backend

    fun set(backend: Backend) {
        _backend.value = backend
    }

    /** Clears the state only if [backend] is the one currently recorded. */
    fun clear(backend: Backend) {
        _backend.compareAndSet(backend, Backend.NONE)
    }
}
