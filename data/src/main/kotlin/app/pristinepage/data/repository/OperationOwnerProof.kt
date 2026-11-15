package app.pristinepage.data.repository

/** Explicit native/file evidence refresh outside any Room transaction. state() remains a bounded cache/registry lookup; missing/unsupported evidence must stay UNKNOWN, never timeout-derived ENDED. */
interface OperationOwnerProof {
    suspend fun refresh(owner: String): LeaseOwnerState
}
