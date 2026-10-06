package app.marify.session

import app.marify.ble.Link

/**
 * The simulated machine from kiratest, for trying the app without Linux
 * hardware. Implemented in the debug source set only; release builds get
 * [NoDemo] and contain no kiratest code.
 */
interface DemoSupport {
    val available: Boolean
    fun enrolLink(): suspend () -> Link
    fun attestLink(): suspend () -> Link
    /** Whether this record belongs to the demo machine of this process. */
    fun owns(recordJson: String): Boolean
    fun nextBoot()
    fun changePcr(index: Int)
    fun rollbackResetCount()
    /** What the demo machine's screen shows during an attestation: its boot code, or "" (it has no real screen). */
    fun screenCode(): String
    /** Make the demo machine's TPM refuse its boot key, as in an unapproved boot state. */
    fun refuseBootKey(refuse: Boolean)
}

object NoDemo : DemoSupport {
    override val available = false
    override fun enrolLink(): suspend () -> Link = error("no demo machine in this build")
    override fun attestLink(): suspend () -> Link = error("no demo machine in this build")
    override fun owns(recordJson: String) = false
    override fun nextBoot() = Unit
    override fun changePcr(index: Int) = Unit
    override fun rollbackResetCount() = Unit
    override fun screenCode() = ""
    override fun refuseBootKey(refuse: Boolean) = Unit
}
