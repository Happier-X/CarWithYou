package com.carwithyou.sender.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val Green = Color(0xFF3DDC84)

@Composable
fun CarSenderTheme(content: @Composable () -> Unit) {
    val controller = remember {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.MonetDark,
            keyColor = Green,
            isDark = true
        )
    }
    MiuixTheme(controller = controller, content = content)
}
