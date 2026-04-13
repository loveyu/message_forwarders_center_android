package info.loveyu.mfca

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity

/**
 * 隐藏的透明Activity，用于从通知栏按钮唤起输入法选择器
 * 通过隐藏 EditText 获取 IME 焦点，绕过 mCurClient 检查
 */
class InputMethodFloatingActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, InputMethodFloatingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            context.startActivity(intent)
        }
    }

    private var pickerShown = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val editText = EditText(this).apply {
            width = 1
            height = 1
        }
        setContentView(editText)
        findViewById<View>(android.R.id.content).setBackgroundColor(android.graphics.Color.TRANSPARENT)
        editText.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !pickerShown) {
            pickerShown = true
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            handler.postDelayed({
                imm.showInputMethodPicker()
            }, 300)
        } else if (hasFocus && pickerShown) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
