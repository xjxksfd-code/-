package com.autumn.douyin.liquidglass.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.util.lerp
import com.autumn.douyin.liquidglass.settings.ModuleSettingsStore
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
private val SettingsBackgroundColor = Color(0xFF070A10)
private val SettingsPrimaryText = Color(0xFFF4F7FB)
private val SettingsSecondaryText = Color(0xFF9BA7B8)
private val SettingsAccent = Color(0xFF34C759)
private val SettingsBlue = Color(0xFF0091FF)
private val SettingsTrackOff = Color(0xFF787880).copy(alpha = 0.38f)
private val SettingsPanelBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.14f),
        Color.White.copy(alpha = 0.07f),
        Color.Black.copy(alpha = 0.12f),
    ),
)
private val SettingsButtonBrush = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.20f),
        Color.White.copy(alpha = 0.08f),
    ),
)
private val SettingsKnobBrush = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.96f),
        Color(0xFFD8E2EF),
    ),
)

@Composable
fun LiquidSettingsScreen(
    versionText: String,
    rootStatus: String,
    daemonStatus: String,
    captureStatus: String,
    actionStatus: String,
    glassBarEnabled: Boolean,
    controlAvoidanceEnabled: Boolean,
    dynamicBackdropEnabled: Boolean,
    diagnosticLoggingEnabled: Boolean,
    framePeriodMillis: Int,
    captureWidth: Int,
    onGlassBarChange: (Boolean) -> Unit,
    onControlAvoidanceChange: (Boolean) -> Unit,
    onDynamicBackdropChange: (Boolean) -> Unit,
    onDiagnosticLoggingChange: (Boolean) -> Unit,
    onFramePeriodChange: (Int) -> Unit,
    onCaptureWidthChange: (Int) -> Unit,
    onRestartDouyin: () -> Unit,
    onRestartDaemon: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = "Douyin Liquid Glass",
            color = SettingsPrimaryText,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = versionText,
            color = SettingsSecondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(18.dp))

        SettingsPanel(
            title = "效果",
        ) {
            SettingsRow(
                icon = Icons.Rounded.Home,
                title = "玻璃栏",
                subtitle = if (glassBarEnabled) "替换抖音原生底栏" else "关闭后重启抖音生效",
                enabled = true,
                selected = glassBarEnabled,
                onSelected = onGlassBarChange,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.Star,
                title = "控件上移",
                subtitle = "让底部控件避开玻璃栏",
                enabled = glassBarEnabled,
                selected = controlAvoidanceEnabled && glassBarEnabled,
                onSelected = onControlAvoidanceChange,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.Search,
                title = "动态背景采集",
                subtitle = if (dynamicBackdropEnabled) "实时采集屏幕背景" else "关闭后使用静态磨砂",
                enabled = glassBarEnabled,
                selected = dynamicBackdropEnabled && glassBarEnabled,
                onSelected = onDynamicBackdropChange,
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsPanel(
            title = "性能",
        ) {
            SettingsSliderRow(
                icon = Icons.Rounded.Speed,
                title = "刷新间隔",
                valueFormatter = { "${it}ms" },
                subtitleProvider = ::framePeriodSubtitle,
                choices = ModuleSettingsStore.FramePeriodChoices,
                selectedValue = framePeriodMillis,
                enabled = glassBarEnabled && dynamicBackdropEnabled,
                onValueFinished = onFramePeriodChange,
            )
            SettingsDivider()
            SettingsSliderRow(
                icon = Icons.Rounded.Image,
                title = "采集分辨率",
                valueFormatter = { "${it}px" },
                subtitleProvider = ::captureWidthSubtitle,
                choices = ModuleSettingsStore.CaptureWidthChoices,
                selectedValue = captureWidth,
                enabled = glassBarEnabled && dynamicBackdropEnabled,
                onValueFinished = onCaptureWidthChange,
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsPanel(
            title = "诊断",
        ) {
            SettingsRow(
                icon = Icons.Rounded.Info,
                title = "诊断日志",
                subtitle = if (diagnosticLoggingEnabled) "正在记录运行日志" else "默认保持静默",
                enabled = true,
                selected = diagnosticLoggingEnabled,
                onSelected = onDiagnosticLoggingChange,
            )
        }

        Spacer(Modifier.height(14.dp))
        SettingsPanel(
            title = "状态",
        ) {
            StatusRow("Root 权限", rootStatus)
            SettingsDivider()
            StatusRow("采集支持", captureStatus)
            SettingsDivider()
            StatusRow("Daemon", daemonStatus)
            SettingsDivider()
            StatusRow("操作", actionStatus)
        }

        Spacer(Modifier.height(18.dp))
        ActionButtons(
            onRestartDouyin = onRestartDouyin,
            onRestartDaemon = onRestartDaemon,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}

private fun framePeriodSubtitle(value: Int): String = when (value) {
    8 -> "约125fps · 极致流畅"
    11 -> "约90fps · 高刷设备"
    17 -> "约60fps · 推荐"
    22 -> "约45fps · 均衡"
    33 -> "约30fps · 省电"
    else -> "自定义"
}

private fun captureWidthSubtitle(value: Int): String = when (value) {
    320 -> "省电优先"
    400 -> "偏省电"
    480 -> "均衡 · 推荐"
    560 -> "偏清晰"
    640 -> "清晰 · 耗电"
    else -> "自定义"
}

@Composable
private fun SettingsPanel(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SettingsPanelBrush, RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = SettingsSecondaryText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SettingsPrimaryText.copy(alpha = 0.08f)),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    enabled: Boolean,
    selected: Boolean,
    onSelected: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .alpha(if (enabled) 1f else 0.42f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SettingsBlue,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = SettingsPrimaryText,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = SettingsSecondaryText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        LiquidSettingsToggle(
            selected = selected,
            enabled = enabled,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun SettingsSliderRow(
    icon: ImageVector,
    title: String,
    valueFormatter: (Int) -> String,
    subtitleProvider: (Int) -> String,
    choices: List<Int>,
    selectedValue: Int,
    enabled: Boolean,
    onValueFinished: (Int) -> Unit,
) {
    var selectedIndex by remember(selectedValue) {
        mutableIntStateOf(choices.indexOfFirst { it == selectedValue }.takeIf { it >= 0 } ?: 0)
    }
    val selectedChoice = choices.getOrElse(selectedIndex) { selectedValue }
    val valueLabel = valueFormatter(selectedChoice)
    val subtitle = subtitleProvider(selectedChoice)
    fun commitSelection() {
        val value = choices.getOrNull(selectedIndex) ?: return
        if (value != selectedValue) onValueFinished(value)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title $valueLabel"
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SettingsBlue,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    color = SettingsPrimaryText,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = valueLabel,
                    color = SettingsPrimaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = SettingsSecondaryText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "$title $valueLabel"
                    },
                value = selectedIndex.toFloat(),
                onValueChange = { selectedIndex = Math.round(it).toInt() },
                onValueChangeFinished = ::commitSelection,
                valueRange = 0f..(choices.size - 1f),
                steps = choices.size - 2,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = SettingsPrimaryText,
                    activeTrackColor = SettingsBlue,
                    inactiveTrackColor = SettingsTrackOff,
                    disabledThumbColor = SettingsSecondaryText.copy(alpha = 0.42f),
                    disabledActiveTrackColor = SettingsSecondaryText.copy(alpha = 0.32f),
                    disabledInactiveTrackColor = SettingsTrackOff.copy(alpha = 0.60f),
                ),
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = SettingsSecondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Text(
            text = value,
            color = SettingsPrimaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionButtons(
    onRestartDouyin: () -> Unit,
    onRestartDaemon: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassActionButton(
            icon = Icons.Rounded.Close,
            text = "强停",
            onClick = onRestartDouyin,
            modifier = Modifier.weight(1f),
        )
        GlassActionButton(
            icon = Icons.Rounded.Refresh,
            text = "重启",
            onClick = onRestartDaemon,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    GlassActionButton(
        icon = Icons.Rounded.Share,
        text = "导出诊断",
        onClick = onExportDiagnostics,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GlassActionButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "$text-scale",
    )
    Box(
        modifier = modifier
            .height(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .background(SettingsButtonBrush, CircleShape)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SettingsPrimaryText,
            )
            Text(
                text = text,
                color = SettingsPrimaryText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LiquidSettingsToggle(
    selected: Boolean,
    enabled: Boolean,
    onSelected: (Boolean) -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "settings-toggle-progress",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "settings-toggle-scale",
    )
    Box(
        modifier = Modifier.size(60.dp, 30.dp).alpha(if (enabled) 1f else 0.42f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .drawBehind {
                    drawRect(lerp(SettingsTrackOff, SettingsAccent, progress))
                },
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(36.dp, 26.dp)
                .graphicsLayer {
                    translationX = lerp(2.dp.toPx(), 22.dp.toPx(), progress)
                    scaleX = scale
                    scaleY = scale
                }
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = interactionSource,
                    indication = null,
                    onValueChange = onSelected,
                )
                .background(SettingsKnobBrush, CircleShape)
                .border(
                    width = 1.dp,
                    color = Color.Black.copy(alpha = 0.08f),
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
fun LiquidSettingsBackdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBackgroundColor)
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF12203A),
                            Color(0xFF0A121D),
                            Color(0xFF101A18),
                        ),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(
                            size.width,
                            size.height,
                        ),
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1B3A5C).copy(alpha = 0.32f),
                            Color.Transparent,
                            Color(0xFF177A67).copy(alpha = 0.20f),
                        ),
                    ),
                )
            },
    ) {
        content()
    }
}
