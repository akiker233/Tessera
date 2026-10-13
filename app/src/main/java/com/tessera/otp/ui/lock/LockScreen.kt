package com.tessera.otp.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 解锁页：PIN 输入为主，生物识别为辅。
 *
 * 失败节流由调用方（LockPrefs）提供秒数，这里只负责倒计时显示与禁用输入。
 */
@Composable
fun LockScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onUnlockWithPin: (String) -> Boolean,
    onUnlockWithBiometric: () -> Boolean,
    throttleSecondsProvider: () -> Int = { 0 }
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var throttle by remember { mutableStateOf(throttleSecondsProvider()) }
    var attempts by remember { mutableStateOf(0) }

    LaunchedEffect(throttle) {
        while (throttle > 0) {
            delay(1000L)
            throttle -= 1
        }
    }

    val canSubmit = pin.length >= 4 && throttle == 0

    fun submit() {
        if (!canSubmit) return
        if (onUnlockWithPin(pin)) {
            pin = ""
            error = null
        } else {
            attempts += 1
            error = "PIN 不正确"
            pin = ""
            val wait = throttleSecondsProvider()
            if (wait > 0) throttle = wait
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(text = "已锁定", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (throttle > 0) "尝试次数过多，请等待 " + throttle + " 秒" else "输入 PIN 解锁",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { value ->
                    pin = value.filter(Char::isDigit).take(12)
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PIN") },
                singleLine = true,
                enabled = throttle == 0,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit
            ) { Text("解锁") }

            if (biometricEnabled && biometricAvailable) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onUnlockWithBiometric() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  使用生物识别")
                }
            }
        }
    }
}
