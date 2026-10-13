package com.tessera.otp.feature.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tessera.otp.domain.Base32
import com.tessera.otp.domain.CodeFormatter
import com.tessera.otp.domain.OtpAlgorithm
import com.tessera.otp.domain.OtpSpec
import com.tessera.otp.domain.TotpEngine
import com.tessera.otp.feature.home.GroupedCode
import com.tessera.otp.importer.ParsedAccount
import com.tessera.otp.ui.LetterAvatar

/**
 * 扫码 / 相册配对成功后的「即将完成」页：展示刚配对条目的实时令牌，
 * 提示用户回服务方重新输入该令牌以完成绑定，并提供一键复制。
 *
 * 令牌直接用扫到的密钥经 [TotpEngine] 实时计算，不依赖列表刷新，保证配对后立即有码可抄。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingCompleteSheet(
    items: List<ParsedAccount>,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val firstCode = remember(items) { currentCode(items.firstOrNull()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "即将完成！",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "要完成与服务的配对，您需要重新输入此令牌。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            items.forEach { account -> PairingTokenCard(account) }
            Button(
                onClick = {
                    val code = firstCode.replace(" ", "")
                    if (code.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(code))
                        android.widget.Toast.makeText(context, "已复制令牌", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("复制令牌") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 单个配对条目卡片：头像 + 名称 + 实时令牌 + 倒计时环。 */
@Composable
private fun PairingTokenCard(account: ParsedAccount) {
    val epoch = System.currentTimeMillis() / 1000
    var code by remember(account) { mutableStateOf(currentCode(account)) }
    var remaining by remember(account) { mutableIntStateOf(TotpEngine.secondsRemaining(account.period, epoch)) }
    LaunchedEffect(account) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val e = System.currentTimeMillis() / 1000
            code = currentCode(account, e)
            remaining = TotpEngine.secondsRemaining(account.period, e)
        }
    }
    val progress = remember(remaining, account.period) {
        mutableFloatStateOf(remaining.toFloat() / account.period.coerceAtLeast(1).toFloat())
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LetterAvatar(seed = account.issuer, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.issuer.ifBlank { account.accountName.ifBlank { "未命名" } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (account.accountName.isNotBlank()) {
                    Text(
                        text = account.accountName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                GroupedCode(
                    text = code,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    gap = (28 / 3).dp
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress.floatValue },
                    modifier = Modifier.size(38.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
                Text(
                    text = remaining.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 用扫到的 Base32 密钥实时算当前令牌（分组格式）。 */
private fun currentCode(account: ParsedAccount?, epoch: Long = System.currentTimeMillis() / 1000): String {
    if (account == null) return ""
    val secret = runCatching { Base32.decode(account.secretBase32) }.getOrNull() ?: return ""
    val spec = OtpSpec(
        secret = secret,
        algorithm = runCatching { OtpAlgorithm.valueOf(account.algorithm.uppercase()) }.getOrDefault(OtpAlgorithm.SHA1),
        digits = account.digits,
        period = account.period,
        isSteam = account.isSteam
    )
    return CodeFormatter.grouped(TotpEngine.generate(spec, epoch))
}
