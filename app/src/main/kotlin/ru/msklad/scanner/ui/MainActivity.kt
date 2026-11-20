package ru.msklad.scanner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import ru.msklad.scanner.data.MoveInfo
import ru.msklad.scanner.data.StoreRef
import ru.msklad.scanner.data.formatQuantity
import ru.msklad.scanner.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var move: MoveInfo
    private lateinit var sounds: SoundPlayer
    private lateinit var analysisExecutor: ExecutorService
    private val viewModel: MainViewModel by viewModels()
    private val barcodeScanner = BarcodeScanning.getClient()
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setScannerOn(true)
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
        binding.moveRoute.text = getString(R.string.move_route, move.source.name, move.target.name)

        binding.scannerToggle.setOnClickListener { toggleScanner() }
        binding.sendButton.setOnClickListener { submitManual() }
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
                launch { viewModel.scannerOn.collect { applyScannerState(it) } }
                launch { viewModel.result.collect { render(it) } }
                launch { viewModel.highlight.collect { applyHighlight(it) } }
                launch { viewModel.outcomes.collect { onOutcome(it) } }
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

    private fun applyScannerState(on: Boolean) {
        binding.scannerToggle.setText(if (on) R.string.scanner_off else R.string.scanner_on)
        binding.scannerOffLabel.visibility = if (on) View.GONE else View.VISIBLE
        binding.previewView.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (!on) {
            cameraProvider?.unbindAll()
        } else if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            if (!viewModel.scannerOn.value) return@addListener

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

    private fun submitManual() {
        viewModel.submit(
            binding.codeEdit.text?.toString().orEmpty(),
            binding.quantityEdit.text?.toString().orEmpty(),
        )
    }

    private fun onOutcome(success: Boolean) {
        if (success) {
            sounds.success()
            binding.codeEdit.setText("")
            binding.quantityEdit.setText("")
            binding.codeEdit.requestFocus()
        } else {
            sounds.error()
            (viewModel.result.value as? ResultState.Error)?.let {
                Snackbar.make(binding.root, it.message, Snackbar.LENGTH_LONG).show()
            }
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

    private fun render(state: ResultState) {
        val loading = state == ResultState.Loading
        binding.sendButton.isEnabled = !loading
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE

        val line = (state as? ResultState.Success)?.result?.let {
            getString(
                R.string.result_line,
                formatQuantity(it.sourceAfter),
                formatQuantity(it.quantity),
                formatQuantity(it.targetAfter),
            )
        }
        val message = when (state) {
            ResultState.Idle -> getString(R.string.result_idle)
            is ResultState.Error -> state.message
            else -> null
        }
        setTextOrHide(binding.resultLine, line)
        setTextOrHide(binding.resultMessage, message)
    }

    private fun setTextOrHide(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SOURCE_HREF = "source_href"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_TARGET_HREF = "target_href"
        private const val EXTRA_TARGET_NAME = "target_name"

        fun intent(context: Context, move: MoveInfo) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ID, move.id)
            .putExtra(EXTRA_NAME, move.name)
            .putExtra(EXTRA_SOURCE_HREF, move.source.href)
            .putExtra(EXTRA_SOURCE_NAME, move.source.name)
            .putExtra(EXTRA_TARGET_HREF, move.target.href)
            .putExtra(EXTRA_TARGET_NAME, move.target.name)

        fun moveFromIntent(intent: Intent) = MoveInfo(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            source = StoreRef(
                intent.getStringExtra(EXTRA_SOURCE_HREF).orEmpty(),
                intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty(),
            ),
            target = StoreRef(
                intent.getStringExtra(EXTRA_TARGET_HREF).orEmpty(),
                intent.getStringExtra(EXTRA_TARGET_NAME).orEmpty(),
            ),
        )
    }
}
