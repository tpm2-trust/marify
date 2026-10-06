package app.marify

import app.marify.demo.DemoMachineSupport
import app.marify.session.DemoSupport

/** Debug builds offer the kiratest demo machine for trying the app without hardware. */
object DemoFactory {
    fun create(): DemoSupport = DemoMachineSupport()
}
