package com.itsme.amkush.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.core.content.ContextCompat
import com.itsme.amkush.R
import com.itsme.amkush.utils.Logger
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class StreamPreviewDialog : DialogFragment() {

    companion object {
        fun newInstance(streamUrl: String): StreamPreviewDialog {
            return StreamPreviewDialog().apply {
                arguments = Bundle().apply {
                    putString("stream_url", streamUrl)
                }
            }
        }
    }

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var btnClose: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingContainer: View
    private lateinit var errorContainer: View

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var media: Media? = null
    private var streamUrl: String? = null
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamUrl = arguments?.getString("stream_url")
        setStyle(DialogFragment.STYLE_NO_FRAME, R.style.Theme_AppCompat_NoActionBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_stream_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupDialog()
        setupVLC()
        startPlayback()
    }

    private fun initViews(view: View) {
        videoLayout = view.findViewById(R.id.videoLayout)
        btnClose = view.findViewById(R.id.btnClose)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvUrl = view.findViewById(R.id.tvUrl)
        progressBar = view.findViewById(R.id.progressBar)
        loadingContainer = view.findViewById(R.id.loadingContainer)
        errorContainer = view.findViewById(R.id.errorContainer)

        tvUrl.text = streamUrl ?: "No URL"

        btnClose.setOnClickListener {
            dismiss()
        }

        view.setOnClickListener {
            dismiss()
        }
        videoLayout.setOnClickListener { /* Prevent dismiss when clicking video */ }
    }

    private fun setupDialog() {
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isCancelable = true
    }

    private fun setupVLC() {
        val args = arrayOf(
            "--no-audio",
            "--no-stats",
            "--no-sub-autodetect-file",
            "--no-osd",
            "--network-caching=300",
            "--live-caching=300",
            "--file-caching=300",
            "--input-repeat=-1"
        )

        libVLC = LibVLC(requireContext(), args)
        mediaPlayer = MediaPlayer(libVLC)

        mediaPlayer?.setVideoLayout(
            MediaPlayer.VideoLayout.FILL,
            videoLayout.width,
            videoLayout.height
        )

        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    requireActivity().runOnUiThread {
                        isPlaying = true
                        tvStatus.text = "LIVE"
                        tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
                        progressBar.visibility = View.GONE
                        loadingContainer.visibility = View.GONE
                        errorContainer.visibility = View.GONE
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    requireActivity().runOnUiThread {
                        tvStatus.text = "Stream error"
                        tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                        progressBar.visibility = View.GONE
                        loadingContainer.visibility = View.GONE
                        errorContainer.visibility = View.VISIBLE
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    requireActivity().runOnUiThread {
                        if (isPlaying) {
                            tvStatus.text = "LIVE"
                        }
                    }
                }
                else -> {}
            }
        }

        videoLayout.setMediaPlayer(mediaPlayer)
    }

    private fun startPlayback() {
        if (streamUrl.isNullOrEmpty()) {
            tvStatus.text = "No URL"
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            progressBar.visibility = View.GONE
            loadingContainer.visibility = View.GONE
            return
        }

        try {
            media = Media(libVLC, Uri.parse(streamUrl))
            media?.addOption(":network-caching=300")
            media?.addOption(":live-caching=300")
            media?.addOption(":input-repeat=-1")

            mediaPlayer?.setMedia(media)
            mediaPlayer?.play()

            tvStatus.text = "Connecting..."
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning_yellow))
            progressBar.visibility = View.VISIBLE
            loadingContainer.visibility = View.VISIBLE
            errorContainer.visibility = View.GONE

        } catch (e: Exception) {
            Logger.e("Error starting playback", e)
            tvStatus.text = "Error: ${e.message}"
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            progressBar.visibility = View.GONE
            loadingContainer.visibility = View.GONE
            errorContainer.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) {
            mediaPlayer?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        media?.release()
        libVLC?.release()
    }
}