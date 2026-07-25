package eu.kanade.tachiyomi.ui.base.controller

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.viewbinding.ViewBinding
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.backgroundColor
import eu.kanade.tachiyomi.util.view.isControllerVisible

abstract class BaseLegacyController<VB : ViewBinding>(bundle: Bundle? = null) :
    BaseController(bundle) {

    override val shouldHideLegacyAppBar = false

    // Backing field is nulled in onDestroyView so a backstacked controller doesn't retain views
    // (and through them, the destroyed activity) across activity recreation.
    private var _binding: VB? = null
    val binding: VB get() = _binding!!
    val isBindingInitialized get() = _binding != null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup, savedViewState: Bundle?): View {
        setAppBarVisibility()
        val binding = createBinding(inflater)
        _binding = binding
        binding.root.backgroundColor = binding.root.context.getResourceColor(R.attr.background)
        return binding.root
    }

    override fun onDestroyView(view: View) {
        _binding = null
        super.onDestroyView(view)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        if (type.isEnter && isControllerVisible) {
            setTitle()
        }
        super.onChangeStarted(handler, type)
    }

    abstract fun createBinding(inflater: LayoutInflater): VB

    open fun getTitle(): String? {
        return null
    }

    open fun getSearchTitle(): String? {
        return null
    }

    open fun getBigIcon(): Drawable? {
        return null
    }

    fun setTitle() {
        var parentController = parentController
        while (parentController != null) {
            if (parentController is BaseLegacyController<*> && parentController.getTitle() != null) {
                return
            }
            parentController = parentController.parentController
        }

        if (isControllerVisible) {
            (activity as? AppCompatActivity)?.title = getTitle()
            (activity as? MainActivity)?.searchTitle = getSearchTitle()
            val icon = getBigIcon()
            activityBinding?.bigIconLayout?.isVisible = icon != null
            if (icon != null) {
                activityBinding?.bigIcon?.setImageDrawable(getBigIcon())
            } else {
                activityBinding?.bigIcon?.setImageDrawable(getBigIcon())
            }
        }
    }
}
