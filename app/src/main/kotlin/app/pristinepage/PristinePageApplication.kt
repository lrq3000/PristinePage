package app.pristinepage

import android.app.Application
import app.pristinepage.design.AtelierPreferences

/** Process lifetime owns the service root. No eager camera permission, provider read, database open or background-completion promise is attached to Application.onCreate. */
class PristinePageApplication : Application() {
    val services: ApplicationServices by lazy { ApplicationServices(this) }
    internal val atelierPreferences by lazy { AtelierPreferences(this) }
}
