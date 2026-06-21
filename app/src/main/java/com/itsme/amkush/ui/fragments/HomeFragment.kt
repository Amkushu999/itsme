package com.itsme.amkush.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.itsme.amkush.R
import com.itsme.amkush.ui.StreamPreviewDialog
import com.itsme.amkush.utils.SharedPrefs

class HomeFragment : Fragment() {

    private var targetPackage: String? = null
    private var targetAppName: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTargetApp = view.findViewById<TextView>(R.id.tvTargetApp)
        val tvTargetPackage = view.findViewById<TextView>(R.id.tvTargetPackage)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvStreamStatus = view.findViewById<TextView>(R.id.tvStreamStatus)
        val btnViewStream = view.findViewById<View>(R.id.btnViewStream)

        val appName = targetAppName ?: SharedPrefs.getTargetAppName()
        val pkg = targetPackage ?: SharedPrefs.getTargetPackage()

        if (!appName.isNullOrEmpty()) {
            tvTargetApp.text = appName
            tvTargetPackage.text = pkg ?: "Unknown package"
            tvStatus.text = "✅ Active interception"
            tvStatus.setTextColor(resources.getColor(R.color.success_green, null))
        } else {
            tvTargetApp.text = "No target selected"
            tvTargetPackage.text = "Select a target app to begin"
            tvStatus.text = "⏳ Waiting for target"
            tvStatus.setTextColor(resources.getColor(R.color.warning_yellow, null))
        }

        val streamUrl = SharedPrefs.getStreamUrl()
        if (!streamUrl.isNullOrEmpty()) {
            tvStreamStatus.text = "Stream configured — ready to watch"
            btnViewStream.visibility = View.VISIBLE
        } else {
            tvStreamStatus.text = "Go to Stream tab to configure a URL"
            btnViewStream.visibility = View.GONE
        }

        btnViewStream.setOnClickListener {
            showStreamPreview()
        }
    }

    private fun showStreamPreview() {
        val streamUrl = SharedPrefs.getStreamUrl()
        if (!streamUrl.isNullOrEmpty()) {
            val dialog = StreamPreviewDialog.newInstance(streamUrl)
            dialog.show(childFragmentManager, "stream_preview")
        }
    }

    fun setTargetInfo(packageName: String?, appName: String?) {
        targetPackage = packageName
        targetAppName = appName
    }
}