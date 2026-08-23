package io.github.dorumrr.de1984.ui.common

import io.github.dorumrr.de1984.data.common.De1984Error
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State manager for the superuser banner
 */
class SuperuserBannerState {

    private val _showBanner = MutableStateFlow(false)
    val showBanner: StateFlow<Boolean> = _showBanner.asStateFlow()

    private var autoDismissJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun showSuperuserRequiredBanner() {
        _showBanner.value = true

        autoDismissJob?.cancel()

        autoDismissJob = scope.launch {
            delay(10000)
            _showBanner.value = false
        }
    }

    fun hideBanner() {
        autoDismissJob?.cancel()
        _showBanner.value = false
    }

    /**
     * Should the "grant root or Shizuku" banner be shown for this failure?
     *
     * Type first, text second. This used to match ONLY on English words in the message, so on a
     * translated device the banner never appeared - and the banner is the one thing that tells the
     * user what to do about the failure. They saw a raw error line instead, in every locale but
     * English.
     *
     * The string checks are kept as a fallback for the paths that still throw a plain
     * SecurityException instead of a typed error.
     */
    fun shouldShowBannerForError(error: Throwable?): Boolean {
        if (error is De1984Error.RootRequired) return true

        return error is SecurityException &&
               (error.message?.contains("Shizuku", ignoreCase = true) == true ||
                error.message?.contains("Root access required", ignoreCase = true) == true ||
                error.message?.contains("superuser", ignoreCase = true) == true)
    }
}

