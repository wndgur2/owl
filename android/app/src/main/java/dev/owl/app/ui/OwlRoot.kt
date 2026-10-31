package dev.owl.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.owl.app.AppContainer
import dev.owl.app.R

/** Placeholder; replaced by the navigation graph. */
@Composable
fun OwlRoot(container: AppContainer) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.app_name))
            }
        }
    }
}
