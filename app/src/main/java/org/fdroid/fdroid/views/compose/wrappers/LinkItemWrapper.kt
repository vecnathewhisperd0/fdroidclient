package org.fdroid.fdroid.views.compose.wrappers

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.fdroid.fdroid.R
import org.fdroid.fdroid.compose.ComposeUtils
import org.fdroid.fdroid.views.compose.components.LinkListItem

fun createComposeLinkListItem(
    parent: ViewGroup,
    @StringRes resIdText: Int,
    @DrawableRes resIdDrawable: Int,
    url: String,
    formatArg: String? = null,
): ComposeView {
    val composeView =
        LayoutInflater.from(parent.context)
            .inflate(R.layout.app_details2_link_item_compose, parent, false) as ComposeView
    composeView.apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ComposeUtils.FDroidTheme {
                LinkListItem(
                    modifier = Modifier,
                    resIdText = resIdText,
                    resIdDrawable = resIdDrawable,
                    url = url,
                    formatArg = formatArg
                )

            }
        }
    }
    return composeView
}
