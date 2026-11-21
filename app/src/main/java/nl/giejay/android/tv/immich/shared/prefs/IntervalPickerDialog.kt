package nl.giejay.android.tv.immich.shared.prefs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import nl.giejay.android.tv.immich.R

/**
 * Remote-friendly picker for the slideshow/screensaver interval: a minutes column and a seconds column.
 *
 * Left/right move the focus between the columns (regular focus search), up/down change the focused column (up = more),
 * OK confirms and Back cancels. The allowed values are the ones from [IntervalOptions].
 */
class IntervalPickerDialog(
    private val context: Context,
    private val title: CharSequence?,
    private val subtitle: CharSequence?,
    initialSeconds: Int,
    private val onPicked: (Int) -> Unit
) {
    private var value = IntervalOptions.snap(initialSeconds)
    private val minutesColumn = PickerColumn(context, context.getString(R.string.interval_picker_minutes))
    private val secondsColumn = PickerColumn(context, context.getString(R.string.interval_picker_seconds))

    fun show() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildContent())
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                d.dismiss()
                onPicked(value)
                true
            } else false
        }
        secondsColumn.requestFocus()
        dialog.show()
    }

    private fun buildContent(): LinearLayout {
        minutesColumn.onStep = { delta -> value = IntervalOptions.stepMinutes(value, delta); refresh() }
        secondsColumn.onStep = { delta -> value = IntervalOptions.stepSeconds(value, delta); refresh() }

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minutesColumn, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text(":", 40f, secondary = true).apply { setPadding(dp(14), 0, dp(14), 0) })
            addView(secondsColumn, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(32), dp(40), dp(24))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.gray_dark))
                cornerRadius = dp(12).toFloat()
            }
            if (!title.isNullOrBlank()) addView(text(title.toString(), 24f))
            if (!subtitle.isNullOrBlank()) addView(text(subtitle.toString(), 14f, secondary = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                maxWidth = dp(420)
            })
            addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
                bottomMargin = dp(20)
            })
            addView(text(context.getString(R.string.interval_picker_hint), 13f, secondary = true))
        }
        refresh()
        return root
    }

    private fun refresh() {
        val minutes = value / 60
        minutesColumn.bind(
            above = if (minutes < IntervalOptions.maxMinutes) (minutes + 1).toString() else "",
            current = minutes.toString(),
            below = if (minutes > 0) (minutes - 1).toString() else ""
        )
        val i = IntervalOptions.seconds.indexOf(value)
        fun secondsPart(index: Int) = IntervalOptions.seconds.getOrNull(index)?.let { "%02d".format(it % 60) } ?: ""
        secondsColumn.bind(above = secondsPart(i + 1), current = secondsPart(i), below = secondsPart(i - 1))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun text(text: String, sp: Float, secondary: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ContextCompat.getColor(context, if (secondary) R.color.default_text_color_secondary else R.color.default_text_color))
    }

    /** One focusable column that shows the current value with its neighbours; up/down keys call [onStep] with +1/-1. */
    private class PickerColumn(context: Context, unit: String) : LinearLayout(context) {
        var onStep: ((Int) -> Unit)? = null
        private val above = label(context, 22f, secondary = true)
        private val current = label(context, 44f, secondary = false)
        private val below = label(context, 22f, secondary = true)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            val density = context.resources.displayMetrics.density
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), frame(context, ContextCompat.getColor(context, R.color.screen_white)))
                addState(intArrayOf(), frame(context, Color.TRANSPARENT))
            }
            addView(label(context, 14f, secondary = true).apply { text = "▲" })
            addView(above)
            addView(current)
            addView(below)
            addView(label(context, 14f, secondary = true).apply { text = "▼" })
            addView(label(context, 13f, secondary = true).apply { text = unit })
        }

        fun bind(above: String, current: String, below: String) {
            this.above.text = above
            this.current.text = current
            this.below.text = below
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { onStep?.invoke(1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { onStep?.invoke(-1); true }
            else -> super.onKeyDown(keyCode, event)
        }

        private companion object {
            fun label(context: Context, sp: Float, secondary: Boolean) = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(ContextCompat.getColor(context, if (secondary) R.color.default_text_color_secondary else R.color.default_text_color))
                minHeight = (sp * 1.5f * context.resources.displayMetrics.density).toInt()
            }

            fun frame(context: Context, strokeColor: Int) = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.default_card_background_color))
                setStroke((3 * context.resources.displayMetrics.density).toInt(), strokeColor)
                cornerRadius = 8 * context.resources.displayMetrics.density
            }
        }
    }
}
