package app.marify

import app.marify.ble.InfoValue
import app.marify.ble.InfoValue.Compatibility
import org.junit.Assert.assertEquals
import org.junit.Test

class InfoValueTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun parse() = assertEquals(
        InfoValue(protocol = 1, mode = 2, schema = 1, capabilities = 0x01020304L),
        InfoValue.parse(bytes(1, 2, 1, 0, 4, 3, 2, 1)),
    )

    @Test fun capabilitiesUnsigned() =
        assertEquals(0xffffffffL, InfoValue.parse(bytes(1, 1, 1, 0, 255, 255, 255, 255)).capabilities)

    @Test fun compatibility() {
        val ok = InfoValue(1, InfoValue.MODE_ATTEST, 1, 0)
        assertEquals(Compatibility.Ok, ok.check(InfoValue.MODE_ATTEST, 1, 1))
        assertEquals(Compatibility.WrongMode(2), ok.check(InfoValue.MODE_ENROL, 1, 1))
        assertEquals(Compatibility.UpdateNeeded(true), InfoValue(2, 2, 1, 0).check(2, 1, 1))
        assertEquals(Compatibility.UpdateNeeded(false), InfoValue(0, 2, 1, 0).check(2, 1, 1))
        assertEquals(Compatibility.UpdateNeeded(true), InfoValue(1, 2, 2, 0).check(2, 1, 1))
        // An older schema on the machine is the core's business, not INFO's.
        assertEquals(Compatibility.Ok, InfoValue(1, 2, 1, 0).check(2, 1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooShort() { InfoValue.parse(bytes(1, 2, 1)) }
}
