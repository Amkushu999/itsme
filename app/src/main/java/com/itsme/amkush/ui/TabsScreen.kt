package com.itsme.amkush.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.itsme.amkush.R
import com.itsme.amkush.ui.adapter.ViewPagerAdapter
import com.itsme.amkush.ui.fragments.*
import com.itsme.amkush.utils.SharedPrefs

class TabsScreen : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageView

    private var targetPackage: String? = null
    private var targetAppName: String? = null

    private val fragments = listOf<Fragment>(
        HomeFragment(),
        StreamSetupFragment(),
        DenyListFragment(),
        MediaFragment(),
        StatsFragment(),
        DeviceSpoofFragment()
    )

    private val tabTitles = listOf(
        "Home",
        "Stream",
        "Deny",
        "Media",
        "Stats",
        "Settings"
    )

    private val tabIcons = listOf(
        R.drawable.ic_home,
        R.drawable.ic_stream,
        R.drawable.ic_shield,
        R.drawable.ic_media,
        R.drawable.ic_stats,
        R.drawable.ic_settings
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs)

        // Get intent data
        targetPackage = intent.getStringExtra("target_package")
        targetAppName = intent.getStringExtra("target_app_name")

        // Save target if coming from payment
        if (intent.getBooleanExtra("from_payment", false)) {
            targetPackage?.let { SharedPrefs.setTargetPackage(it) }
            targetAppName?.let { SharedPrefs.setTargetAppName(it) }
        }

        initViews()
        setupViewPager()
        setupTabLayout()
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        tvTitle = findViewById(R.id.tvTitle)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener {
            finish()
        }

        // Set target app name in title
        val appName = targetAppName ?: SharedPrefs.getTargetAppName()
        tvTitle.text = appName ?: "FaceGate"
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this, fragments)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = true

        // Pass target info to fragments
        for (fragment in fragments) {
            if (fragment is HomeFragment) {
                fragment.setTargetInfo(targetPackage, targetAppName)
            }
            if (fragment is StreamSetupFragment) {
                fragment.setTargetInfo(targetPackage, targetAppName)
            }
        }
    }

    private fun setupTabLayout() {
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
            tab.setIcon(tabIcons[position])
        }.attach()
    }

    override fun onBackPressed() {
        if (viewPager.currentItem == 0) {
            super.onBackPressed()
        } else {
            viewPager.currentItem = 0
        }
    }

    fun switchToTab(position: Int) {
        if (position in 0 until fragments.size) {
            viewPager.currentItem = position
        }
    }

    fun getTargetPackage(): String? = targetPackage ?: SharedPrefs.getTargetPackage()
    fun getTargetAppName(): String? = targetAppName ?: SharedPrefs.getTargetAppName()
}