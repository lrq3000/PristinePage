package app.pristinepage.capture

/** Main-confined gate. A JPEG delivered after torch expiry cannot prove it was exposed while lit. */
internal class StillIllumination(flash: Boolean) {
    private enum class State { NOT_REQUESTED, WAITING, LIT, EXPIRED }
    private var state = if (flash) State.WAITING else State.NOT_REQUESTED
    val acceptDelivery get() = state == State.NOT_REQUESTED || state == State.LIT

    fun acknowledge(): Boolean {
        if (state != State.WAITING) return false
        state = State.LIT
        return true
    }

    fun expire() { if (state != State.NOT_REQUESTED) state = State.EXPIRED }
}
