package app.pristinepage.capture

import androidx.activity.result.ActivityResultRegistry

/** Main-thread, Activity-owned registration. Each callback closes over its immutable persisted request, never mutable navigation. Recreate with the restored request and registry state; close unregisters this owner but does not cancel its durable batch or erase committed pages. */
class PhotoPickerRegistration(registry: ActivityResultRegistry, request: PhotoImportRequest, callback: (PhotoImportRequest, PhotoSelection) -> Unit) : AutoCloseable {
    private val launcher = registry.register(request.registryKey, PhotoPickerContract()) { selection -> callback(request, selection) }

    fun launch() { launcher.launch(Unit) }
    override fun close() { launcher.unregister() }
}
