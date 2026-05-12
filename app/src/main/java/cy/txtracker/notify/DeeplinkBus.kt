package cy.txtracker.notify

import cy.txtracker.ui.MainActivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot bus carrying deeplink intents from the activity to interested
 * consumers. Two independent SharedFlows so AppRoute (navigation) and
 * HomeViewModel (filter state) don't compete for the same emission.
 *
 * `replay = 0` + `extraBufferCapacity = 1` + `DROP_OLDEST`: if the emit happens
 * before a consumer is collecting, the value is buffered until that consumer
 * arrives, then drained. No replay across config changes.
 */
@Singleton
class DeeplinkBus @Inject constructor() {
    private val _forAppRoute = MutableSharedFlow<MainActivity.Deeplink>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val forAppRoute: SharedFlow<MainActivity.Deeplink> = _forAppRoute.asSharedFlow()

    private val _forHome = MutableSharedFlow<MainActivity.Deeplink>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val forHome: SharedFlow<MainActivity.Deeplink> = _forHome.asSharedFlow()

    fun emit(deeplink: MainActivity.Deeplink) {
        _forAppRoute.tryEmit(deeplink)
        _forHome.tryEmit(deeplink)
    }
}
