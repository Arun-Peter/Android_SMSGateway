package com.r2r.smsgateway

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup, the access token, example commands and the recent message log. */
class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    private companion object {
        const val RECENT = 15 // messages shown under Recent messages; Export log has them all
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Own fixed header instead of the system title bar, which content could scroll under.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        val header = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Android 15+ draws apps edge to edge: keep everything between the status bar (and camera cutout)
        // and the navigation bar, so nothing is hidden behind them.
        screen.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(screen)
        // Dark status / navigation bar icons on the light theme, so the clock and battery stay readable.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (!night) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
        if (!allGranted()) askPermissions()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private val wanted = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE)
    private fun allGranted() = wanted.all { granted(it) }
    private fun askPermissions() = requestPermissions(wanted, 1)

    private fun render() {
        root.removeAllViews()
        val token = Token.get(this)

        heading("Setup")
        status("SMS permission", granted(Manifest.permission.SEND_SMS))
        status("Notifications (delivered / failed; KDE Connect shows them on the computer)", granted(Manifest.permission.POST_NOTIFICATIONS))
        status("Phone (optional: list the SIMs and choose one by slot)", granted(Manifest.permission.READ_PHONE_STATE))
        if (!allGranted()) button("Allow permissions") { askPermissions() }

        // Without these the phone may not start the app for a command or a late delivery report.
        val autostart = PhoneSetup.autostart(this)
        val battery = PhoneSetup.batteryUnrestricted(this)
        if (PhoneSetup.isXiaomi()) {
            when (autostart) {
                true -> status("Autostart", true)
                false -> status("Autostart is off: commands and late delivery reports can be missed", false)
                null -> text("?  Autostart: this phone does not let the app check it; please check in App info", color = Color.GRAY)
            }
        }
        status(
            if (battery) "Battery saver: No restrictions" else "Battery saver restricts the app: set it to No restrictions",
            battery,
        )
        val xiaomiOff = PhoneSetup.isXiaomi() && autostart != true
        if (xiaomiOff) button("Open Autostart settings") { PhoneSetup.openAutostart(this) }
        button(if (battery) "Open App info" else "Open App info (Battery saver)") { PhoneSetup.openAppInfo(this) }

        heading("Access token")
        note("Every command must include it. Keep it private; a new token makes old scripts stop working.")
        mono(token)
        row(
            "Copy token" to {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SMS Gateway token", token))
                Toast.makeText(this, "Token copied", Toast.LENGTH_SHORT).show()
            },
            "New token" to {
                AlertDialog.Builder(this)
                    .setTitle("Create a new token?")
                    .setMessage("Scripts that use the current token will stop working until you update them.")
                    .setPositiveButton("New token") { _, _ -> Token.renew(this); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
        )

        heading("Use from adb or Termux")
        note("From the computer put \"adb shell\" in front; in Termux run it as is. The reply is JSON after data=.")
        mono(
            "T=$token\n\n" +
                "am broadcast -n $packageName/.Api -a send --es token \$T --es to 0000000000 --es text \"hello\" --es id MSG001\n\n" +
                "am broadcast -n $packageName/.Api -a status --es token \$T --es id MSG001\n\n" +
                "am broadcast -n $packageName/.Api -a list --es token \$T"
        )
        note(
            "Status goes QUEUED → SENT → DELIVERED, or FAILED with the reason. Times come from the carrier's report " +
                "when it has them, otherwise from the phone. To choose the SIM add --ei slot 2 (SIM slot) or --ei sub <id>."
        )

        heading("Recent messages")
        val store = Store.of(this)
        val total = store.count()
        row(
            "Refresh" to {
                render()
                val n = Store.of(this).count()
                Toast.makeText(this, "Refreshed: $n message${if (n == 1) "" else "s"} in the log", Toast.LENGTH_SHORT).show()
            },
            "Export log" to {
                val text = try {
                    "Saved to ${LogExport.write(this)}"
                } catch (e: Exception) {
                    "Export failed: ${e.message ?: e.javaClass.simpleName}"
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            },
            "Clear log" to { confirmClear(total) },
        )
        note(
            "Showing the latest ${minOf(RECENT, total)} of $total. Export log saves all of them, with the carrier's " +
                "reference and times, as a CSV in Download/SmsGateway."
        )
        val recent = store.recent(RECENT)
        if (recent.isEmpty()) note("Nothing sent yet.")
        recent.forEach { m ->
            val t = store.timeline(m)
            val time = Message.stamp((t.delivered ?: t.failed ?: t.sent)?.ms ?: m.queuedAt)
            text(
                "${m.id}  ·  ${m.state}\n→ ${m.to}  ·  $time\n${m.text.take(60)}" + (m.error?.let { "\n$it" } ?: ""),
                color = when (m.state) {
                    State.DELIVERED -> Color.rgb(46, 125, 50)
                    State.FAILED -> Color.rgb(198, 40, 40)
                    else -> null
                },
            ).setPadding(0, dp(8), 0, dp(8))
        }

        note("SMS Gateway ${BuildConfig.VERSION_NAME}")
    }

    private fun confirmClear(total: Int) {
        if (total == 0) {
            Toast.makeText(this, "The log is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        val password = EditText(this).apply {
            hint = "Developer password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(password) }
        AlertDialog.Builder(this)
            .setTitle("Clear the log?")
            .setMessage(
                "This deletes all $total message${if (total == 1) "" else "s"} and their delivery details from SMS Gateway " +
                    "and cannot be undone. A copy of the log is saved to Download/SmsGateway first.\n\n" +
                    "The messages in your phone's SMS app are not affected."
            )
            .setView(box)
            .setPositiveButton("Clear log") { _, _ -> clearWithCopy(password.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Checks the password, saves a copy of the whole log, and only then deletes it. */
    private fun clearWithCopy(password: String) {
        if (!Logic.isClearPassword(password)) {
            Toast.makeText(this, "Wrong password. Nothing was deleted.", Toast.LENGTH_LONG).show()
            return
        }
        val copy = try {
            LogExport.write(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save the copy (${e.message}). Nothing was deleted.", Toast.LENGTH_LONG).show()
            return
        }
        val n = Store.of(this).clear()
        render()
        Toast.makeText(this, "Copy saved to $copy. Log cleared: $n message${if (n == 1) "" else "s"} deleted.", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ small view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(t: String) = text(t, size = 18f, bold = true).also { it.setPadding(0, dp(20), 0, dp(6)) }

    private fun note(t: String) = text(t, size = 13f).also { it.alpha = 0.75f; it.setPadding(0, dp(4), 0, dp(4)) }

    private fun status(label: String, ok: Boolean) =
        text((if (ok) "✓  " else "✗  ") + label, color = if (ok) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))

    private fun mono(t: String) = text(t, size = 13f).also {
        it.typeface = Typeface.MONOSPACE
        it.setTextIsSelectable(true)
        it.setPadding(dp(8), dp(8), dp(8), dp(8))
        it.setBackgroundColor(Color.argb(24, 128, 128, 128))
    }

    private fun text(t: String, size: Float = 15f, bold: Boolean = false, color: Int? = null): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            color?.let { setTextColor(it) }
            root.addView(this)
        }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, onClick) ->
            r.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            })
        }
        root.addView(r)
    }
}
