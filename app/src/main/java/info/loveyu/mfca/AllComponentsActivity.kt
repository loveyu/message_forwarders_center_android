package info.loveyu.mfca

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import info.loveyu.mfca.ui.AllComponentsScreen
import info.loveyu.mfca.ui.theme.MfcaTheme

class AllComponentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MfcaTheme {
                AllComponentsScreen(onBack = ::finish)
            }
        }
    }
}
