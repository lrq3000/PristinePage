package app.pristinepage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.model.AlbumOrder
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtelierPreferencesTest {
    @Test fun ANewOwnerReadsTheSameSettingsAndIgnoresAnInvalidTypeIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-atelier-${UUID.randomUUID()}"
        try {
            val original = app.pristinepage.design.AtelierPreferences(context, name)
            original.setOrder(AlbumOrder.TITLE_ZA)
            original.setShowCreationInNameSort(true)
            original.setShowCameraGrid(false)
            val reopened = app.pristinepage.design.AtelierPreferences(context, name)
            assertEquals(app.pristinepage.design.AtelierSettings(AlbumOrder.TITLE_ZA, true, false), reopened.settings.value)

            // One malformed setting must not erase the user's other choices.
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putInt("order", -1).commit()
            assertEquals(app.pristinepage.design.AtelierSettings(AlbumOrder.MODIFIED, true, false),
                app.pristinepage.design.AtelierPreferences(context, name).settings.value)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
