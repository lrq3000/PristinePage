package app.pristinepage.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pristinepage.R
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.library.AlbumTitleTemplate
import app.pristinepage.library.defaultAlbumTitle
import app.pristinepage.library.label
import java.util.Locale

internal enum class SettingsPage(val title: Int, val parent: SettingsPage?) {
    ROOT(R.string.atelier_settings, null),
    HOME(R.string.settings_home_and_albums, ROOT),
    CAMERA(R.string.settings_camera, ROOT),
    OCR(R.string.settings_ocr, ROOT),
    TITLE(R.string.carnet_title_template, HOME),
    OCR_LANGUAGES(R.string.settings_ocr_languages, OCR),
    LIBRARY_STORAGE(R.string.settings_library_storage, ROOT),
}

@Composable
internal fun AtelierSettingsScreen(page: SettingsPage, preferences: AtelierPreferences, ocrReady: Boolean,
    libraryStorage: (@Composable () -> Unit)? = null, navigate: (SettingsPage?) -> Unit) {
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val back = { navigate(page.parent) }
    val ocrStatus = if (ocrReady) R.string.settings_ocr_offline else R.string.settings_ocr_not_ready
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
        .testTag("settings-screen-${page.name.lowercase(Locale.ROOT)}")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, modifier = Modifier.size(AtelierStyle.touchTarget).testTag("settings-back")) {
                Icon(painterResource(R.drawable.ic_atelier_back), stringResource(R.string.atelier_back))
            }
            Text(stringResource(page.title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider(color = AtelierStyle.divider)
        key(page) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (page) {
                    SettingsPage.ROOT -> {
                        listOf(SettingsPage.HOME, SettingsPage.CAMERA, SettingsPage.OCR).forEach { SettingsLink(it, navigate) }
                        if (libraryStorage != null) SettingsLink(SettingsPage.LIBRARY_STORAGE, navigate)
                    }
                    SettingsPage.LIBRARY_STORAGE -> libraryStorage?.invoke()
                    SettingsPage.HOME -> {
                        SettingsLink(SettingsPage.TITLE, navigate, tag = "carnet-title-template")
                        SettingsHeading(R.string.atelier_sort)
                        Column(Modifier.selectableGroup()) {
                            AlbumOrder.entries.forEach { order ->
                                SettingsRadio(order.label(), settings.order == order, "settings-sort-${order.name}") { preferences.setOrder(order) }
                            }
                        }
                        SettingsHeading(R.string.carnet_home_thumbnails)
                        Column(Modifier.selectableGroup()) {
                            HomeThumbnailLayout.entries.forEach { layout ->
                                val stack = layout == HomeThumbnailLayout.STACK
                                SettingsRadio(if (stack) R.string.carnet_stack else R.string.carnet_row,
                                    settings.homeThumbnailLayout == layout, "home-thumbnails-${layout.name.lowercase(Locale.ROOT)}",
                                    if (stack) R.string.carnet_stack_help else R.string.carnet_row_help) { preferences.setHomeThumbnailLayout(layout) }
                            }
                        }
                        SettingsSwitch(R.string.atelier_created, settings.showCreationInNameSort, "atelier-date-setting", preferences::setShowCreationInNameSort)
                        Text(stringResource(R.string.atelier_date_setting_note), style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsPage.CAMERA -> SettingsSwitch(R.string.atelier_grid, settings.showCameraGrid, "atelier-grid-setting", preferences::setShowCameraGrid)
                    SettingsPage.OCR -> {
                        Text(stringResource(ocrStatus))
                        SettingsLink(SettingsPage.OCR_LANGUAGES, navigate)
                        SettingsHeading(R.string.settings_ocr_layout)
                        Text(stringResource(R.string.settings_ocr_layout_help), style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.selectableGroup()) {
                            for (layout in OcrLayout.entries) {
                                SettingsRadio(layout.label(), settings.ocrLayout == layout, "settings-ocr-layout-${layout.name.lowercase(Locale.ROOT)}") {
                                    preferences.setOcrLayout(layout)
                                }
                            }
                        }
                        SettingsSwitch(R.string.settings_ocr_diagnostics, settings.ocrDiagnostics, "settings-ocr-diagnostics", preferences::setOcrDiagnostics)
                        Text(stringResource(R.string.settings_ocr_diagnostics_help), style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsPage.TITLE -> AlbumTemplateSettings(settings.albumTitleTemplate) { template ->
                        preferences.setAlbumTitleTemplate(template)
                        back()
                    }
                    SettingsPage.OCR_LANGUAGES -> {
                        Text(stringResource(ocrStatus))
                        Text(stringResource(R.string.settings_ocr_languages_help), style = MaterialTheme.typography.bodySmall)
                        val names = settings.ocrLanguages.map { it.displayName() }
                        Text(stringResource(R.string.settings_ocr_language_order, names.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                        var query by remember { mutableStateOf("") }
                        OutlinedTextField(query, { query = it.take(64) }, modifier = Modifier.fillMaxWidth().testTag("settings-ocr-language-search"),
                            label = { Text(stringResource(R.string.settings_ocr_find_language)) }, singleLine = true)
                        if (settings.ocrLanguages.size >= OcrLanguage.MAX_SELECTED) {
                            Text(stringResource(R.string.settings_ocr_language_limit, OcrLanguage.MAX_SELECTED), style = MaterialTheme.typography.bodySmall)
                        }
                        val options = OcrLanguage.entries.map { it to it.displayName() }.sortedBy { it.second.lowercase(Locale.ROOT) }
                        val term = query.trim()
                        val filtered = options.filter { (language, name) ->
                            name.contains(term, ignoreCase = true) || language.packCode.contains(term, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) Text(stringResource(R.string.settings_ocr_no_languages))
                        filtered.forEach { (language, name) ->
                            val checked = language in settings.ocrLanguages
                            val enabled = language.selectable &&
                                (if (checked) settings.ocrLanguages.size > 1 else settings.ocrLanguages.size < OcrLanguage.MAX_SELECTED)
                            Row(Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget)
                                .testTag("settings-ocr-${language.packCode}").toggleable(checked, enabled = enabled, role = Role.Checkbox,
                                    onValueChange = { preferences.setOcrLanguage(language, it) }),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Checkbox(checked, onCheckedChange = null, enabled = enabled)
                                Column {
                                    Text(name)
                                    if (!language.selectable) Text(stringResource(R.string.settings_ocr_legacy_pack),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun OcrLayout.label(): Int = when (this) {
    OcrLayout.AUTOMATIC -> R.string.ocr_layout_automatic
    OcrLayout.SINGLE_BLOCK -> R.string.ocr_layout_block
    OcrLayout.SPARSE_TEXT -> R.string.ocr_layout_sparse
}

@Composable private fun SettingsHeading(label: Int) {
    Text(stringResource(label), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable private fun SettingsLink(
    destination: SettingsPage, onOpen: (SettingsPage) -> Unit, tag: String = "settings-open-${destination.name.lowercase(Locale.ROOT)}",
) {
    OutlinedButton(onClick = { onOpen(destination) },
        modifier = Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag(tag)) {
        Text(stringResource(destination.title), Modifier.weight(1f))
    }
}

@Composable private fun SettingsRadio(label: Int, selected: Boolean, tag: String, help: Int? = null, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag(tag).selectable(selected, role = Role.RadioButton, onClick = select),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null)
        Column {
            Text(stringResource(label))
            help?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun SettingsSwitch(label: Int, checked: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = change),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable private fun AlbumTemplateSettings(template: AlbumTitleTemplate, save: (AlbumTitleTemplate) -> Unit) {
    var pattern by rememberSaveable { mutableStateOf(template.pattern) }
    val parsed = remember(pattern) { AlbumTitleTemplate.parse(pattern) }
    OutlinedTextField(value = pattern, onValueChange = { if (it.length <= 160) pattern = it },
        modifier = Modifier.fillMaxWidth().testTag("carnet-title-template-field"),
        singleLine = true, isError = parsed == null, label = { Text(stringResource(R.string.carnet_title_pattern)) })
    Text(stringResource(R.string.carnet_title_template_help), style = MaterialTheme.typography.bodySmall)
    if (parsed == null) Text(stringResource(R.string.carnet_title_template_invalid), color = MaterialTheme.colorScheme.error)
    else Text(stringResource(R.string.carnet_title_template_preview, defaultAlbumTitle(template = parsed)))
    TextButton(onClick = { pattern = AlbumTitleTemplate.DEFAULT.pattern }, modifier = Modifier.heightIn(min = AtelierStyle.touchTarget)) {
        Text(stringResource(R.string.carnet_title_template_reset))
    }
    Button(onClick = { parsed?.let(save) }, enabled = parsed != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = AtelierStyle.touchTarget).testTag("carnet-title-template-save")) {
        Text(stringResource(R.string.carnet_title_template_save))
    }
}
