package com.carwithyou.sender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DesktopScreen(
    hint: String,
    onNav: () -> Unit,
    onMusic: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(hint, color = MiuixTheme.colorScheme.onBackgroundVariant, fontSize = 18.sp)
            Spacer(Modifier.padding(top = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onNav,
                    modifier = Modifier.weight(2f).fillMaxSize(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("打开导航", fontSize = 28.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
                Button(
                    onClick = onMusic,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("打开音乐", fontSize = 28.sp, color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
