package app.brokoli5191.khrechner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class Choice<T>(val value: T, val label: String)

/** Expressive connected button group for picking exactly one of a few options. */
@Composable
fun <T> ConnectedChoiceGroup(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        choices.forEachIndexed { index, choice ->
            ToggleButton(
                checked = choice.value == selected,
                onCheckedChange = { checked -> if (checked) onSelect(choice.value) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // One step above the card the group sits on, so unselected options stay visible.
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
