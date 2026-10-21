package io.crashbox.passage

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.crashbox.passage.otp.Totp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/** The current code of a one-time password, with a countdown and a copy button. */
@Composable
fun OtpCode(uri: String, onCopy: (String) -> Unit) {
  val totp = remember(uri) { runCatching { Totp.parse(uri) } }
  totp.exceptionOrNull()?.let {
    Text("Invalid one-time password: ${it.message}", color = MaterialTheme.colorScheme.error)
    return
  }
  val otp = totp.getOrThrow()
  var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
  LaunchedEffect(Unit) {
    while (true) {
      delay(1000 - System.currentTimeMillis() % 1000)
      now = System.currentTimeMillis() / 1000
    }
  }
  val code = otp.code(now)
  val remaining = otp.secondsRemaining(now)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("One-time password · ${otp.label}", style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(grouped(code), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
      Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { remaining.toFloat() / otp.period }, modifier = Modifier.size(36.dp))
        Text("$remaining", style = MaterialTheme.typography.labelSmall)
      }
    }
    Button(onClick = { onCopy(code) }) { Text("Copy code") }
  }
}

/** "123456" -> "123 456", "12345678" -> "1234 5678" */
private fun grouped(code: String): String = code.substring(0, code.length / 2) + " " + code.substring(code.length / 2)

/**
 * A full-screen camera view returning the first QR code it reads. Asks for
 * the camera permission first; [onCancel] if it is denied, or on back.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var granted by remember {
    mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
  }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    granted = ok
    if (!ok) onCancel()
  }
  LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
  BackHandler(onBack = onCancel)
  if (!granted) return

  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val found = remember { AtomicBoolean(false) }
  DisposableEffect(Unit) {
    onDispose {
      ProcessCameraProvider.getInstance(context).get().unbindAll()
      analysisExecutor.shutdown()
    }
  }
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        val previewView = PreviewView(ctx)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
          val provider = providerFuture.get()
          val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          analysis.setAnalyzer(analysisExecutor) { image ->
            val text = image.use(::decodeQr)
            if (text != null && found.compareAndSet(false, true)) {
              ContextCompat.getMainExecutor(ctx).execute { onResult(text) }
            }
          }
          provider.unbindAll()
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        previewView
      },
    )
    Column(
      Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Point the camera at the QR code", color = Color.White)
      TextButton(onClick = onCancel) { Text("Cancel") }
    }
  }
}

private val qrHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

/** Decodes a QR code from the luminance (Y) plane of a camera frame, if there is one. */
private fun decodeQr(image: ImageProxy): String? {
  val plane = image.planes[0]
  val rowStride = plane.rowStride
  val data = ByteArray(rowStride * image.height)
  plane.buffer.rewind()
  plane.buffer.get(data, 0, minOf(data.size, plane.buffer.remaining()))
  val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
  return try {
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), qrHints).text
  } catch (e: ReaderException) {
    null
  }
}
