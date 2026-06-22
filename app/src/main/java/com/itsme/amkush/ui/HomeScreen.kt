package com.itsme.amkush.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itsme.amkush.R
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.network.ApiClient
import com.itsme.amkush.network.models.TokenRequest
import com.itsme.amkush.utils.DeviceUtils
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeScreen : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var btnLockTarget: TextView
    private lateinit var tvSelectedApp: TextView
    private lateinit var tvSelectedPackage: TextView
    private lateinit var ivSelectedIcon: ImageView
    private lateinit var selectedAppContainer: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCopyDeviceId: TextView

    private val appList = mutableListOf<AppInfo>()
    private val filteredApps = mutableListOf<AppInfo>()
    private var selectedApp: AppInfo? = null
    private var adapter: AppListAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Initialize SharedPrefs
        SharedPrefs.init(this)

        initViews()
        setupRecyclerView()
        setupSearch()
        setupCopyButton()
        loadInstalledApps()
        loadSavedTarget()
        loadDeviceId()

        btnLockTarget.setOnClickListener {
            if (selectedApp == null) {
                Toast.makeText(this, "Please select a target app first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lockTarget()
        }

        // Click on selected app to change
        selectedAppContainer.setOnClickListener {
            showAppSelection()
        }
    }

    private fun initViews() {
        rvApps = findViewById(R.id.rvApps)
        searchInput = findViewById(R.id.searchInput)
        btnLockTarget = findViewById(R.id.btnLockTarget)
        tvSelectedApp = findViewById(R.id.tvSelectedApp)
        tvSelectedPackage = findViewById(R.id.tvSelectedPackage)
        ivSelectedIcon = findViewById(R.id.ivSelectedIcon)
        selectedAppContainer = findViewById(R.id.selectedAppContainer)
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        progressBar = findViewById(R.id.progressBar)
        btnCopyDeviceId = findViewById(R.id.btnCopyDeviceId)
    }

    private fun setupCopyButton() {
        btnCopyDeviceId.setOnClickListener {
            val deviceId = tvDeviceId.text.toString()
            if (deviceId != "---" && deviceId.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Device ID", deviceId)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Device ID copied to clipboard!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No Device ID to copy", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(filteredApps) { app ->
            selectApp(app)
        }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s.toString())
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Perform search
                true
            } else {
                false
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun loadInstalledApps() {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        searchInput.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val packageManager = packageManager
                val packages = packageManager.getInstalledApplications(0)
                val apps = mutableListOf<AppInfo>()

                for (pkg in packages) {
                    val appName = packageManager.getApplicationLabel(pkg).toString()
                    val packageName = pkg.packageName
                    val isSystem = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    // ✅ Show ALL apps - no filtering
                    val icon = packageManager.getApplicationIcon(pkg)
                    apps.add(AppInfo(packageName, appName, icon, isSystem))
                }

                // Sort by name
                apps.sortBy { it.appName.lowercase() }

                withContext(Dispatchers.Main) {
                    appList.clear()
                    appList.addAll(apps)
                    filteredApps.clear()
                    filteredApps.addAll(apps)
                    adapter?.notifyDataSetChanged()
                    progressBar.visibility = View.GONE

                    // ✅ Show the list and search bar
                    rvApps.visibility = View.VISIBLE
                    searchInput.visibility = View.VISIBLE

                    Logger.d("Loaded ${apps.size} apps")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@HomeScreen, "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
                    Logger.e("Error loading apps", e)
                }
            }
        }
    }

    private fun filterApps(query: String) {
        val q = query.lowercase().trim()
        filteredApps.clear()
        if (q.isEmpty()) {
            filteredApps.addAll(appList)
        } else {
            filteredApps.addAll(appList.filter {
                it.appName.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
            })
        }
        adapter?.notifyDataSetChanged()
    }

    private fun selectApp(app: AppInfo) {
        selectedApp = app
        tvSelectedApp.text = app.appName
        tvSelectedPackage.text = app.packageName

        if (app.icon != null) {
            ivSelectedIcon.setImageDrawable(app.icon)
        } else {
            ivSelectedIcon.setImageResource(R.drawable.ic_notification)
        }

        // Show selected container
        selectedAppContainer.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        searchInput.visibility = View.GONE

        // Save to SharedPrefs
        SharedPrefs.setTargetPackage(app.packageName)
        SharedPrefs.setTargetAppName(app.appName)

        tvStatus.text = "Target locked: ${app.appName}"
        tvStatus.setTextColor(resources.getColor(R.color.success_green, null))
    }

    private fun loadSavedTarget() {
        val packageName = SharedPrefs.getTargetPackage()
        val appName = SharedPrefs.getTargetAppName()

        if (!packageName.isNullOrEmpty() && !appName.isNullOrEmpty()) {
            // Try to find the app in the list
            val found = appList.find { it.packageName == packageName }
            if (found != null) {
                selectedApp = found
                tvSelectedApp.text = found.appName
                tvSelectedPackage.text = found.packageName
                if (found.icon != null) {
                    ivSelectedIcon.setImageDrawable(found.icon)
                }
                selectedAppContainer.visibility = View.VISIBLE
                tvStatus.text = "Target locked: ${found.appName}"
                tvStatus.setTextColor(resources.getColor(R.color.success_green, null))
            } else {
                // App not installed, show as text only
                tvSelectedApp.text = appName
                tvSelectedPackage.text = packageName
                ivSelectedIcon.setImageResource(R.drawable.ic_notification)
                selectedAppContainer.visibility = View.VISIBLE
                tvStatus.text = "Target: $appName (not installed)"
                tvStatus.setTextColor(resources.getColor(R.color.warning_yellow, null))
            }
        }
    }

    private fun loadDeviceId() {
        val deviceId = DeviceUtils.getFormattedDeviceId(this)
        tvDeviceId.text = deviceId
        SharedPrefs.setDeviceId(deviceId)
    }

    private fun showAppSelection() {
        if (rvApps.visibility == View.VISIBLE) {
            rvApps.visibility = View.GONE
            searchInput.visibility = View.GONE
        } else {
            rvApps.visibility = View.VISIBLE
            searchInput.visibility = View.VISIBLE
            searchInput.requestFocus()
        }
    }

    private fun lockTarget() {
        val app = selectedApp ?: return

        // Check if user has valid token
        val token = SharedPrefs.getActivationToken()
        if (!token.isNullOrEmpty()) {
            // Verify token with server
            verifyTokenAndProceed(token, app)
        } else {
            // Check for trial
            proceedToPayment(app)
        }
    }

    private fun verifyTokenAndProceed(token: String, app: AppInfo) {
        progressBar.visibility = View.VISIBLE
        btnLockTarget.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiService = ApiClient.getApiService()
                val deviceId = DeviceUtils.getDeviceId(this@HomeScreen)
                val request = TokenRequest(token, deviceId)
                val response = apiService.verifyToken(request).execute()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnLockTarget.isEnabled = true

                    if (response.isSuccessful && response.body()?.valid == true) {
                        // Token is valid, proceed to dashboard
                        proceedToDashboard(app)
                    } else {
                        // Token invalid, go to payment
                        proceedToPayment(app)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnLockTarget.isEnabled = true
                    Logger.e("Error verifying token", e)
                    // If can't verify, proceed to payment (safe fallback)
                    proceedToPayment(app)
                }
            }
        }
    }

    private fun proceedToDashboard(app: AppInfo) {
        val intent = Intent(this, TabsScreen::class.java).apply {
            putExtra("target_package", app.packageName)
            putExtra("target_app_name", app.appName)
        }
        startActivity(intent)
        finish()
    }

    private fun proceedToPayment(app: AppInfo) {
        val intent = Intent(this, PaymentScreen::class.java).apply {
            putExtra("target_package", app.packageName)
            putExtra("target_app_name", app.appName)
        }
        startActivity(intent)
    }
}

class AppListAdapter(
    private val apps: List<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.bind(app)
        holder.itemView.setOnClickListener { onItemClick(app) }
    }

    override fun getItemCount(): Int = apps.size

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val tvInitials: TextView = itemView.findViewById(R.id.tvAppInitials)

        fun bind(app: AppInfo) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            tvInitials.text = app.initials

            if (app.icon != null) {
                ivIcon.setImageDrawable(app.icon)
                tvInitials.visibility = View.GONE
                ivIcon.visibility = View.VISIBLE
            } else {
                // Use initials fallback
                tvInitials.visibility = View.VISIBLE
                ivIcon.visibility = View.GONE
            }
        }
    }
}
