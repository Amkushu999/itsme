package com.itsme.amkush.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itsme.amkush.R
import com.itsme.amkush.network.ApiClient
import com.itsme.amkush.network.models.ValidateRequest
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaymentScreen : AppCompatActivity() {

    private lateinit var etActivationKey: EditText
    private lateinit var btnActivate: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvTargetApp: TextView
    private lateinit var tvTargetPackage: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCopyDeviceId: TextView
    private lateinit var btnBot: TextView

    private var targetPackage: String? = null
    private var targetAppName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        // Get intent data
        targetPackage = intent.getStringExtra("target_package")
        targetAppName = intent.getStringExtra("target_app_name")

        initViews()
        setupUI()
        setupListeners()
        loadDeviceId()
        checkForTrialKey()
    }

    private fun initViews() {
        etActivationKey = findViewById(R.id.etActivationKey)
        btnActivate = findViewById(R.id.btnActivate)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvTargetApp = findViewById(R.id.tvTargetApp)
        tvTargetPackage = findViewById(R.id.tvTargetPackage)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnCopyDeviceId = findViewById(R.id.btnCopyDeviceId)
        btnBot = findViewById(R.id.btnBot)
    }

    private fun setupUI() {
        // Set target app info
        if (!targetAppName.isNullOrEmpty()) {
            tvTargetApp.text = targetAppName
        } else {
            tvTargetApp.text = "No target selected"
        }

        if (!targetPackage.isNullOrEmpty()) {
            tvTargetPackage.text = targetPackage
        } else {
            tvTargetPackage.visibility = View.GONE
        }

        // Bot button
        btnBot.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/facegateofficialbot"))
            startActivity(intent)
        }

        // Copy device ID
        btnCopyDeviceId.setOnClickListener {
            val deviceId = tvDeviceId.text.toString()
            if (deviceId.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Device ID", deviceId)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Device ID copied!", Toast.LENGTH_SHORT).show()
            }
        }

        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        etActivationKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Clear status when typing
                tvStatus.visibility = View.GONE
            }
        })

        btnActivate.setOnClickListener {
            val key = etActivationKey.text.toString().trim()
            if (key.isEmpty()) {
                tvStatus.text = "Please enter an activation key"
                tvStatus.visibility = View.VISIBLE
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                return@setOnClickListener
            }

            // Check if it's the trial key
            if (key.uppercase() == "NOWORNEVER") {
                activateTrial()
            } else {
                activatePaid(key)
            }
        }
    }

    private fun loadDeviceId() {
        val deviceId = DeviceUtils.getFormattedDeviceId(this)
        tvDeviceId.text = deviceId
        SharedPrefs.setDeviceId(deviceId)
    }

    private fun checkForTrialKey() {
        // Check if user has an active trial
        val isTrial = SharedPrefs.isTrial()
        val expiry = SharedPrefs.getTrialExpiry()

        if (isTrial && expiry > System.currentTimeMillis()) {
            // Active trial, skip to dashboard
            proceedToDashboard()
        }
    }

    private fun activateTrial() {
        progressBar.visibility = View.VISIBLE
        btnActivate.isEnabled = false
        tvStatus.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiService = ApiClient.getApiService()
                val deviceId = DeviceUtils.getDeviceId(this@PaymentScreen)
                val wifiIp = DeviceUtils.getWifiIpAddress(this@PaymentScreen)

                val request = ValidateRequest(
                    key = "NOWORNEVER",
                    deviceId = deviceId,
                    wifiIp = wifiIp
                )

                val response = apiService.validateKey(request).execute()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnActivate.isEnabled = true

                    if (response.isSuccessful && response.body()?.success == true) {
                        val body = response.body()!!
                        // Save token
                        body.token?.let { token ->
                            SharedPrefs.setActivationToken(token)
                            SharedPrefs.setTrial(true)
                            SharedPrefs.setPaid(false)
                            // Trial expiry is handled by server, but we store it
                            body.expiresAt?.let {
                                // Parse and store expiry
                            }
                            showActivationSuccess()
                        }
                    } else {
                        val errorMsg = response.body()?.message ?: "Trial activation failed"
                        showError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnActivate.isEnabled = true
                    Logger.e("Error activating trial", e)
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    private fun activatePaid(key: String) {
        progressBar.visibility = View.VISIBLE
        btnActivate.isEnabled = false
        tvStatus.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiService = ApiClient.getApiService()
                val deviceId = DeviceUtils.getDeviceId(this@PaymentScreen)
                val wifiIp = DeviceUtils.getWifiIpAddress(this@PaymentScreen)

                val request = ValidateRequest(
                    key = key,
                    deviceId = deviceId,
                    wifiIp = wifiIp
                )

                val response = apiService.validateKey(request).execute()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnActivate.isEnabled = true

                    if (response.isSuccessful && response.body()?.success == true) {
                        val body = response.body()!!
                        // Save token
                        body.token?.let { token ->
                            SharedPrefs.setActivationToken(token)
                            SharedPrefs.setPaid(true)
                            SharedPrefs.setTrial(false)
                            showActivationSuccess()
                        }
                    } else {
                        val errorMsg = response.body()?.message ?: "Invalid activation key"
                        showError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnActivate.isEnabled = true
                    Logger.e("Error activating key", e)
                    showError("Network error: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        tvStatus.text = message
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red))
    }

    private fun showActivationSuccess() {
        // Show success message and navigate to dashboard
        Toast.makeText(this, "Activation successful!", Toast.LENGTH_LONG).show()
        proceedToDashboard()
    }

    private fun proceedToDashboard() {
        val intent = Intent(this, TabsScreen::class.java).apply {
            putExtra("target_package", targetPackage)
            putExtra("target_app_name", targetAppName)
            putExtra("from_payment", true)
        }
        startActivity(intent)
        finish()
    }
}