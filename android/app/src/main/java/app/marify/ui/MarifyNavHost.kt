package app.marify.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.marify.AppContainer

private object Routes {
    const val ONBOARDING = "onboarding"
    const val MACHINES = "machines"
    const val ENROL = "enrol"
    const val SESSION = "session"
    const val MACHINE = "machine/{id}"
    fun machine(id: String) = "machine/$id"
}

@Composable
fun MarifyNavHost(c: AppContainer, nav: NavHostController = rememberNavController()) {
    val ctx = LocalContext.current
    val start = if (Requirements.allMet(ctx)) Routes.MACHINES else Routes.ONBOARDING

    fun openSession() = nav.navigate(Routes.SESSION) { launchSingleTop = true }

    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onReady = {
                nav.navigate(Routes.MACHINES) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.MACHINES) {
            MachinesScreen(
                vm = viewModel { MachinesViewModel(c) },
                onEnrol = { nav.navigate(Routes.ENROL) },
                onOpenMachine = { nav.navigate(Routes.machine(it)) },
                onOpenSession = ::openSession,
            )
        }
        composable(Routes.ENROL) {
            EnrolScreen(
                vm = viewModel { EnrolViewModel(c) },
                onBack = { nav.popBackStack() },
                onSession = {
                    nav.navigate(Routes.SESSION) { popUpTo(Routes.MACHINES) }
                },
            )
        }
        composable(Routes.SESSION) {
            SessionScreen(c.sessions, onClose = { nav.popBackStack(Routes.MACHINES, inclusive = false) })
        }
        composable(Routes.MACHINE) { entry ->
            MachineDetailsScreen(
                c = c,
                deviceId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onSession = ::openSession,
            )
        }
    }
}
