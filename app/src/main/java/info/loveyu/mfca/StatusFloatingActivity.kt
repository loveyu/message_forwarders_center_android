package info.loveyu.mfca

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import info.loveyu.mfca.service.ForwardService

class StatusFloatingActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, StatusFloatingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            context.startActivity(intent)
        }
    }

    private val density: Float by lazy { resources.displayMetrics.density }
    private var cardView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bgColor = Color.parseColor("#B3000000")
        val cardBg = Color.parseColor("#FAFAFA")
        val textPrimary = Color.parseColor("#1A1A1A")
        val textSecondary = Color.parseColor("#757575")
        val dividerColor = Color.parseColor("#1F000000")
        val accentOn = Color.parseColor("#1B6D35")
        val accentOff = Color.parseColor("#C62828")
        val rippleBg = Color.parseColor("#0F000000")

        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(bgColor) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        cardView = card

        // Header
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            addView(TextView(this@StatusFloatingActivity).apply {
                text = "服务状态"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@StatusFloatingActivity).apply {
                text = buildSummary()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textSecondary)
                setPadding(0, dp(4), 0, 0)
            })
        })

        card.addView(View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        // Rows data
        val rows = listOf(
            Triple("消息接收", "接收来自输入源的消息") { ForwardService.isReceivingEnabled },
            Triple("消息转发", "将消息发送至输出目标") { ForwardService.isForwardingEnabled },
            Triple("唤醒锁", "保持 CPU 唤醒，增加耗电") { ForwardService.isWakeLockEnabled },
        )
        val actions = listOf(
            ForwardService.ACTION_TOGGLE_RECEIVE,
            ForwardService.ACTION_TOGGLE_FORWARD,
            ForwardService.ACTION_TOGGLE_WAKELOCK,
        )

        rows.forEachIndexed { index, (label, desc, stateProvider) ->
            card.addView(createSwitchRow(
                label, desc, accentOn, accentOff, rippleBg, stateProvider, actions[index]
            ))
            if (index < rows.size - 1) {
                card.addView(View(this).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = dp(20) }
                })
            }
        }

        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.8).toInt()

        root.addView(card, FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val r = android.graphics.Rect()
                card.getGlobalVisibleRect(r)
                if (!r.contains(event.rawX.toInt(), event.rawY.toInt())) finish()
            }
            true
        }

        setContentView(root)
    }

    private fun buildSummary(): String {
        if (!ForwardService.isRunning) return "服务未运行"
        val parts = mutableListOf<String>()
        if (!ForwardService.isReceivingEnabled) parts.add("接收暂停")
        if (!ForwardService.isForwardingEnabled) parts.add("转发暂停")
        if (ForwardService.isWakeLockEnabled) parts.add("唤醒锁开启")
        return if (parts.isEmpty()) "一切正常" else parts.joinToString(" · ")
    }

    private fun createSwitchRow(
        label: String,
        description: String,
        accentOn: Int,
        accentOff: Int,
        rippleBg: Int,
        stateProvider: () -> Boolean,
        action: String
    ): LinearLayout {
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val textPrimary = Color.parseColor("#1A1A1A")
        val textSecondary = Color.parseColor("#757575")

        // Container: vertical, label on top, full-width switch at bottom
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }

        // Label row: dot + label
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (stateProvider()) accentOn else accentOff)
            }
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                marginEnd = dp(8)
            }
        }

        labelRow.addView(dot)
        labelRow.addView(TextView(this).apply {
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        container.addView(labelRow)

        // Description
        container.addView(TextView(this).apply {
            text = description
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                marginStart = dp(14)
            }
        })

        // Full-width pill toggle button
        val toggleBtn = TextView(this).apply {
            val isOn = stateProvider()
            text = if (isOn) "已开启" else "已关闭"
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (isOn) Color.WHITE else Color.parseColor("#757575"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (isOn) accentOn else Color.parseColor("#E0E0E0"))
            }
            isClickable = true
            isFocusable = true
            setPadding(dp(0), dp(10), dp(0), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }

            setOnClickListener {
                val oldState = stateProvider()
                toggleAction(action)
                val newState = !oldState
                text = if (newState) "已开启" else "已关闭"
                setTextColor(if (newState) Color.WHITE else Color.parseColor("#757575"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(if (newState) accentOn else Color.parseColor("#E0E0E0"))
                }
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (newState) accentOn else accentOff)
                }
                updateSummary()
            }
        }

        container.addView(toggleBtn)
        return container
    }

    private fun toggleAction(action: String) {
        startService(Intent(this, ForwardService::class.java).apply { this.action = action })
    }

    private fun updateSummary() {
        val card = cardView as? LinearLayout ?: return
        val header = card.getChildAt(0) as? LinearLayout ?: return
        if (header.childCount >= 2) {
            (header.getChildAt(1) as? TextView)?.text = buildSummary()
        }
    }

    override fun onPause() {
        super.onPause()
        finish()
    }
}
