package com.sui.utils

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat

object Tint {

    val LIGHT_GRAY = Color.parseColor("#F9F9F9")
    val GRAY = Color.parseColor("#DFDFDF")
    val DARK_GRAY = Color.parseColor("#666666")
    val U1N9_BLUE = Color.parseColor("#A1BBDD")
    val U1N9_LIGHT_BLUE = Color.parseColor("#CDDDF2")

    fun apply(root: View) {
        root.applyTintRecursive()
    }

    private fun View.applyTintRecursive() {
        when (this) {
            is SwitchCompat -> applySwitchColors()
            //is SeekBar -> applySeekStyle()
            //is AppCompatEditText -> applyInputStyle()
            //is EditText -> applyInputStyle()
            //is AppCompatButton -> applyButtonColors()
            //is android.widget.Button -> applyButtonColors()
            //is TextView -> applyTextStyle()
        }

        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).applyTintRecursive()
            }
        }
    }

    private fun TextView.applyTextStyle() {
        setTextColor(DARK_GRAY)
    }

    private fun EditText.applyInputStyle() {
        setTextColor(DARK_GRAY)
        setHintTextColor(GRAY)

        backgroundTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(
                U1N9_BLUE,
                U1N9_BLUE
            )
        )
    }

    private fun android.widget.Button.applyButtonColors() {
        setTextColor(DARK_GRAY)

        backgroundTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(
                U1N9_BLUE,
                U1N9_LIGHT_BLUE
            )
        )
    }

    private fun SwitchCompat.applySwitchColors() {
        setTextColor(DARK_GRAY)
        thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                U1N9_BLUE,
                GRAY
            )
        )

        trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(
                LIGHT_GRAY,
                LIGHT_GRAY
            )
        )
    }

    private fun SeekBar.applySeekStyle() {
        thumb?.setTint(U1N9_BLUE)

        progressTintList =
            ColorStateList.valueOf(U1N9_LIGHT_BLUE)

        progressBackgroundTintList =
            ColorStateList.valueOf(GRAY)
    }
}