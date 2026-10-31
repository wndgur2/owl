package dev.owl.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.owl.app.R

/** `viewModel()` with a one-line factory, so ViewModels take plain constructor arguments (manual DI). */
@Composable
inline fun <reified VM : ViewModel> owlViewModel(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** The pendant glyph from the design (cord + round body), used for the device tab and cards. */
@Composable
fun PendantIcon(modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.LocalContentColor.current) {
    Icon(painterResource(R.drawable.ic_pendant), contentDescription = null, modifier = modifier, tint = tint)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = actions,
        colors = owlTopAppBarColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun owlTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

/** Small primary-coloured section title ("보관과 위치", "동기화"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** M3 list item laid out like the design rows: icon · headline/supporting · trailing. */
@Composable
fun OwlListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    minHeight: Dp = if (subtitle != null) 72.dp else 56.dp,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .defaultMinSize(minHeight = minHeight)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun OwlDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Tonal hint box with an info icon (Onboarding Samsung hint, Retention hint). */
@Composable
fun HintCard(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Centered icon + title + body, for empty lists. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
