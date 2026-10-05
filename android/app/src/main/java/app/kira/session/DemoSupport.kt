package app.kira.session

import app.kira.ble.Link

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
}

object NoDemo : DemoSupport {
    override val available = false
    override fun enrolLink(): suspend () -> Link = error("no demo machine in this build")
    override fun attestLink(): suspend () -> Link = error("no demo machine in this build")
    override fun owns(recordJson: String) = false
    override fun nextBoot() = Unit
    override fun changePcr(index: Int) = Unit
    override fun rollbackResetCount() = Unit
}
