package app.brokoli5191.khrechner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.brokoli5191.khrechner.R
import app.brokoli5191.khrechner.data.InstallFailure

/** Why the scanner module could not be downloaded, in words the user can act on. */
@Composable
fun installFailureMessage(reason: InstallFailure, code: Int): String = when (reason) {
    InstallFailure.METERED_NETWORK -> stringResource(R.string.scanner_install_failed_metered)
    InstallFailure.NO_STORAGE -> stringResource(R.string.scanner_install_failed_storage)
    InstallFailure.OTHER -> stringResource(R.string.scanner_install_failed_other, code)
}
