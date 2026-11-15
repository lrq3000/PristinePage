package app.pristinepage.data.repository

import kotlinx.coroutines.CoroutineScope

/** A fresh opaque owner surrounds the complete structured operation, including native/resource finally scopes. Merely requesting cancellation must not mark an owner ENDED. Unknown identities, including owners absent after process restart, are never assumed ended. */
interface OperationOwners : LeaseOwnerLiveness {
    suspend fun <T> run(block: suspend CoroutineScope.(owner: String) -> T): T
}
