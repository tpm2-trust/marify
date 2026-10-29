package app.marify

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marify.core.KiraCore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Go core in this APK carries the EK vendor roots (tpm2-kira's core/attest/ekroots). */
@RunWith(AndroidJUnit4::class)
class CoreOnDeviceTest {
    @Test fun ekVendorRootsAreEmbedded() {
        val vendors = KiraCore.ekVendors()
        assertTrue(vendors.toString(), "Intel Corporation" in vendors)
    }
}
