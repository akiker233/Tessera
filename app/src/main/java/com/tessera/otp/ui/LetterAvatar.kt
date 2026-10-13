package com.tessera.otp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/** 首字母头像配色：按 seed 稳定取色，避免每次渲染变色。 */
fun letterColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF3B5BDB), Color(0xFF0B7285), Color(0xFF2B8A3E),
        Color(0xFF9C36B5), Color(0xFFC92A2A), Color(0xFFE8590C), Color(0xFF495057)
    )
    val index = (seed.hashCode().toLong() and 0x7FFFFFFF).toInt() % palette.size
    return palette[index]
}

/**
 * 图标库里没有匹配时的兜底头像：名称首字母 + 稳定配色。
 *
 * 首页列表与编辑页的图标按钮共用，保证同一账号在两处显示同一个头像。
 */
@Composable
fun LetterAvatar(
    seed: String,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(letterColor(seed)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = seed.trim().take(1).uppercase().ifBlank { "?" },
            color = Color.White,
            style = textStyle
        )
    }
}
