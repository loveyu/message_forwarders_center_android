package info.loveyu.mfca.test.output_plugin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import info.loveyu.mfca.ui.theme.MfcaTheme

class OutputPluginTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val engine = OutputPluginTestEngine(context = this)
        setContent {
            MfcaTheme {
                OutputPluginTestScreen(onBack = { finish() }, engine = engine)
            }
        }
    }
}
