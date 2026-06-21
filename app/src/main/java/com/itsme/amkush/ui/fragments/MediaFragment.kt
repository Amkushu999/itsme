package com.itsme.amkush.ui.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.itsme.amkush.R
import com.itsme.amkush.services.InjectionService
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs

class MediaFragment : Fragment() {

    companion object {
        private const val PICK_MEDIA_REQUEST = 1001
        private const val PERMISSION_REQUEST = 1002
    }

    private lateinit var videoView: VideoView
    private lateinit var ivPreview: ImageView
    private lateinit var tvFileName: TextView
    private lateinit var btnUpload: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnStartInjection: TextView
    private lateinit var btnStopInjection: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTargetApp: TextView

    private var selectedUri: Uri? = null
    private var selectedFileName: String? = null
    private var isInjectionRunning = false
    private var targetPackage: String? = null
    private var targetAppName: String? = null
    private var isPlaying = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_media, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        checkPermissions()
        loadSavedMedia()
        updateUI()
        setupListeners()
    }

    private fun initViews(view: View) {
        videoView = view.findViewById(R.id.videoView)
        ivPreview = view.findViewById(R.id.ivPreview)
        tvFileName = view.findViewById(R.id.tvFileName)
        btnUpload = view.findViewById(R.id.btnUpload)
        btnClear = view.findViewById(R.id.btnClear)
        btnStartInjection = view.findViewById(R.id.btnStartInjection)
        btnStopInjection = view.findViewById(R.id.btnStopInjection)
        tvStatus = view.findViewById(R.id.tvStatus)
        progressBar = view.findViewById(R.id.progressBar)
        tvTargetApp = view.findViewById(R.id.tvTargetApp)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissions.toTypedArray(),
                PERMISSION_REQUEST
            )
        }
    }

    private fun loadSavedMedia() {
        val savedUri = SharedPrefs.getLastUsedUrl()
        if (!savedUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(savedUri)
                selectedUri = uri
                selectedFileName = getFileName(uri)
                displayMedia(uri)
                updateUI()
            } catch (e: Exception) {
                Logger.e("Error loading saved media", e)
            }
        }
    }

    private fun displayMedia(uri: Uri) {
        try {
            val mimeType = requireContext().contentResolver.getType(uri)

            if (mimeType?.startsWith("video/") == true) {
                videoView.visibility = View.VISIBLE
                ivPreview.visibility = View.GONE

                videoView.setVideoURI(uri)
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    videoView.start()
                    isPlaying = true
                }
                videoView.setOnErrorListener { _, what, extra ->
                    Logger.e("VideoView error: what=$what, extra=$extra")
                    false
                }

                val mediaController = MediaController(requireContext())
                mediaController.setAnchorView(videoView)
                videoView.setMediaController(mediaController)

                tvFileName.text = "🎬 $selectedFileName"

            } else {
                videoView.visibility = View.GONE
                ivPreview.visibility = View.VISIBLE

                Glide.with(this)
                    .load(uri)
                    .placeholder(R.drawable.ic_media)
                    .error(R.drawable.ic_media)
                    .into(ivPreview)

                tvFileName.text = selectedFileName ?: "Media selected"
            }
        } catch (e: Exception) {
            Logger.e("Error displaying media", e)
        }
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "Unknown"
        val cursor: Cursor? = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = it.getString(nameIndex) ?: "Unknown"
                }
            }
        }
        return fileName
    }

    private fun updateUI() {
        val hasMedia = selectedUri != null
        val target = targetAppName ?: SharedPrefs.getTargetAppName()

        if (!target.isNullOrEmpty()) {
            tvTargetApp.text = getString(R.string.target_format, target)
            tvTargetApp.visibility = View.VISIBLE
        } else {
            tvTargetApp.visibility = View.GONE
        }

        btnStartInjection.isEnabled = hasMedia
        btnStartInjection.alpha = if (hasMedia) 1.0f else 0.5f

        if (isInjectionRunning) {
            btnStartInjection.visibility = View.GONE
            btnStopInjection.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.injection_active)
            tvStatus.setTextColor(resources.getColor(R.color.success_green, null))
        } else {
            btnStartInjection.visibility = View.VISIBLE
            btnStopInjection.visibility = View.GONE
            tvStatus.text = if (hasMedia) getString(R.string.status_ready_inject) else getString(R.string.status_upload_first)
            tvStatus.setTextColor(resources.getColor(R.color.warning_yellow, null))
        }

        if (hasMedia) {
            tvFileName.visibility = View.VISIBLE
            btnClear.visibility = View.VISIBLE
        } else {
            tvFileName.visibility = View.GONE
            btnClear.visibility = View.GONE
            videoView.visibility = View.GONE
            ivPreview.visibility = View.VISIBLE
            ivPreview.setImageResource(R.drawable.ic_media)
        }
    }

    private fun setupListeners() {
        btnUpload.setOnClickListener {
            checkPermissionsAndPick()
        }

        btnClear.setOnClickListener {
            clearMedia()
        }

        btnStartInjection.setOnClickListener {
            if (selectedUri == null) {
                Toast.makeText(requireContext(), R.string.upload_media_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startInjection()
        }

        btnStopInjection.setOnClickListener {
            stopInjection()
        }
    }

    private fun checkPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImagePerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES)
            val hasVideoPerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO)
            if (hasImagePerm != PackageManager.PERMISSION_GRANTED &&
                hasVideoPerm != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ),
                    PERMISSION_REQUEST
                )
                return
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 - MediaStore
        } else {
            val hasStoragePerm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
            if (hasStoragePerm != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST
                )
                return
            }
        }

        pickMedia()
    }

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        }
        startActivityForResult(intent, PICK_MEDIA_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_MEDIA_REQUEST && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                selectedUri = uri
                selectedFileName = getFileName(uri)
                displayMedia(uri)
                SharedPrefs.setLastUsedUrl(uri.toString())
                updateUI()
                Toast.makeText(requireContext(), getString(R.string.media_selected_format, selectedFileName), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearMedia() {
        selectedUri = null
        selectedFileName = null
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        ivPreview.visibility = View.VISIBLE
        ivPreview.setImageResource(R.drawable.ic_media)
        SharedPrefs.setLastUsedUrl(null)
        updateUI()
        Toast.makeText(requireContext(), R.string.media_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun startInjection() {
        val targetPkg = targetPackage ?: SharedPrefs.getTargetPackage()
        if (targetPkg.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.no_target_selected, Toast.LENGTH_LONG).show()
            return
        }

        if (selectedUri == null) {
            Toast.makeText(requireContext(), R.string.no_media_selected_error, Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        isInjectionRunning = true
        updateUI()

        val intent = Intent(requireContext(), InjectionService::class.java).apply {
            putExtra("target_package", targetPkg)
            putExtra("media_uri", selectedUri.toString())
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

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) {
            videoView.pause()
            isPlaying = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying && selectedUri != null) {
            videoView.start()
        }
        if (InjectionService.isRunning) {
            isInjectionRunning = true
            updateUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView.stopPlayback()
    }
}