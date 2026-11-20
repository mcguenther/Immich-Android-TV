package nl.giejay.mediaslider.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.datasource.DefaultHttpDataSource
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel

open class MediaSliderFragment : Fragment() {
    protected val sliderViewModel: MediaSliderViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return MediaSliderView(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.requestFocus()
    }

    override fun onPause() {
        // Releases the player and pauses prefetching; onResume brings prefetching back.
        view.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }

    override fun onDestroyView() {
        view.onDestroy()
        super.onDestroyView()
    }

    override fun getView(): MediaSliderView {
        return super.getView() as MediaSliderView
    }

    fun loadMediaSliderView(config: MediaSliderConfiguration) {
        view.loadMediaSliderView(config)
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        view.setDefaultExoFactory(defaultExoFactory)
    }
}
