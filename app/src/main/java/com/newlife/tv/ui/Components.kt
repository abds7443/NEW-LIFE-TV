package com.newlife.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage

object Palette {
    val Bg = Color(0xFF070B16)
    val Surface = Color(0xFF101828)
    val Surface2 = Color(0xFF1C2638)
    val Accent = Color(0xFF00D1B2)
    val Gold = Color(0xFFFFB547)
    val Text2 = Color(0xFF9AA4B8)
    val Danger = Color(0xFFFF5A5F)
    val Rec = Color(0xFFFF3B3B)
}

@Composable
fun NewLifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent, onPrimary = Color.Black,
            secondary = Palette.Gold, onSecondary = Color.Black,
            background = Palette.Bg, onBackground = Color.White,
            surface = Palette.Surface, onSurface = Color.White,
            surfaceVariant = Palette.Surface2, onSurfaceVariant = Color.White,
            secondaryContainer = Palette.Surface2, onSecondaryContainer = Color.White,
            error = Palette.Danger,
        ),
        content = content,
    )
}

/** Gold ring around the focused element: essential for remote-control (D-pad) navigation. */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(2.dp, if (focused) Palette.Gold else Color.Transparent, shape)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvClick(
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    return this
        .onFocusChanged {
            val f = it.isFocused || it.hasFocus
            if (f && !focused) onFocus?.invoke()
            focused = f
        }
        .clip(shape)
        .background(if (focused) Palette.Surface2 else if (selected) Palette.Surface2.copy(alpha = 0.6f) else Color.Transparent)
        .border(2.dp, if (focused) Palette.Gold else Color.Transparent, shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}

@Composable
fun NButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = when {
        danger -> ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Color.White)
        primary -> ButtonDefaults.buttonColors()
        else -> ButtonDefaults.buttonColors(containerColor = Palette.Surface2, contentColor = Color.White)
    }
    Button(
        onClick = onClick,
        modifier = modifier.focusRing(),
        enabled = enabled,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, fontSize = 14.sp)
    }
}

@Composable
fun Logo(url: String, size: Dp, modifier: Modifier = Modifier, fallback: ImageVector = Icons.Filled.Tv) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Palette.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(fallback, contentDescription = null, tint = Palette.Text2, modifier = Modifier.size(size * 0.5f))
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    isError: Boolean = false,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, color = Palette.Text2) }) else null,
        singleLine = singleLine,
        isError = isError,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.focusRing(RoundedCornerShape(6.dp)),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(vertical = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
}

@Composable
fun Badge(text: String, color: Color = Palette.Accent) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Palette.Text2, modifier = Modifier.width(160.dp))
        Text(value, color = Color.White)
    }
}
