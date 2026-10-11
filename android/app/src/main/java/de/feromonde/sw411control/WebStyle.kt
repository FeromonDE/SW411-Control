package de.feromonde.sw411control

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal val Bg = Color(0xFF071019)
internal val TextPrimary = Color(0xFFF4F7FB)
internal val Muted = Color(0xFF8FA0B5)
internal val Line = Color(0x2BA7BED6)
internal val Cyan = Color(0xFF22D3EE)
internal val Cyan2 = Color(0xFF06B6D4)
internal val Green = Color(0xFF22E39A)
internal val Red = Color(0xFFFF6B76)
internal val Amber = Color(0xFFF8C45C)
internal val PanelBrush = Brush.linearGradient(listOf(Color(0xED192633), Color(0xE60D1822)))
internal val TileBrush = Brush.linearGradient(listOf(Color(0xF21B2937), Color(0xF20F1A24)))
internal val CyanBrush = Brush.horizontalGradient(listOf(Cyan2, Cyan))

@Composable
internal fun WebIcon(@DrawableRes resource: Int, size: Dp = 27.dp, tint: Color = TextPrimary) {
    Icon(painterResource(resource), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
internal fun WebPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().shadow(18.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = .30f), spotColor = Color.Black.copy(alpha = .30f))
        .clip(shape).background(PanelBrush).border(BorderStroke(1.dp, Line), shape), content = content)
}

@Composable
internal fun WebTile(
    modifier: Modifier = Modifier, enabled: Boolean = true, border: Color = Line,
    brush: Brush = TileBrush, radius: Dp = 18.dp, onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "webPress")
    val shape = RoundedCornerShape(radius)
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape).background(brush).border(1.dp, border, shape)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center, content = content)
}

/** Reboot and update have the web panel's 1.5-second hold, including its red progress line. */
@Composable
internal fun HoldTile(
    label: String, modifier: Modifier = Modifier, enabled: Boolean, border: Color = Line,
    radius: Dp = 18.dp, onHold: () -> Unit, onShortPress: () -> Unit,
    onAccessibleClick: () -> Unit, content: @Composable BoxScope.() -> Unit
) {
    val progress = remember { Animatable(0f) }
    val hold by rememberUpdatedState(onHold)
    val shortPress by rememberUpdatedState(onShortPress)
    var fired by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "holdPress")
    val shape = RoundedCornerShape(radius)
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .background(TileBrush).border(1.dp, border, shape)
        .semantics {
            contentDescription = label
            role = Role.Button
            this.onClick(label = "Подтвердить $label") {
                if (enabled) onAccessibleClick()
                enabled
            }
        }.pointerInput(enabled) {
            if (enabled) detectTapGestures(
                onPress = {
                    fired = false
                    pressed = true
                    coroutineScope {
                        val job = launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(1500))
                            fired = true
                            hold()
                        }
                        try { tryAwaitRelease() } finally {
                            job.cancel()
                            pressed = false
                            progress.snapTo(0f)
                        }
                    }
                },
                onTap = { if (!fired) shortPress() })
        }.drawBehind {
            if (progress.value > 0f) drawRect(Red,
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 3.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width * progress.value, 3.dp.toPx()))
        }, contentAlignment = Alignment.Center, content = content)
}

@Composable
internal fun WebSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val offset by animateDpAsState(if (checked) 22.dp else 0.dp, tween(200), label = "webSwitch")
    Box(Modifier.size(52.dp, 30.dp).clip(CircleShape)
        .background(if (checked) CyanBrush else Brush.horizontalGradient(listOf(Color(0xFF263444), Color(0xFF263444))))
        .semantics { contentDescription = label }
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Box(Modifier.padding(3.dp).offset(x = offset).size(24.dp)
            .shadow(3.dp, CircleShape).background(Color(0xFFEEF4FA), CircleShape))
    }
}

@Composable
internal fun WebBadge(text: String) {
    Text(text, color = Color(0xFF91A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(Color(0xFF1B2835)).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
internal fun SheetButton(text: String, primary: Boolean = false, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val brush = if (primary) CyanBrush else Brush.horizontalGradient(listOf(Color(0xFF1B2936), Color(0xFF1B2936)))
    WebTile(modifier.heightIn(min = 44.dp), enabled, border = Color.Transparent, brush = brush,
        radius = 14.dp, onClick = onClick) {
        Text(text, color = if (primary) Color(0xFF03232A) else Color(0xFFC9D4DF),
            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(12.dp))
    }
}
