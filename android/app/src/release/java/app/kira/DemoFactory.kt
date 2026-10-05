package app.kira

import app.kira.session.DemoSupport
import app.kira.session.NoDemo

/** Release builds have no demo machine (and no kiratest code). */
object DemoFactory {
    fun create(): DemoSupport = NoDemo
}
