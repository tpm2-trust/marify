package app.marify

import android.app.Application
import app.marify.ble.BleScanner
import app.marify.data.MachineRepository
import app.marify.keys.AndroidAnchorKeys
import app.marify.keys.BiometricHost
import app.marify.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection: one instance of each service per process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val biometricHost = BiometricHost()
    val scanner = BleScanner(app)
    val machines = MachineRepository(app)
    val anchorKeys = AndroidAnchorKeys(app, biometricHost)
    val sessions = SessionManager(app, scope, scanner, machines, anchorKeys, DemoFactory.create())
}

class MarifyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
