package app.marify.keys

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The activity a biometric prompt can be shown on. MainActivity registers
 * itself while started; a signature requested while the app is in the
 * background waits until the user returns.
 */
class BiometricHost {
    private val _activity = MutableStateFlow<FragmentActivity?>(null)
    val activity: StateFlow<FragmentActivity?> get() = _activity

    fun attach(a: FragmentActivity) { _activity.value = a }
    fun detach(a: FragmentActivity) { _activity.compareAndSet(a, null) }
}
