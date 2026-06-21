package com.itsme.amkush.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.itsme.amkush.R
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs

class DeviceSpoofFragment : Fragment() {

    private lateinit var etModel: EditText
    private lateinit var etBrand: EditText
    private lateinit var etManufacturer: EditText
    private lateinit var etAndroidVersion: EditText
    private lateinit var etBuildId: EditText
    private lateinit var etSecurityPatch: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etSerial: EditText
    private lateinit var btnApply: TextView
    private lateinit var btnReset: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView

    private val androidVersions = listOf(
        "10" to "Android 10 (Q)",
        "11" to "Android 11 (R)",
        "12" to "Android 12 (S)",
        "13" to "Android 13 (T)",
        "14" to "Android 14 (U)",
        "15" to "Android 15 (V)"
    )

    private val brands = listOf(
        "Samsung", "Google", "Xiaomi", "OnePlus", "Oppo",
        "Vivo", "Realme", "Huawei", "Sony", "Motorola",
        "Nokia", "LG", "Asus", "HTC", "Lenovo"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_device_spoof, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        loadCurrentDeviceInfo()
        loadSavedSpoof()
        setupListeners()
        updateUI()
    }

    private fun initViews(view: View) {
        etModel = view.findViewById(R.id.etModel)
        etBrand = view.findViewById(R.id.etBrand)
        etManufacturer = view.findViewById(R.id.etManufacturer)
        etAndroidVersion = view.findViewById(R.id.etAndroidVersion)
        etBuildId = view.findViewById(R.id.etBuildId)
        etSecurityPatch = view.findViewById(R.id.etSecurityPatch)
        etDeviceId = view.findViewById(R.id.etDeviceId)
        etSerial = view.findViewById(R.id.etSerial)
        btnApply = view.findViewById(R.id.btnApply)
        btnReset = view.findViewById(R.id.btnReset)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvDeviceInfo = view.findViewById(R.id.tvDeviceInfo)
    }

    private fun loadCurrentDeviceInfo() {
        val info = DeviceUtils.getDeviceInfo()
        tvDeviceInfo.text = buildString {
            append("Current Device Info:\n")
            append("Model: ${info.model}\n")
            append("Brand: ${info.brand}\n")
            append("Manufacturer: ${info.manufacturer}\n")
            append("Android: ${info.androidVersion}\n")
            append("Build ID: ${info.buildId}\n")
            append("Security Patch: ${info.securityPatch}\n")
            append("Device ID: ${DeviceUtils.getFormattedDeviceId(requireContext())}")
        }
    }

    private fun loadSavedSpoof() {
        etModel.setText(SharedPrefs.getSpoofModel() ?: "")
        etBrand.setText(SharedPrefs.getSpoofBrand() ?: "")
        etManufacturer.setText(SharedPrefs.getSpoofManufacturer() ?: "")
        etAndroidVersion.setText(SharedPrefs.getSpoofAndroid() ?: "")
        etBuildId.setText(SharedPrefs.getSpoofBuildId() ?: "")
        etSecurityPatch.setText(SharedPrefs.getSpoofSecurityPatch() ?: "")
        etDeviceId.setText(SharedPrefs.getSpoofDeviceId() ?: "")
        etSerial.setText(SharedPrefs.getSpoofSerial() ?: "")
    }

    private fun setupListeners() {
        btnApply.setOnClickListener {
            applySpoof()
        }

        btnReset.setOnClickListener {
            resetSpoof()
        }

        val brandContainer = view?.findViewById<LinearLayout>(R.id.brandContainer)
        brandContainer?.let { container ->
            for (brand in brands) {
                val button = TextView(requireContext()).apply {
                    text = brand
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    setBackgroundResource(R.drawable.brand_chip_background)
                    setPadding(24, 12, 24, 12)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 8, 8)
                    }
                    setOnClickListener {
                        etBrand.setText(brand)
                        etManufacturer.setText(brand)
                    }
                }
                container.addView(button)
            }
        }

        val versionContainer = view?.findViewById<LinearLayout>(R.id.versionContainer)
        versionContainer?.let { container ->
            for ((version, label) in androidVersions) {
                val button = TextView(requireContext()).apply {
                    text = version
                    setTextColor(resources.getColor(R.color.text_secondary, null))
                    setBackgroundResource(R.drawable.brand_chip_background)
                    setPadding(24, 12, 24, 12)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 8, 8)
                    }
                    setOnClickListener {
                        etAndroidVersion.setText(version)
                    }
                }
                container.addView(button)
            }
        }
    }

    private fun applySpoof() {
        val model = etModel.text.toString().trim()
        val brand = etBrand.text.toString().trim()
        val manufacturer = etManufacturer.text.toString().trim()
        val androidVersion = etAndroidVersion.text.toString().trim()
        val buildId = etBuildId.text.toString().trim()
        val securityPatch = etSecurityPatch.text.toString().trim()
        val deviceId = etDeviceId.text.toString().trim()
        val serial = etSerial.text.toString().trim()

        if (model.isEmpty() && brand.isEmpty() && manufacturer.isEmpty() &&
            androidVersion.isEmpty() && buildId.isEmpty() && securityPatch.isEmpty() &&
            deviceId.isEmpty() && serial.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter at least one spoof value", Toast.LENGTH_SHORT).show()
            return
        }

        SharedPrefs.setSpoofModel(if (model.isNotEmpty()) model else null)
        SharedPrefs.setSpoofBrand(if (brand.isNotEmpty()) brand else null)
        SharedPrefs.setSpoofManufacturer(if (manufacturer.isNotEmpty()) manufacturer else null)
        SharedPrefs.setSpoofAndroid(if (androidVersion.isNotEmpty()) androidVersion else null)
        SharedPrefs.setSpoofBuildId(if (buildId.isNotEmpty()) buildId else null)
        SharedPrefs.setSpoofSecurityPatch(if (securityPatch.isNotEmpty()) securityPatch else null)
        SharedPrefs.setSpoofDeviceId(if (deviceId.isNotEmpty()) deviceId else null)
        SharedPrefs.setSpoofSerial(if (serial.isNotEmpty()) serial else null)
        SharedPrefs.setSpoofActive(true)

        updateUI()
        Toast.makeText(requireContext(), "Device spoof applied!", Toast.LENGTH_SHORT).show()
        Logger.d("Device spoof applied: model=$model, brand=$brand, deviceId=$deviceId")
    }

    private fun resetSpoof() {
        etModel.text.clear()
        etBrand.text.clear()
        etManufacturer.text.clear()
        etAndroidVersion.text.clear()
        etBuildId.text.clear()
        etSecurityPatch.text.clear()
        etDeviceId.text.clear()
        etSerial.text.clear()

        SharedPrefs.setSpoofModel(null)
        SharedPrefs.setSpoofBrand(null)
        SharedPrefs.setSpoofManufacturer(null)
        SharedPrefs.setSpoofAndroid(null)
        SharedPrefs.setSpoofBuildId(null)
        SharedPrefs.setSpoofSecurityPatch(null)
        SharedPrefs.setSpoofDeviceId(null)
        SharedPrefs.setSpoofSerial(null)
        SharedPrefs.setSpoofActive(false)

        updateUI()
        Toast.makeText(requireContext(), "Spoof reset to device defaults", Toast.LENGTH_SHORT).show()
        Logger.d("Device spoof reset")
    }

    private fun updateUI() {
        val isActive = SharedPrefs.isSpoofActive()
        tvStatus.text = if (isActive) "✅ Spoof active" else "⏳ Spoof inactive"
        tvStatus.setTextColor(
            if (isActive) resources.getColor(R.color.success_green, null)
            else resources.getColor(R.color.warning_yellow, null)
        )
    }
}