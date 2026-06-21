package com.itsme.amkush.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.itsme.amkush.R
import com.itsme.amkush.model.HookStatusRegistry
import com.itsme.amkush.utils.SharedPrefs

class StatsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.hookStatusContainer)
        val target = SharedPrefs.getTargetAppName()

        val targetAppView = view.findViewById<TextView>(R.id.tvTargetApp)
        if (!target.isNullOrEmpty()) {
            targetAppView.text = getString(R.string.target_format, target)
            targetAppView.visibility = View.VISIBLE
        }

        val hooks = HookStatusRegistry.getAllHooks()
        var category = ""

        for (hook in hooks) {
            if (category != hook.category) {
                category = hook.category
                val header = TextView(requireContext()).apply {
                    text = category
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 20, 0, 8)
                }
                container.addView(header)
            }

            val itemView = layoutInflater.inflate(R.layout.item_hook_status, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvHookName)
            val tvStatus = itemView.findViewById<TextView>(R.id.tvHookStatus)

            tvName.text = hook.name
            tvStatus.text = if (hook.isActive) "✅" else "❌"
            tvStatus.setTextColor(
                if (hook.isActive) {
                    resources.getColor(R.color.success_green, null)
                } else {
                    resources.getColor(R.color.error_red, null)
                }
            )

            container.addView(itemView)
        }
    }
}