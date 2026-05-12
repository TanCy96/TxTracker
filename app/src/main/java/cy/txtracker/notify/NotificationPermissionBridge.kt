package cy.txtracker.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Decouples ViewModels from the Activity. ViewModels call [request]; the
 * activity-side code observes [requests] and launches the Activity Result
 * contract; result flows back through [onResult] to resume the suspended call.
 *
 * Pre-Tiramisu: [request] returns true immediately. POST_NOTIFICATIONS is not
 * a runtime permission below API 33; it's granted at install time.
 */
@Singleton
class NotificationPermissionBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Fired when a ViewModel asks for the permission. Activity collects + launches launcher. */
    val requests = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    private var pending: ((Boolean) -> Unit)? = null

    suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) return true
        return suspendCancellableCoroutine { cont ->
            pending = { granted -> cont.resume(granted) }
            requests.tryEmit(Unit)
            cont.invokeOnCancellation { pending = null }
        }
    }

    fun onResult(granted: Boolean) {
        val cb = pending
        pending = null
        cb?.invoke(granted)
    }
}
