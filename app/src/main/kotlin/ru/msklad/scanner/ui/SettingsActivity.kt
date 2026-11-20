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
import ru.msklad.scanner.data.Settings
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
        }

        binding.startButton.setOnClickListener { onStartClicked() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.progress.visibility = if (state.loading) View.VISIBLE else View.GONE
                        binding.startButton.isEnabled = !state.loading
                        binding.errorText.visibility = if (state.error == null) View.GONE else View.VISIBLE
                        binding.errorText.text = state.error
                    }
                }
                launch {
                    viewModel.started.collect { move -> startActivity(MainActivity.intent(this@SettingsActivity, move)) }
                }
            }
        }
    }

    private fun onStartClicked() {
        val login = binding.loginEdit.text?.toString().orEmpty().trim()
        val password = binding.passwordEdit.text?.toString().orEmpty()
        val move = binding.moveEdit.text?.toString().orEmpty().trim()

        binding.loginLayout.error = if (login.isEmpty()) getString(R.string.error_required) else null
        binding.passwordLayout.error = if (password.isEmpty()) getString(R.string.error_required) else null
        binding.moveLayout.error = if (move.isEmpty()) getString(R.string.error_required) else null
        if (login.isEmpty() || password.isEmpty() || move.isEmpty()) return

        viewModel.start(Settings(login, password, move))
    }
}
