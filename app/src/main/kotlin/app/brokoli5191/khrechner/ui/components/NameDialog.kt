package app.brokoli5191.khrechner.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.SavedEntry

/** Asks for the name of a saved calculation. Opens with the field focused and the keyboard up. */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
) {
    val state = rememberTextFieldState(initialName, TextRange(0, initialName.length))
    val focusRequester = remember { FocusRequester() }
    val name = state.text.toString().trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.name_placeholder)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.name_counter, state.text.length, SavedEntry.MAX_NAME_LENGTH),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                },
                inputTransformation = InputTransformation.maxLengthTrim(SavedEntry.MAX_NAME_LENGTH),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { if (name.isNotEmpty()) onConfirm(name) },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    LaunchedEffect(focusRequester) {
        // The dialog window has to be attached before it can take focus and raise the keyboard.
        withFrameNanos { }
        focusRequester.requestFocus()
    }
}
