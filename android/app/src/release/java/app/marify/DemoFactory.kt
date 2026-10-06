package app.marify

import app.marify.session.DemoSupport
import app.marify.session.NoDemo

/** Release builds have no demo machine (and no kiratest code). */
object DemoFactory {
    fun create(): DemoSupport = NoDemo
}
