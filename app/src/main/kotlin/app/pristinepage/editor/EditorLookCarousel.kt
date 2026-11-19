package app.pristinepage.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pristinepage.R
import app.pristinepage.core.pipeline.Recipe

internal enum class EditorLookCard(val id: String, val label: Int, val sample: Int) {
    NONE("none", R.string.editor_look_none, R.drawable.editor_look_sample_none),
    PAPER("paper", R.string.editor_look_paper, R.drawable.editor_look_sample_paper),
    NATURAL("natural", R.string.editor_look_natural, R.drawable.editor_look_sample_natural),
    GRAY("gray", R.string.editor_look_gray, R.drawable.editor_look_sample_gray),
    BINARY("binary", R.string.editor_look_binary, R.drawable.editor_look_sample_binary),
}

@Composable
internal fun EditorLookCarousel(selected: EditorLookCard?, enabled: Boolean, onChooseLook: (String) -> Unit) {
    // Only the selected recipe renders the user's page. These bundled examples are inert and never part of a preview request.
    Row(
        modifier = Modifier.fillMaxWidth().testTag("editor-looks-row").horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (card in EditorLookCard.entries) {
            val isSelected = selected == card
            val colors = MaterialTheme.colorScheme
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) colors.secondaryContainer else colors.surfaceVariant,
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.primary else colors.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.width(124.dp)
                        .testTag("editor-look-${card.id}")
                        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onChooseLook(card.id) })
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Image(painterResource(card.sample), contentDescription = null, modifier = Modifier.fillMaxWidth().height(84.dp))
                    Text(stringResource(card.label), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

internal fun Recipe.matchingLook(): EditorLookCard? =
    EditorLookCard.entries.firstOrNull { EditorLooks.select(this, it.id) == this }
