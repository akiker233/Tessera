package com.tessera.otp.feature.scan

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 内嵌式相机二维码预览：CameraX 预览 + ML Kit 逐帧识别。
 *
 * 识别到第一个二维码后即回调 [onScanned] 并停止重复上报（handled 闸门），
 * 避免同一码在连续帧里触发多次导入。
 */
@Composable
fun QrCameraPreview(
    modifier: Modifier = Modifier,
    onScanned: (String) -> Unit
) {
    var granted by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

    Box(modifier = modifier.background(Color.Black)) {
        when {
            granted -> CameraAnalysis(onScanned = onScanned)
            denied -> PermissionDenied()
        }
    }
}

@Composable
private fun PermissionDenied() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要相机权限才能扫描二维码",
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        }) { Text("去设置") }
    }
}

@Composable
private fun CameraAnalysis(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val handled = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var bindError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        // 刚授予相机权限时相机服务可能尚未就绪，getInstance 会瞬失败；重试数次再报错
        var cameraProvider: ProcessCameraProvider? = null
        var attempt = 0
        while (cameraProvider == null && attempt < 4) {
            cameraProvider = runCatching {
                suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
                }
            }.getOrNull()
            if (cameraProvider == null) {
                attempt++
                delay(400)
            }
        }
        if (cameraProvider == null) {
            bindError = "无法启动相机"
            return@LaunchedEffect
        }
        provider = cameraProvider

        val barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            val media = imageProxy.image
            if (media == null || handled.get()) {
                imageProxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                    if (raw != null && handled.compareAndSet(false, true)) onScanned(raw)
                }
                .addOnCompleteListener { imageProxy.close() }
        }

        var bound = false
        var bindAttempt = 0
        while (!bound && bindAttempt < 3) {
            bound = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.isSuccess
            if (!bound) {
                bindAttempt++
                delay(400)
            }
        }
        if (!bound) bindError = "无法打开后置相机"
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view -> previewView = view }
    )

    bindError?.let { message ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
