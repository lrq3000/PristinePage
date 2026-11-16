package app.pristinepage

import android.app.Application

/** Process lifetime owns the service root. No eager camera permission, provider read, database open or background-completion promise is attached to Application.onCreate. */
class PristinePageApplication : Application() {
    val services: ApplicationServices by lazy { ApplicationServices(this) }
}
