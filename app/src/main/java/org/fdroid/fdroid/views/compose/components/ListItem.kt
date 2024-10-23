package org.fdroid.fdroid.views.compose.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.fdroid.fdroid.R
import org.fdroid.fdroid.compose.ComposeUtils

private val ContentColor
    @Composable @ReadOnlyComposable get() = MaterialTheme.colors.onSurface
private const val ContentAlpha = 0.8f
private val ContainerHeight = 48.dp
private val HorizontalPadding = 16.dp
private val IconTextPadding = 12.dp

@Composable
private fun RowScope.ListItemContent(
    leadingIcon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    leadingIcon?.invoke()
    if (leadingIcon != null) {
        Spacer(modifier = Modifier.width(IconTextPadding))
    }
    text.invoke()
    Spacer(modifier = Modifier.weight(1f))
    trailingIcon?.invoke()
}

/**
 * See the other overloads for [clickable] and [combinedClickable] list items.
 */
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = ContainerHeight)
            .fillMaxWidth()
            .padding(horizontal = HorizontalPadding)
    ) {
        ListItemContent(leadingIcon, text, trailingIcon)
    }
}


@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = ContainerHeight)
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        ListItemContent(leadingIcon, text, trailingIcon)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
    trailingIcon: (@Composable () -> Unit)? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = ContainerHeight)
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel
            )
            .padding(horizontal = HorizontalPadding)

    ) {
        ListItemContent(leadingIcon, text, trailingIcon)
    }
}

@Composable
fun LinkListItem(
    modifier: Modifier = Modifier,
    @StringRes resIdText: Int,
    @DrawableRes resIdDrawable: Int,
    url: String,
    formatArg: String? = null,
) {
    val text = formatArg?.let { stringResource(resIdText, it) } ?: stringResource(resIdText)
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current
    CompositionLocalProvider(
        LocalContentColor provides ContentColor,
        LocalContentAlpha provides ContentAlpha
    ) {
        ListItem(modifier = modifier, text = {
            Text(text = text, style = MaterialTheme.typography.body2)
        }, leadingIcon = {
            Icon(
                painter = painterResource(id = resIdDrawable),
                contentDescription = null,
            )
        }, onClick = {
            if (url.isNotBlank()) {
                uriHandler.openUri(url)
            }
        }, onLongClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            clipboardManager.setText(AnnotatedString(url))
        }, onLongClickLabel = stringResource(R.string.copy_url))
    }
}

@Preview
@Composable
private fun Preview() {
    ComposeUtils.FDroidTheme {
        Surface {
            LinkListItem(
                resIdText = R.string.menu_source,
                resIdDrawable = R.drawable.ic_source_code,
                url = "https://www.example.com"
            )
        }
    }

}
