package ru.msklad.scanner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.msklad.scanner.R
import ru.msklad.scanner.data.MoveInfo
import ru.msklad.scanner.data.StoreRef
import ru.msklad.scanner.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val move = moveFromIntent(intent)
        binding.moveTitle.text = getString(R.string.move_title, move.name)
        binding.moveRoute.text = getString(R.string.move_route, move.source.name, move.target.name)
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
