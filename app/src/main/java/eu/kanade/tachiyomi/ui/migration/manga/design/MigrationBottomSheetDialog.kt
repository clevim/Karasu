package eu.kanade.tachiyomi.ui.migration.manga.design

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import com.bluelinelabs.conductor.Controller
import eu.kanade.tachiyomi.R
import karasu.i18n.MR
import karasu.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.MigrationBottomSheetBinding
import eu.kanade.tachiyomi.ui.migration.MigrationFlags
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setBottomEdge
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import uy.kohesive.injekt.injectLazy

class MigrationBottomSheetDialog(
    activity: Activity,
    private val listener: StartMigrationListener,
) : E2EBottomSheetDialog<MigrationBottomSheetBinding>(activity) {

    /**
     * Preferences helper.
     */
    private val preferences by injectLazy<PreferencesHelper>()

    override fun createBinding(inflater: LayoutInflater) = MigrationBottomSheetBinding.inflate(inflater)
    init {
        // The landscape reshuffle went with the option rows it was moving around; what is left
        // is the flag grid and one switch, which fit either orientation as they are.
        setBottomEdge(binding.skipStep, activity)
        val contentView = binding.root
        (contentView.parent as View).background = ContextCompat.getDrawable(context, R.drawable.bg_sheet_gradient)
        contentView.post {
            (contentView.parent as View).background = ContextCompat.getDrawable(context, R.drawable.bg_sheet_gradient)
        }
    }

    /**
     * Called when the sheet is created. It initializes the listeners and values of the preferences.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initPreferences()

        // window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        binding.fab.setOnClickListener {
            preferences.skipPreMigration().set(binding.skipStep.isChecked)
            listener.startMigration()
            dismiss()
        }
    }

    /**
     * Init general reader preferences.
     */
    private fun initPreferences() {
        val flags = preferences.migrateFlags().get()

        val enabledFlags = MigrationFlags.getEnabledFlags(flags)
        MigrationFlags.titles.forEachIndexed { index, title ->
            val checkbox = CheckBox(context)
            checkbox.id = title.hashCode()
            checkbox.text = context.getString(title)
            checkbox.isChecked = enabledFlags[index]
            binding.gridFlagsLayout.addView(checkbox)
            checkbox.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = 8.dpToPx
                topMargin = 8.dpToPx
            }
            checkbox.setOnCheckedChangeListener { _, _ -> setFlags() }
        }

        binding.skipStep.isChecked = preferences.skipPreMigration().get()
        binding.skipStep.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                (listener as? Controller)?.activity?.toast(
                    MR.strings.to_show_again_setting_sources,
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun setFlags() {
        val enabledBoxes = binding.gridFlagsLayout.children.toList().filterIsInstance<CheckBox>().map { it.isChecked }
        val flags = MigrationFlags.getFlagsFromPositions(enabledBoxes.toTypedArray())
        preferences.migrateFlags().set(flags)
    }

}

interface StartMigrationListener {
    fun startMigration()
}
