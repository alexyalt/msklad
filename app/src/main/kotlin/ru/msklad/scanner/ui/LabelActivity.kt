package ru.msklad.scanner.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.msklad.scanner.R
import ru.msklad.scanner.databinding.ActivityLabelBinding

class LabelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLabelBinding
    private lateinit var sounds: SoundPlayer
    private val viewModel: LabelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLabelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sounds = SoundPlayer(this)

        binding.searchButton.setOnClickListener { viewModel.lookupProduct(codeText()) }
        binding.printButton.setOnClickListener { viewModel.print(codeText(), countText()) }
        binding.codeEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = viewModel.clearLookup()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.codeEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.lookupProduct(codeText())
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.lookup.collect { renderLookup(it) } }
                launch { viewModel.print.collect { renderPrint(it) } }
                launch { viewModel.highlight.collect { applyHighlight(it) } }
                launch { viewModel.outcomes.collect { onOutcome(it) } }
            }
        }
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    private fun codeText() = binding.codeEdit.text?.toString().orEmpty()

    private fun countText() = binding.countEdit.text?.toString().orEmpty()

    private fun renderLookup(state: LookupState) {
        binding.searchButton.isEnabled = state != LookupState.Loading
        val text = when (state) {
            LookupState.Idle, LookupState.Loading -> null
            is LookupState.Found -> getString(R.string.label_product, state.product.name, state.product.code)
            is LookupState.Failed -> state.message
        }
        setTextOrHide(binding.productText, text)
    }

    private fun renderPrint(state: PrintState) {
        binding.printButton.isEnabled = state != PrintState.Sending
        binding.progress.visibility = if (state == PrintState.Sending) View.VISIBLE else View.GONE
        val text = when (state) {
            PrintState.Idle, PrintState.Sending -> null
            is PrintState.Done -> getString(R.string.label_printed, state.name, state.count)
            is PrintState.Failed -> state.message
        }
        setTextOrHide(binding.resultText, text)
    }

    private fun onOutcome(success: Boolean) {
        if (success) {
            sounds.play(Sound.SUCCESS)
            binding.codeEdit.setText("")
            binding.countEdit.setText("")
            binding.codeEdit.requestFocus()
        } else {
            sounds.play(Sound.ERROR)
        }
    }

    private fun applyHighlight(highlight: Highlight) {
        val color = when (highlight) {
            Highlight.NONE -> R.color.bg_idle
            Highlight.SENDING -> R.color.bg_sending
            Highlight.SUCCESS -> R.color.bg_success
            Highlight.ERROR -> R.color.bg_error
        }
        binding.root.setBackgroundColor(ContextCompat.getColor(this, color))
    }

    private fun setTextOrHide(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
