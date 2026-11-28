package ru.msklad.scanner.ui

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.msklad.scanner.R
import ru.msklad.scanner.data.PrintServerAddress
import ru.msklad.scanner.data.Settings
import ru.msklad.scanner.data.SettingsStore
import ru.msklad.scanner.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            val saved = viewModel.savedSettings()
            binding.loginEdit.setText(saved.login)
            binding.passwordEdit.setText(saved.password)
            binding.moveEdit.setText(saved.moveInput)
            binding.supplyEdit.setText(saved.supplyInput)
            binding.lossEdit.setText(saved.lossInput)
            binding.printServerEdit.setText(saved.printServer)
            binding.internalBarcodeSwitch.isChecked = saved.internalBarcodesOnly
        }
        binding.versionText.text =
            getString(R.string.app_version, packageManager.getPackageInfo(packageName, 0).versionName)
        binding.startButton.setOnClickListener { collectSettings()?.let(viewModel::start) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.started.collect { move ->
                        startActivity(MainActivity.intent(this@SettingsActivity, move))
                    }
                }
            }
        }
    }

    private fun render(state: SettingsState) {
        binding.progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.startButton.isEnabled = !state.loading

        val error = state.error
        binding.errorText.visibility = if (error == null) View.GONE else View.VISIBLE
        binding.errorText.text = error.orEmpty()
    }

    /** Итог последней синхронизации обновляется при каждом возврате с экрана сканирования. */
    override fun onResume() {
        super.onResume()
        renderOfflineLines(viewModel.savedSummary())
    }

    private fun renderOfflineLines(lines: List<String>) {
        binding.offlineText.text =
            if (lines.isEmpty()) getString(R.string.offline_empty) else lines.joinToString("\n")
    }

    /** Проверяет поля и собирает настройки; null — если есть ошибки ввода. */
    private fun collectSettings(): Settings? {
        val login = binding.loginEdit.text?.toString().orEmpty().trim()
        val password = binding.passwordEdit.text?.toString().orEmpty()
        val move = binding.moveEdit.text?.toString().orEmpty().trim()
        val supply = binding.supplyEdit.text?.toString().orEmpty().trim()
        val loss = binding.lossEdit.text?.toString().orEmpty().trim()
        val printServer = binding.printServerEdit.text?.toString().orEmpty().trim()

        binding.loginLayout.error = if (login.isEmpty()) getString(R.string.error_required) else null
        binding.passwordLayout.error = if (password.isEmpty()) getString(R.string.error_required) else null
        binding.moveLayout.error = if (move.isEmpty()) getString(R.string.error_required) else null
        val printServerValid = printServer.isEmpty() || PrintServerAddress.parse(printServer) != null
        binding.printServerLayout.error =
            if (printServerValid) null else getString(R.string.error_print_server)
        if (login.isEmpty() || password.isEmpty() || move.isEmpty() || !printServerValid) return null

        return Settings(
            login = login,
            password = password,
            moveInput = move,
            printServer = printServer,
            supplyInput = supply,
            lossInput = loss,
            internalBarcodesOnly = binding.internalBarcodeSwitch.isChecked,
            // Офлайн-режим переключается только на экране сканирования — сохраняем текущее значение.
            offlineMode = SettingsStore(this).load().offlineMode,
        )
    }
}
