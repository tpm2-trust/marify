package app.kira

import app.kira.demo.DemoMachineSupport
import app.kira.session.DemoSupport

/** Debug builds offer the kiratest demo machine for trying the app without hardware. */
object DemoFactory {
    fun create(): DemoSupport = DemoMachineSupport()
}
