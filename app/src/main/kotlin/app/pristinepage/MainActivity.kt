package app.pristinepage

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pristinepage.capture.LibraryPickerBinding
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.ImportSessionId
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.ImportLibraryViewModel
import app.pristinepage.library.LibraryIdentity
import app.pristinepage.library.LibraryScreen
import app.pristinepage.library.LibrarySession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var picker: LibraryPickerBinding? = null
    private var observing: Job? = null
    private var session: LibrarySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildPolicy.supportsApi(Build.VERSION.SDK_INT)) { finish(); return }
        enableEdgeToEdge()
        val restored = try { restore(savedInstanceState) } catch (_: RuntimeException) {
            // Invalid saved identity cannot invent a replacement request/destination. The explicit library action still discovers durable pending insertions without reopening temporary provider references.
            setContent { AppTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                Text(stringResource(R.string.error_session_expired))
                Button(onClick = { start(LibraryIdentity()) }) { Text(stringResource(R.string.open_library)) }
            } } }
            return
        }
        start(restored)
    }

    private fun start(restored: LibraryIdentity) {
        val services = (application as PristinePageApplication).services
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ImportLibraryViewModel::class.java)
                return ImportLibraryViewModel(ApplicationLibraryActions(services::imports), restored) as T
            }
        }
        val retained = ViewModelProvider(this, factory)[ImportLibraryViewModel::class.java].session
        session = retained
        observing?.cancel(); picker?.close()
        val binding = LibraryPickerBinding(activityResultRegistry, retained)
        picker = binding
        binding.synchronize()
        observing = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { retained.state.collect { binding.synchronize() } } }
        setContent { AppTheme { LibraryScreen(retained) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.identity()?.let { identity ->
            outState.putBundle(SAVED, Bundle().apply {
                identity.selected?.let { putString(SELECTED, it.value) }
                identity.pending?.let { putString(PENDING, it.value) }
            })
        }
    }

    override fun onDestroy() { picker?.close(); picker = null; super.onDestroy() }

    private companion object {
        const val SAVED = "pristinepage.library.identity.v1"
        const val SELECTED = "document"
        const val PENDING = "batch"
        @Suppress("DEPRECATION") fun restore(saved: Bundle?): LibraryIdentity {
            val raw = saved?.get(SAVED) ?: return LibraryIdentity()
            require(raw is Bundle)
            fun id(key: String): String? = if (raw.containsKey(key)) (raw.get(key) as? String ?: throw IllegalArgumentException("Invalid saved identity")) else null
            return LibraryIdentity(id(SELECTED)?.let(::DocumentId), id(PENDING)?.let(::ImportSessionId))
        }
    }
}

@Composable private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
