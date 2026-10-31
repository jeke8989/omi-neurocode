package codes.neuro.neurosoft

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import codes.neuro.neurosoft.ui.AppRoot
import codes.neuro.neurosoft.ui.MnemoTheme
import codes.neuro.neurosoft.ui.Nav
import codes.neuro.neurosoft.ui.Route

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        if (!Core.prefs.onboarded && Nav.stack.lastOrNull() == Route.Home && Nav.stack.size == 1) {
            Nav.reset(Route.Onboarding)
        }
        openFrom(intent)
        enableEdgeToEdge()  // стиль полос уточняет MnemoTheme по выбранной теме
        setContent {
            MnemoTheme { AppRoot() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Из уведомления-напоминания — сразу на вкладку задач. */
    private fun openFrom(intent: android.content.Intent?) {
        val open = intent?.getStringExtra("open") ?: return
        when {
            open == "tasks" -> {
                Nav.reset(Route.Home)
                codes.neuro.neurosoft.ui.HomeState.page.value = 1
            }
            open.startsWith("day:") -> {  // утренняя сводка — сразу вчерашний день
                Nav.reset(Route.Home)
                Nav.push(Route.Day(open.removePrefix("day:")))
            }
        }
        intent?.removeExtra("open")
    }

    override fun onStart() {
        super.onStart()
        Core.setForeground(true)
    }

    override fun onStop() {
        Core.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Core.syncPhotosNow()
    }
}
