"""Bounded catalog/real Room consumption plus prior routing cases. Inherit exact paired-APK, allocator, identity, outcome and shutdown guards; no UI or permission changes."""

from run_photo_routing import PhotoRoutingRun


class DocumentCatalogRun(PhotoRoutingRun):
    classes = PhotoRoutingRun.classes + ("app.pristinepage.DocumentCatalogIntegrationTest",)
    case_count = 28
