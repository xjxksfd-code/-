package com.autumn.douyin.liquidglass.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val LocalColorMode = staticCompositionLocalOf { 0 }

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun DemoMiuixTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        controller = ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            isDark = darkTheme,
        ),
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalColorMode provides if (darkTheme) 2 else 1,
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
            ) {
                content()
            }
        },
    )
}
