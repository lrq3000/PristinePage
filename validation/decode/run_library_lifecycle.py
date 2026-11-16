"""ID-only discovery and Activity Result binding consumers on the exact P04 target. Inherit allocator/artifact/outcome/shutdown guards; these cases open no external picker UI and acquire no grants."""

from run_document_catalog import DocumentCatalogRun


class LibraryLifecycleRun(DocumentCatalogRun):
    classes = DocumentCatalogRun.classes + ("app.pristinepage.ImportLibraryIntegrationTest", "app.pristinepage.LibraryPickerBindingTest")
    case_count = 36
    missing_marker = "Activity-owned library picker binding is absent"
