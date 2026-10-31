package dev.owl.app.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dev.owl.app.R
import dev.owl.app.model.DeviceRegistrar
import dev.owl.app.model.SettingsRepository
import dev.owl.app.ui.theme.OwlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

enum class StartDestination { ONBOARDING, MAIN }

class SplashViewModel(private val settings: SettingsRepository, private val registrar: DeviceRegistrar) : ViewModel() {
    /** Onboarding until it was finished once and a pendant is registered. */
    suspend fun destination(): StartDestination {
        val done = settings.onboardingDone.first()
        val name = registrar.registeredName.first()
        return if (done && name != null) StartDestination.MAIN else StartDestination.ONBOARDING
    }
}

@Composable
fun SplashScreen(viewModel: SplashViewModel, onDestination: (StartDestination) -> Unit) {
    LaunchedEffect(viewModel) {
        val start = System.currentTimeMillis()
        val dest = viewModel.destination()
        delay((1_000 - (System.currentTimeMillis() - start)).coerceAtLeast(0))
        onDestination(dest)
    }
    SplashContent()
}

/** App icon + the N8 privacy principle at the bottom. */
@Composable
fun SplashContent() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Adaptive-icon foreground is 108 dp with the visible part in the middle 72 dp, so draw it 1.5x and clip.
            Box(
                Modifier.size(160.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.requiredSize(240.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Outlined.Visibility, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            Text(
                stringResource(R.string.privacy_principle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SplashPreview() {
    OwlTheme { SplashContent() }
}
