package app.marify

import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: requesting a runtime permission from MainActivity crashed with
 * "Can only use lower 16 bits for requestCode" because the biometric library
 * pulled in androidx.fragment 1.2.5. The request is validated before Android
 * looks at what is granted, so this test fails on the old library whatever
 * the permission state.
 */
@RunWith(AndroidJUnit4::class)
class PermissionRequestTest {
    @Test fun permissionRequestDoesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.activityResultRegistry
                    .register("test-permissions", ActivityResultContracts.RequestMultiplePermissions()) {}
                    .launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }
}
