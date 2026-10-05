package app.kira

import android.app.Application
import app.kira.ble.BleScanner
import app.kira.data.MachineRepository
import app.kira.keys.AndroidAnchorKeys
import app.kira.keys.BiometricHost
import app.kira.keys.NoiseKeyStore
import app.kira.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency injection: one instance of each service per process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val biometricHost = BiometricHost()
    val scanner = BleScanner(app)
    val machines = MachineRepository(app)
    val noise = NoiseKeyStore(app)
    val anchorKeys = AndroidAnchorKeys(app, biometricHost)
    val sessions = SessionManager(app, scope, scanner, machines, noise, anchorKeys, DemoFactory.create())
}

class KiraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch { container.machines.load() }
    }
}
