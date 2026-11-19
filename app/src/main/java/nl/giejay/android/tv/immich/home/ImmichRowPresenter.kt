package nl.giejay.android.tv.immich.home

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

class ImmichRowPresenter : Presenter() {
    var editMode: Boolean = false

    /** Name of the page that is currently shown; its entry stays marked while focus is in the content. */
    var selectedName: String? = null

    override fun onCreateViewHolder(parent: ViewGroup): ImmichRowViewHolder {
        val root: View = LayoutInflater.from(parent.context)
            .inflate(R.layout.presenter_row, parent, false)

        val viewHolder = ImmichRowViewHolder(root)
        // Make the focused entry unmistakable on a TV: bold text and a slight zoom on top of the white pill.
        viewHolder.tvTitle.setOnFocusChangeListener { view, hasFocus ->
            (view as android.widget.TextView).setTypeface(null, if (hasFocus) Typeface.BOLD else Typeface.NORMAL)
            view.animate().scaleX(if (hasFocus) FOCUS_SCALE else 1f).scaleY(if (hasFocus) FOCUS_SCALE else 1f)
                .setDuration(FOCUS_ANIMATION_MS).start()
        }
        return viewHolder
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val headerItem = if (item == null) null else (item as Row).headerItem
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = headerItem?.name
        vh.view.isActivated = headerItem?.name != null && headerItem.name == selectedName

        if (editMode) {
            if (headerItem?.name == viewHolder.view.context.getString(R.string.edit)) {
                vh.tvTitle.text = viewHolder.view.context.getString(R.string.done)
            } else {
                vh.icon.visibility = View.VISIBLE
                if (PreferenceManager.itemInStringSet(headerItem?.name, HIDDEN_HOME_ITEMS)) {
                    vh.icon.setImageResource(R.drawable.closed_eye)
                } else {
                    vh.icon.setImageResource(R.drawable.visible_eye)
                }
            }
        } else {
            vh.icon.visibility = View.GONE
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = null
        vh.view.isActivated = false
    }

    private companion object {
        const val FOCUS_SCALE = 1.04f
        const val FOCUS_ANIMATION_MS = 120L
    }
}
