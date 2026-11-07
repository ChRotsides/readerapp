package com.readerapp.ui.favorites

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The star toggle used on the novel, work, library and search-result screens. */
@Composable
fun FavoriteStar(isFavorite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
