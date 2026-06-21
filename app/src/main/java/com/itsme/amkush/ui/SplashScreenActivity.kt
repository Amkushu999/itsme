package com.itsme.amkush.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.itsme.amkush.R

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var tvUser: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var tvCommand: TextView
    private lateinit var tvCursor: TextView
    private lateinit var ivCheckmark: ImageView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        initViews()
        animateText()
    }

    private fun initViews() {
        tvUser = findViewById(R.id.tvUser)
        tvPrompt = findViewById(R.id.tvPrompt)
        tvCommand = findViewById(R.id.tvCommand)
        tvCursor = findViewById(R.id.tvCursor)
        ivCheckmark = findViewById(R.id.ivCheckmark)
    }

    private fun animateText() {
        handler.postDelayed({
            tvUser.text = "user@FaceGate"
            tvPrompt.text = ":~❯ "
            tvCommand.text = "Happy Hooking"

            handler.postDelayed({
                animateCheckmark()
            }, 600)

            handler.postDelayed({
                startCursorBlinking()
            }, 200)

        }, 300)
    }

    private fun animateCheckmark() {
        ivCheckmark.visibility = View.VISIBLE
        ivCheckmark.setImageResource(R.drawable.ic_checkmark_animated)

        // Start the animation
        (ivCheckmark.drawable as? Animatable)?.start()

        // Scale animation for the checkmark
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                ivCheckmark.scaleX = scale
                ivCheckmark.scaleY = scale
                ivCheckmark.alpha = scale
            }
            doOnEnd {
                handler.postDelayed({
                    navigateToHomeScreen()
                }, 800)
            }
        }
        animator.start()
    }

    private fun startCursorBlinking() {
        tvCursor.visibility = View.VISIBLE

        val blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                tvCursor.alpha = it.animatedValue as Float
            }
        }
        blinkAnimator.start()
    }

    private fun navigateToHomeScreen() {
        val intent = Intent(this, HomeScreen::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}