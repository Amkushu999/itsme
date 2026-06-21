package com.itsme.amkush.ui.fragments

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itsme.amkush.R
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.utils.Logger
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.*

class DenyListFragment : Fragment() {

    private lateinit var rvApps: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    private val appList = mutableListOf<AppInfo>()
    private val filteredApps = mutableListOf<AppInfo>()
    private var denyList = mutableSetOf<String>()
    private var adapter: DenyListAdapter? = null
    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_deny_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        loadDenyList()
        setupRecyclerView()
        setupSearch()
        loadInstalledApps()
    }

    private fun initViews(view: View) {
        rvApps = view.findViewById(R.id.rvApps)
        searchInput = view.findViewById(R.id.searchInput)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        progressBar = view.findViewById(R.id.progressBar)
    }

    private fun loadDenyList() {
        denyList = SharedPrefs.getDenyList().toMutableSet()
    }

    private fun setupRecyclerView() {
        adapter = DenyListAdapter(filteredApps, denyList) { app, isDenied ->
            toggleDeny(app, isDenied)
        }
        rvApps.layoutManager = LinearLayoutManager(requireContext())
        rvApps.adapter = adapter
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    filterApps(s.toString())
                }
            }
        })
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun loadInstalledApps() {
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageManager = requireContext().packageManager
                val packages = packageManager.getInstalledApplications(0)
                val apps = mutableListOf<AppInfo>()

                for (pkg in packages) {
                    val appName = packageManager.getApplicationLabel(pkg).toString()
                    val packageName = pkg.packageName
                    val isSystem = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    if (isSystem && !denyList.contains(packageName)) {
                        continue
                    }

                    val icon = packageManager.getApplicationIcon(pkg)
                    apps.add(AppInfo(packageName, appName, icon, isSystem))
                }

                apps.sortBy { it.appName.lowercase() }

                withContext(Dispatchers.Main) {
                    appList.clear()
                    appList.addAll(apps)
                    filteredApps.clear()
                    filteredApps.addAll(apps)
                    adapter?.notifyDataSetChanged()
                    updateEmptyState()
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Logger.e("Error loading apps", e)
                    Toast.makeText(requireContext(), "Error loading apps: ${e.message}", Toast.LENGTH_SHORT).show()
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
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (filteredApps.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvApps.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvApps.visibility = View.VISIBLE
        }
    }

    private fun toggleDeny(app: AppInfo, isDenied: Boolean) {
        if (isDenied) {
            denyList.add(app.packageName)
            SharedPrefs.addToDenyList(app.packageName)
        } else {
            denyList.remove(app.packageName)
            SharedPrefs.removeFromDenyList(app.packageName)
        }
        val position = filteredApps.indexOfFirst { it.packageName == app.packageName }
        if (position >= 0) {
            adapter?.notifyItemChanged(position)
        }
    }
}

class DenyListAdapter(
    private val apps: List<AppInfo>,
    private val denyList: Set<String>,
    private val onToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<DenyListAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deny_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val isDenied = denyList.contains(app.packageName)
        holder.bind(app, isDenied)
        holder.itemView.setOnClickListener {
            onToggle(app, !isDenied)
        }
    }

    override fun getItemCount(): Int = apps.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val tvInitials: TextView = itemView.findViewById(R.id.tvAppInitials)
        private val ivCheck: ImageView = itemView.findViewById(R.id.ivCheck)

        fun bind(app: AppInfo, isDenied: Boolean) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            tvInitials.text = app.initials

            if (app.icon != null) {
                ivIcon.setImageDrawable(app.icon)
                ivIcon.visibility = View.VISIBLE
                tvInitials.visibility = View.GONE
            } else {
                ivIcon.visibility = View.GONE
                tvInitials.visibility = View.VISIBLE
            }

            if (isDenied) {
                ivCheck.setImageResource(R.drawable.ic_check_circle)
                ivCheck.setColorFilter(android.graphics.Color.parseColor("#FF4444"))
                itemView.setBackgroundResource(R.drawable.item_denied_background)
            } else {
                ivCheck.setImageResource(R.drawable.ic_check_circle_outline)
                ivCheck.setColorFilter(android.graphics.Color.parseColor("#888888"))
                itemView.setBackgroundResource(R.drawable.item_app_background)
            }
        }
    }
}