package az.beu.gradenotifier.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import az.beu.gradenotifier.notify.createChannels

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshSessionState()
        if (model.signedIn.value) model.checkNow()
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannels(this)
        requestNotificationPermission()
        model.start()

        setContent {
            val state by model.state.collectAsState()
            val checking by model.checking.collectAsState()
            val signedIn by model.signedIn.collectAsState()

            GradeNotifierTheme {
                MainScreen(
                    state = state,
                    checking = checking,
                    signedIn = signedIn,
                    onCheckNow = model::checkNow,
                    onSignIn = { login.launch(Intent(this, LoginActivity::class.java)) },
                    onSignOut = model::signOut,
                    onSettingsChange = model::updateSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshSessionState()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
