package com.clinttasker.plugin.tasker

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Mini-constructeur de formulaire (pas de XML, pas d'AppCompat). */
class Form(private val activity: Activity) {

    private val pad = (16 * activity.resources.displayMetrics.density).toInt()
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }

    fun title(text: String) {
        root.addView(TextView(activity).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 0, 0, pad / 2)
        })
    }

    fun note(text: String) {
        root.addView(TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(0, pad / 4, 0, pad / 2)
        })
    }

    fun field(label: String, value: String?, hint: String = "", numeric: Boolean = false): EditText {
        root.addView(TextView(activity).apply { text = label; setPadding(0, pad / 2, 0, 0) })
        val et = EditText(activity).apply {
            setText(value ?: "")
            this.hint = hint
            inputType = if (numeric) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return et
    }

    fun check(label: String, value: Boolean): CheckBox {
        val cb = CheckBox(activity).apply { text = label; isChecked = value }
        root.addView(cb)
        return cb
    }

    fun button(label: String, onClick: () -> Unit) {
        root.addView(Button(activity).apply { text = label; setOnClickListener { onClick() } })
    }

    fun show() {
        activity.setContentView(ScrollView(activity).apply { addView(root) })
    }
}
