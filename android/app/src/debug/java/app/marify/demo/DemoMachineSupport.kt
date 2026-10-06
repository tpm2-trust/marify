package app.marify.demo

import app.marify.ble.Link
import app.marify.core.KiraCore
import app.marify.session.DemoSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One kiratest demo machine per process ("Thinkpad-X1"). Its identity lives
 * in memory: after the app restarts, a demo machine enrolled earlier no
 * longer exists and its record should be deleted.
 */
class DemoMachineSupport : DemoSupport {
    private val demo by lazy { kiratest.Kiratest.newDemoMachine() }

    override val available = true

    override fun enrolLink(): suspend () -> Link = { withContext(Dispatchers.IO) { DemoLink.enrol(demo) } }

    override fun attestLink(): suspend () -> Link = { withContext(Dispatchers.IO) { DemoLink.attest(demo) } }

    override fun owns(recordJson: String): Boolean =
        KiraCore.matchAdvertisement(demo.serviceData(), recordJson)

    override fun nextBoot() = demo.nextBoot()
    override fun changePcr(index: Int) = demo.changePCR(index.toLong())
    override fun rollbackResetCount() = demo.rollbackResetCount()
    override fun screenCode(): String = demo.lastBootCode()
    override fun refuseBootKey(refuse: Boolean) = demo.refuseBootKey(refuse)
}
