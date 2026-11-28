package ru.msklad.scanner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import ru.msklad.scanner.R
import ru.msklad.scanner.data.AddResult
import ru.msklad.scanner.data.DocInfo
import ru.msklad.scanner.data.DocumentType
import ru.msklad.scanner.data.OfflineStore
import ru.msklad.scanner.data.SettingsStore
import ru.msklad.scanner.data.StoreRef
import ru.msklad.scanner.data.journalLetter
import ru.msklad.scanner.data.withUom
import ru.msklad.scanner.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var move: DocInfo
    private lateinit var sounds: SoundPlayer
    private lateinit var analysisExecutor: ExecutorService
    private val viewModel: MainViewModel by viewModels()
    private val barcodeScanner = BarcodeScanning.getClient()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraActive = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setScannerOn(true)
                updateCamera()
            } else {
                viewModel.setScannerOn(false)
                Snackbar.make(binding.root, R.string.camera_denied, Snackbar.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        move = moveFromIntent(intent)
        viewModel.init(move)
        sounds = SoundPlayer(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.moveTitle.text = getString(R.string.move_title, move.name)
        binding.moveRoute.text = getString(R.string.move_route, move.store.name, move.target?.name.orEmpty())

        binding.scannerToggle.setOnClickListener { toggleScanner() }
        binding.offlineSwitch.isChecked = viewModel.offline.value
        binding.offlineSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !OfflineStore(this).load().hasData) {
                Snackbar.make(binding.root, R.string.offline_no_data, Snackbar.LENGTH_LONG).show()
            }
            viewModel.setOffline(checked)
        }
        binding.syncButton.setOnClickListener { viewModel.sync() }
        binding.docGroup.check(docButtonId(viewModel.docType.value))
        binding.docGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) viewModel.setDocType(docFromButtonId(id))
        }
        if (SettingsStore(this).load().printServer.isNotBlank()) {
            binding.labelButton.visibility = View.VISIBLE
            binding.labelButton.setOnClickListener { startActivity(Intent(this, LabelActivity::class.java)) }
        }
        binding.undoButton.setOnClickListener { viewModel.undo() }
        binding.codeEdit.showSoftInputOnFocus = false
        binding.quantityEdit.showSoftInputOnFocus = false
        bindKeys(binding.keypad)
        bindKeys(binding.keypadLarge)
        binding.modeGroup.check(modeButtonId(viewModel.inputMode.value))
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) viewModel.setInputMode(modeFromButtonId(id))
        }
        binding.codeEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitManual()
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.scannerOn.collect { updateCamera() } }
                launch { viewModel.inputMode.collect { applyMode(it) } }
                launch { viewModel.result.collect { render(it) } }
                launch { viewModel.highlight.collect { applyHighlight(it) } }
                launch { viewModel.canUndo.collect { binding.undoButton.visibility = if (it) View.VISIBLE else View.GONE } }
                launch { viewModel.sounds.collect { onSound(it) } }
                launch { viewModel.clearInput.collect { clearInput() } }
                launch { viewModel.recent.collect { renderRecent(it) } }
                launch {
                    viewModel.offline.collect {
                        binding.offlineSwitch.isChecked = it
                        renderSyncButton()
                    }
                }
                launch { viewModel.docType.collect { syncDocButtons(it) } }
                launch {
                    viewModel.syncing.collect {
                        renderSyncButton()
                    }
                }
                launch { viewModel.pendingCount.collect { renderPending(it) } }
            }
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        barcodeScanner.close()
        analysisExecutor.shutdown()
        sounds.release()
        super.onDestroy()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun toggleScanner() {
        if (viewModel.scannerOn.value) {
            viewModel.setScannerOn(false)
        } else if (hasCameraPermission()) {
            viewModel.setScannerOn(true)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateCamera() {
        val scannerOn = viewModel.scannerOn.value
        val active = scannerOn && viewModel.inputMode.value != InputMode.KEYBOARD
        binding.scannerToggle.setText(if (scannerOn) R.string.scanner_off else R.string.scanner_on)
        binding.scannerOffLabel.visibility = if (scannerOn) View.GONE else View.VISIBLE
        binding.previewView.visibility = if (active) View.VISIBLE else View.INVISIBLE
        if (!active) {
            cameraProvider?.unbindAll()
            cameraActive = false
        } else if (hasCameraPermission()) {
            if (!cameraActive) {
                cameraActive = true
                startCamera()
            }
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun applyMode(mode: InputMode) {
        binding.cameraFrame.visibility = if (mode == InputMode.KEYBOARD) View.GONE else View.VISIBLE
        binding.inputBlock.visibility = if (mode == InputMode.SCANNER) View.GONE else View.VISIBLE
        binding.keypad.visibility = if (mode == InputMode.MIXED) View.VISIBLE else View.GONE
        binding.keypadLarge.visibility = if (mode == InputMode.KEYBOARD) View.VISIBLE else View.GONE
        (binding.cameraFrame.layoutParams as LinearLayout.LayoutParams).weight =
            if (mode == InputMode.SCANNER) 6.5f else 2.8f
        binding.cameraFrame.requestLayout()
        updateCamera()
    }

    private fun modeButtonId(mode: InputMode) = when (mode) {
        InputMode.SCANNER -> R.id.modeScanner
        InputMode.MIXED -> R.id.modeMixed
        InputMode.KEYBOARD -> R.id.modeKeyboard
    }

    private fun modeFromButtonId(id: Int) = when (id) {
        R.id.modeScanner -> InputMode.SCANNER
        R.id.modeKeyboard -> InputMode.KEYBOARD
        else -> InputMode.MIXED
    }

    private fun bindKeys(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is Button -> when (child.tag) {
                    "enter" -> child.setOnClickListener { submitManual() }
                    "clear" -> child.setOnClickListener { focusedField().setText("") }
                    else -> child.setOnClickListener { typeDigit(child.text) }
                }
                is ViewGroup -> bindKeys(child)
            }
        }
    }

    private fun focusedField() = if (binding.quantityEdit.hasFocus()) binding.quantityEdit else binding.codeEdit

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            if (!cameraActive) return@addListener

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: IllegalStateException) {
                cameraActive = false
                viewModel.setScannerOn(false)
                Snackbar.make(binding.root, R.string.camera_failed, Snackbar.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val code = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                if (code != null) {
                    val accepted = viewModel.onScanned(code, binding.quantityEdit.text?.toString().orEmpty())
                    if (accepted) binding.codeEdit.setText(code)
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun typeDigit(digit: CharSequence) {
        focusedField().append(digit)
    }

    private fun submitManual() {
        viewModel.submit(
            binding.codeEdit.text?.toString().orEmpty(),
            binding.quantityEdit.text?.toString().orEmpty(),
        )
    }

    private fun onSound(sound: Sound) {
        sounds.play(sound)
        if (sound == Sound.ERROR) {
            (viewModel.result.value as? ResultState.Error)?.let {
                Snackbar.make(binding.root, it.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun clearInput() {
        binding.codeEdit.setText("")
        binding.quantityEdit.setText("")
        binding.codeEdit.requestFocus()
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

    private fun render(state: ResultState) {
        val loading = state == ResultState.Loading
        binding.keypadEnter.isEnabled = !loading
        binding.keypadLargeEnter.isEnabled = !loading
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE

        val added = (state as? ResultState.Success)?.result
        val undone = (state as? ResultState.Undone)?.result
        val product = (added ?: undone)?.let { getString(R.string.product_line, it.productName, it.productCode) }
        val line = added?.let { resultLine(it) }
        val message = when (state) {
            ResultState.Idle -> getString(R.string.result_idle)
            is ResultState.Error -> state.message
            is ResultState.Undone -> getString(
                R.string.undone_message,
                state.result.productName,
                withUom(state.result.quantity, state.result.uom),
            )
            is ResultState.Removed -> getString(
                R.string.removed_message,
                state.entry.name,
                withUom(state.entry.quantity, state.entry.uom),
            )
            is ResultState.Synced -> state.message
            else -> null
        }
        setTextOrHide(binding.resultProduct, product)
        setTextOrHide(binding.resultLine, line)
        setTextOrHide(binding.resultMessage, message)
    }

    /**
     * Строка результата. Для перемещения — «ИЗ → В» между складами, для оприходования и
     * списания — было → изменение → стало на складе документа. Количество всегда с единицей измерения.
     */
    private fun resultLine(result: AddResult): String =
        if (result.docType == DocumentType.MOVE) {
            getString(
                R.string.result_line,
                withUom(result.sourceAfter, result.uom),
                withUom(result.quantity, result.uom),
                withUom(result.targetAfter, result.uom),
            )
        } else {
            val sign = if (result.docType == DocumentType.LOSS) "-" else "+"
            getString(
                R.string.result_line_store,
                withUom(result.storeBefore, result.uom),
                sign + withUom(result.quantity, result.uom),
                withUom(result.sourceAfter, result.uom),
            )
        }

    private fun docButtonId(type: DocumentType) = when (type) {
        DocumentType.MOVE -> R.id.docMove
        DocumentType.SUPPLY -> R.id.docSupply
        DocumentType.LOSS -> R.id.docLoss
    }

    private fun docFromButtonId(id: Int) = when (id) {
        R.id.docSupply -> DocumentType.SUPPLY
        R.id.docLoss -> DocumentType.LOSS
        else -> DocumentType.MOVE
    }

    private fun syncDocButtons(type: DocumentType) {
        binding.docGroup.check(docButtonId(type))
    }

    private fun renderPending(count: Int) {
        val show = count > 0 && viewModel.offline.value
        binding.pendingLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.pendingLabel.text = getString(R.string.pending_count, count)
    }

    /** Выгрузка очереди нужна только в офлайн-режиме: в онлайне данные уходят сразу. */
    private fun renderSyncButton() {
        val offline = viewModel.offline.value
        binding.syncButton.isEnabled = offline && !viewModel.syncing.value
        binding.syncButton.alpha = if (offline) 1f else 0.35f
        if (!offline) {
            binding.pendingLabel.visibility = View.GONE
        } else {
            renderPending(viewModel.pendingCount.value)
        }
    }

    /**
     * Журнал сканирований: одна строка на одно сканирование, с порядковым номером и буквой
     * операции (П — перемещение, О — оприходование, С — списание). Товар, отсканированный
     * несколько раз, больше не склеивается в одну строку.
     */
    private fun renderRecent(entries: List<ScanEntry>) {
        binding.recentList.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val row = layoutInflater.inflate(R.layout.item_recent_scan, binding.recentList, false)
            row.findViewById<TextView>(R.id.recentText).text = getString(
                R.string.recent_scan_line,
                index + 1,
                entry.docType.journalLetter,
                entry.code,
                entry.name.take(RECENT_NAME_LENGTH),
                withUom(entry.quantity, entry.uom),
            )
            row.findViewById<Button>(R.id.recentRemove).setOnClickListener {
                viewModel.removeScanned(entry)
            }
            binding.recentList.addView(row)
        }
        // Новые сканирования дописываются снизу — держим последнюю строку в поле зрения.
        binding.recentScroll.post { binding.recentScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setTextOrHide(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val RECENT_NAME_LENGTH = 50
        private const val EXTRA_ID = "id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SOURCE_HREF = "source_href"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_TARGET_HREF = "target_href"
        private const val EXTRA_TARGET_NAME = "target_name"

        fun intent(context: Context, move: DocInfo) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ID, move.id)
            .putExtra(EXTRA_TYPE, move.type.name)
            .putExtra(EXTRA_NAME, move.name)
            .putExtra(EXTRA_SOURCE_HREF, move.store.href)
            .putExtra(EXTRA_SOURCE_NAME, move.store.name)
            .putExtra(EXTRA_TARGET_HREF, move.target?.href.orEmpty())
            .putExtra(EXTRA_TARGET_NAME, move.target?.name.orEmpty())

        fun moveFromIntent(intent: Intent) = DocInfo(
            type = intent.getStringExtra(EXTRA_TYPE)
                ?.let { runCatching { DocumentType.valueOf(it) }.getOrNull() }
                ?: DocumentType.MOVE,
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            store = StoreRef(
                intent.getStringExtra(EXTRA_SOURCE_HREF).orEmpty(),
                intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty(),
            ),
            target = intent.getStringExtra(EXTRA_TARGET_HREF)
                ?.takeIf(String::isNotBlank)
                ?.let { StoreRef(it, intent.getStringExtra(EXTRA_TARGET_NAME).orEmpty()) },
        )
    }
}
