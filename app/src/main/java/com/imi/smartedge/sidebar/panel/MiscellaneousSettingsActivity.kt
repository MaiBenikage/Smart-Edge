package com.imi.smartedge.sidebar.panel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.imi.smartedge.sidebar.panel.databinding.ActivitySettingsMiscBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MiscellaneousSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsMiscBinding
    private lateinit var panelPrefs: PanelPreferences

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    // OpenDocument with a MIME array: GetContent("application/json") hid
    // backups whose provider reports text/plain or
    // application/octet-stream (common for .json files shared from
    // other apps or downloaded). The JSON is validated on import, so
    // accepting the wider set is safe.
    private val importFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Read off the main thread, bounded: importFromJson() caps at
        // IMPORT_MAX_CHARS, but the old code already readText()s the whole
        // file (on the main thread) before calling it, so a multi-MB
        // backup could OOM first. Read at most cap + 1 chars and reject
        // anything over the cap without ever holding it in RAM.
        lifecycleScope.launch(Dispatchers.IO) {
            var readError: String? = null
            val json: String? = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream))
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var total = 0
                    var overCap = false
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > PanelPreferences.IMPORT_MAX_CHARS) {
                            overCap = true
                            break
                        }
                        sb.append(buf, 0, n)
                    }
                    if (overCap) null else sb.toString()
                }
            } catch (e: Exception) {
                readError = e.message
                null
            }
            withContext(Dispatchers.Main) {
                if (readError != null) {
                    binding.root.showModernToast(getString(R.string.toast_import_read_error, readError ?: ""))
                    return@withContext
                }
                val success = json != null && panelPrefs.importFromJson(json)
                if (success) {
                    // An import can change the theme and the language: re-apply
                    // them and recreate so the change is visible now, instead
                    // of only after the next process start.
                    applyAppTheme(this@MiscellaneousSettingsActivity)
                    LocaleHelper.applyPersistedLocale(this@MiscellaneousSettingsActivity)
                    applyGlobalRefresh()
                    binding.root.showModernToast(getString(R.string.toast_import_success))
                    recreate()
                } else {
                    binding.root.showModernToast(getString(R.string.toast_import_failed))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsMiscBinding.inflate(layoutInflater)
        setContentView(binding.root)

        panelPrefs = PanelPreferences(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        updateLanguageLabel()
        binding.featureLanguage.setOnClickListener { showLanguagePicker() }

        binding.btnExportSettings.setOnClickListener {
            exportSettings()
        }

        binding.btnImportSettings.setOnClickListener {
            importFilePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }

        handleDeepLink()
    }

    private fun handleDeepLink() {
        val targetId = intent.getStringExtra(SettingsMainActivity.EXTRA_SCROLL_TO) ?: return
        val viewId = resources.getIdentifier(targetId, "id", packageName)
        if (viewId != 0) {
            val targetView = findViewById<View>(viewId)
            targetView?.post {
                val rect = android.graphics.Rect()
                targetView.getDrawingRect(rect)
                binding.root.offsetDescendantRectToMyCoords(targetView, rect)
                binding.miscScrollView.smoothScrollTo(0, rect.top - 200)
                targetView.highlightView()
            }
        }
    }

    private fun updateLanguageLabel() {
        binding.tvLanguageValue.text = when (panelPrefs.appLanguage) {
            "es" -> "Español"
            else -> "English"
        }
    }

    private fun showLanguagePicker() {
        val languages = arrayOf("English", "Español")
        val codes = arrayOf("en", "es")
        val currentIndex = codes.indexOf(panelPrefs.appLanguage).let { if (it == -1) 0 else it }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_choose_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selected = codes[which]
                if (selected != panelPrefs.appLanguage) {
                    panelPrefs.appLanguage = selected
                    LocaleHelper.setLocale(this, selected)

                    // Restart the overlay service too: it runs in this
                    // process with a base context wrapped at service start,
                    // so it keeps the old locale until it restarts.
                    // ACTION_STOP_RUNTIME tears it down without clearing
                    // serviceEnabled; the delayed start recreates it in the
                    // new language (delayed so the stop is processed first —
                    // an immediate start would reuse the live instance).
                    if (panelPrefs.serviceEnabled) {
                        startService(Intent(this, FloatingPanelService::class.java).apply {
                            action = FloatingPanelService.ACTION_STOP_RUNTIME
                        })
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val start = Intent(this, FloatingPanelService::class.java)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                startForegroundService(start)
                            } else {
                                startService(start)
                            }
                        }, 500)
                    }

                    // Restart app
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private val exportFilePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // The user picked the destination: writing needs no storage
        // permission on any API level. The old pre-Q branch wrote directly
        // to public Downloads, which needs WRITE_EXTERNAL_STORAGE (not
        // declared) and always failed with EACCES on API 26-28.
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { it.write(panelPrefs.exportToJson().toByteArray()) }
                true
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                binding.root.showModernToast(
                    getString(if (ok) R.string.toast_export_saved_file else R.string.toast_export_failed)
                )
            }
        }
    }

    private fun exportSettings() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        exportFilePicker.launch("smartedge_backup_$timestamp.json")
    }

    private fun applyGlobalRefresh() {
        // Same guard as the other screens' applyOnly(): no refresh
        // while the service is off (it would flash its notification).
        if (!panelPrefs.serviceEnabled) return
        val intent = Intent(this, FloatingPanelService::class.java).apply {
            action = FloatingPanelService.ACTION_REFRESH
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            // Background-start restrictions (Android 12+) — nothing to show; the
            // picker/panel refresh will be picked up on the next service start.
        }
    }
}
