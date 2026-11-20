package app.pristinepage.design

import android.content.Context
import app.pristinepage.data.model.AlbumOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HomeThumbnailLayout {
    STACK, ROW;

    fun <T> pages(ordered: List<T>): List<T> = when (this) {
        STACK -> ordered.take(3)
        ROW -> ordered
    }
}

internal data class AtelierSettings(
    val order: AlbumOrder = AlbumOrder.MODIFIED,
    val showCreationInNameSort: Boolean = false,
    val showCameraGrid: Boolean = true,
    val homeThumbnailLayout: HomeThumbnailLayout = HomeThumbnailLayout.STACK,
) {
    companion object {
        @JvmStatic fun fromStored(values: Map<String, *>): AtelierSettings = AtelierSettings(
            AlbumOrder.entries.firstOrNull { it.name == values["order"] } ?: AlbumOrder.MODIFIED,
            values["created"] as? Boolean ?: false,
            values["grid"] as? Boolean ?: true,
            HomeThumbnailLayout.entries.firstOrNull { it.name == values["homeThumbnailLayout"] } ?: HomeThumbnailLayout.STACK,
        )
    }
}

/** Process-owned, main-thread preference changes. The keys are independent of albums and never store photo or picker data. */
internal class AtelierPreferences(context: Context, name: String = "pristinepage.atelier.v1") {
    private val storage = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(AtelierSettings.fromStored(storage.all))
    val settings = current.asStateFlow()

    fun setOrder(order: AlbumOrder) {
        storage.edit().putString("order", order.name).apply()
        current.value = current.value.copy(order = order)
    }

    fun setShowCreationInNameSort(show: Boolean) {
        storage.edit().putBoolean("created", show).apply()
        current.value = current.value.copy(showCreationInNameSort = show)
    }

    fun setShowCameraGrid(show: Boolean) {
        storage.edit().putBoolean("grid", show).apply()
        current.value = current.value.copy(showCameraGrid = show)
    }

    fun setHomeThumbnailLayout(layout: HomeThumbnailLayout) {
        storage.edit().putString("homeThumbnailLayout", layout.name).apply()
        current.value = current.value.copy(homeThumbnailLayout = layout)
    }
}
