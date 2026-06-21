package com.itsme.amkush.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.itsme.amkush.R
import com.itsme.amkush.services.InjectionService
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs

class StreamSetupFragment : Fragment() {

    private lateinit var etStreamUrl: EditText
    private lateinit var btnSave: TextView
    private lateinit var btnStartInjection: TextView
    private lateinit var btnStopInjection: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTargetApp: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var protocolSpinner: Spinner

    private var targetPackage: String? = null
    private var targetAppName: String? = null
    private var isInjectionRunning = false
    private var savedUrl: String? = null

    private val protocols = arrayOf(
        "HLS (.m3u8)",
        "RTSP",
        "RTMP",
        "HTTP",
        "HTTPS",
        "UDP",
        "RTP",
        "SRT",
        "MMS",
        "FTP"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_stream_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupProtocolSpinner()
        loadSavedStream()
        updateUI()
        setupListeners()
    }

    private fun initViews(view: View) {
        etStreamUrl = view.findViewById(R.id.etStreamUrl)
        btnSave = view.findViewById(R.id.btnSave)
        btnStartInjection = view.findViewById(R.id.btnStartInjection)
        btnStopInjection = view.findViewById(R.id.btnStopInjection)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvTargetApp = view.findViewById(R.id.tvTargetApp)
        progressBar = view.findViewById(R.id.progressBar)
        protocolSpinner = view.findViewById(R.id.protocolSpinner)
    }

    private fun setupProtocolSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            protocols
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        protocolSpinner.adapter = adapter
    }

    private fun loadSavedStream() {
        savedUrl = SharedPrefs.getStreamUrl()
        if (!savedUrl.isNullOrEmpty()) {
            etStreamUrl.setText(savedUrl)
            val protocol = detectProtocol(savedUrl!!)
            val position = protocols.indexOfFirst { it.startsWith(protocol, ignoreCase = true) }
            if (position >= 0) {
                protocolSpinner.setSelection(position)
            }
        }
    }

    private fun detectProtocol(url: String): String {
        return when {
            url.startsWith("rtsp://") -> "RTSP"
            url.startsWith("rtmp://") -> "RTMP"
            url.startsWith("https://") -> "HTTPS"
            url.startsWith("http://") -> "HTTP"
            url.startsWith("udp://") -> "UDP"
            url.startsWith("rtp://") -> "RTP"
            url.startsWith("srt://") -> "SRT"
            url.startsWith("mms://") -> "MMS"
            url.startsWith("ftp://") -> "FTP"
            url.endsWith(".m3u8") -> "HLS (.m3u8)"
            else -> "HTTP"
        }
    }

    private fun updateUI() {
        val hasUrl = !etStreamUrl.text.toString().trim().isEmpty()
        val target = targetAppName ?: SharedPrefs.getTargetAppName()

        if (!target.isNullOrEmpty()) {
            tvTargetApp.text = getString(R.string.target_format, target)
            tvTargetApp.visibility = View.VISIBLE
        } else {
            tvTargetApp.visibility = View.GONE
        }

        btnStartInjection.isEnabled = hasUrl
        btnStartInjection.alpha = if (hasUrl) 1.0f else 0.5f

        if (isInjectionRunning) {
            btnStartInjection.visibility = View.GONE
            btnStopInjection.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.injection_active)
            tvStatus.setTextColor(resources.getColor(R.color.success_green, null))
        } else {
            btnStartInjection.visibility = View.VISIBLE
            btnStopInjection.visibility = View.GONE
            tvStatus.text = getString(R.string.status_ready)
            tvStatus.setTextColor(resources.getColor(R.color.warning_yellow, null))
        }

        if (!savedUrl.isNullOrEmpty()) {
            btnSave.text = getString(R.string.stream_saved)
        } else {
            btnSave.text = getString(R.string.save_apply)
        }
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            val url = etStreamUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), R.string.enter_stream_url_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValidUrl(url)) {
                Toast.makeText(requireContext(), R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            savedUrl = url
            SharedPrefs.setStreamUrl(url)
            SharedPrefs.setStreamType(protocols[protocolSpinner.selectedItemPosition])
            updateUI()

            Toast.makeText(requireContext(), R.string.stream_saved, Toast.LENGTH_SHORT).show()
        }

        btnStartInjection.setOnClickListener {
            val url = etStreamUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), R.string.configure_stream_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startInjection(url)
        }

        btnStopInjection.setOnClickListener {
            stopInjection()
        }

        etStreamUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val url = s.toString().trim()
                btnStartInjection.isEnabled = url.isNotEmpty()
                btnStartInjection.alpha = if (url.isNotEmpty()) 1.0f else 0.5f
            }
        })
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") ||
                url.startsWith("https://") ||
                url.startsWith("rtsp://") ||
                url.startsWith("rtmp://") ||
                url.startsWith("udp://") ||
                url.startsWith("rtp://") ||
                url.startsWith("srt://") ||
                url.startsWith("mms://") ||
                url.startsWith("ftp://") ||
                url.endsWith(".m3u8")
    }

    private fun startInjection(url: String) {
        val targetPkg = targetPackage ?: SharedPrefs.getTargetPackage()
        if (targetPkg.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.no_target_selected, Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        isInjectionRunning = true
        updateUI()

        val intent = android.content.Intent(requireContext(), InjectionService::class.java).apply {
            putExtra("target_package", targetPkg)
            putExtra("stream_url", url)
        }
        requireContext().startService(intent)

        val appName = targetAppName ?: SharedPrefs.getTargetAppName()
        Toast.makeText(requireContext(), getString(R.string.injection_started, appName), Toast.LENGTH_SHORT).show()
        progressBar.visibility = View.GONE
    }

    private fun stopInjection() {
        progressBar.visibility = View.VISIBLE
        isInjectionRunning = false
        updateUI()

        InjectionService.stop(requireContext())

        Toast.makeText(requireContext(), R.string.injection_stopped, Toast.LENGTH_SHORT).show()
        progressBar.visibility = View.GONE
    }

    fun setTargetInfo(packageName: String?, appName: String?) {
        targetPackage = packageName
        targetAppName = appName
    }

    override fun onResume() {
        super.onResume()
        if (InjectionService.isRunning) {
            isInjectionRunning = true
            updateUI()
        }
    }
}