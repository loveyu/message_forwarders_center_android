package info.loveyu.mfca.test.input_plugin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import info.loveyu.mfca.ui.theme.MfcaTheme

class InputPluginTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = InputPluginTestEngine(context = this)
        setContent {
            MfcaTheme {
                InputPluginTestScreen(onBack = { finish() }, engine = engine)
            }
        }
    }
}
