package ru.msklad.scanner.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import ru.msklad.scanner.data.MoveInfo
import ru.msklad.scanner.data.StoreRef
import ru.msklad.scanner.databinding.ActivityMainBinding
import java.math.BigDecimal
import java.math.RoundingMode
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
                launch {
                    viewModel.outcomes.collect { success ->
                        if (success) sounds.success() else sounds.error()
                    }
                }
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

    private fun render(state: ResultState) {
        val resultCard = binding.resultZone
        binding.sendButton.isEnabled = state != ResultState.Loading
        binding.progress.visibility = if (state == ResultState.Loading) View.VISIBLE else View.GONE
        when (state) {
            ResultState.Idle -> {
                resultCard.setBackgroundColor(color(R.color.neutral_bg))
                binding.resultTitle.setTextColor(color(R.color.black))
                binding.resultTitle.setText(R.string.result_idle)
                binding.resultProduct.text = ""
                binding.resultSource.text = ""
                binding.resultTarget.text = ""
            }
            ResultState.Loading -> {
                resultCard.setBackgroundColor(color(R.color.neutral_bg))
                binding.resultTitle.setTextColor(color(R.color.black))
                binding.resultTitle.setText(R.string.result_loading)
            }
            is ResultState.Success -> {
                val r = state.result
                resultCard.setBackgroundColor(color(R.color.success_green_bg))
                binding.resultTitle.setTextColor(color(R.color.success_green))
                binding.resultTitle.setText(R.string.result_success)
                binding.resultProduct.text = r.productName
                binding.resultSource.text = getString(R.string.result_source, move.source.name, fmt(r.sourceStock))
                binding.resultTarget.text = getString(
                    R.string.result_target, move.target.name, fmt(r.targetStockBefore), fmt(r.targetStockAfter),
                )
                binding.codeEdit.setText("")
                binding.quantityEdit.setText("1")
                binding.codeEdit.requestFocus()
                Snackbar.make(binding.root, getString(R.string.snack_added, r.productName, fmt(r.quantity)), Snackbar.LENGTH_SHORT).show()
            }
            is ResultState.Error -> {
                resultCard.setBackgroundColor(color(R.color.error_red_bg))
                binding.resultTitle.setTextColor(color(R.color.error_red))
                binding.resultTitle.setText(R.string.result_error)
                binding.resultProduct.text = state.message
                binding.resultSource.text = ""
                binding.resultTarget.text = ""
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun fmt(value: Double): String =
        BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

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
