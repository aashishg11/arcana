package com.aashishgodambe.arcana.feature.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme
import com.aashishgodambe.arcana.ui.theme.Mono
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.concurrent.futures.await
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * The entry to the capture flow (new acquisitions only). Its whole job is to freeze one frame — the AI
 * runs afterward on Review. Per the wireframe: a clean viewfinder (no framing reticle — segmentation
 * handles messy framing), a centre shutter, a close affordance, and a deliberately understated bottom-left
 * barcode fallback whose visual weakness *is* the architectural statement (the image cascade is the star).
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val store: CaptureSessionStore,
) : ViewModel() {
    /** Stash the frozen image frame(s) for the vision cascade, then Review [take]s them. */
    fun stashImage(frames: List<Bitmap>) = store.put(CapturePayload.Image(frames))

    /** Stash a single frame for the barcode path. */
    fun stashBarcode(frame: Bitmap) = store.put(CapturePayload.Barcode(frame))
}

@Composable
fun CameraScreen(
    onClose: () -> Unit,
    onCaptured: () -> Unit,
    vm: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
        // After a denial with no rationale offer, the OS won't prompt again — route to Settings.
        if (!isGranted) {
            permanentlyDenied =
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        }
    }

    // Ask once on first entry if we don't already hold the grant.
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraViewfinder(onClose = onClose, onCaptured = onCaptured, vm = vm)
        } else {
            PermissionRationale(
                permanentlyDenied = permanentlyDenied,
                onGrant = {
                    if (permanentlyDenied) {
                        activity?.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onNotNow = onClose,
            )
        }
    }
}

@Composable
private fun CameraViewfinder(
    onClose: () -> Unit,
    onCaptured: () -> Unit,
    vm: CameraViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    // Close — top-left, over the viewfinder (below the status bar).
    Box(
        Modifier.statusBarsPadding().padding(16.dp).size(40.dp).clip(CircleShape)
            .background(Color(0x66000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) { Text("✕", color = Color.White, fontSize = 18.sp) }

    // Controls — barcode fallback (left, quiet), shutter (centre), balancing spacer (right).
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BarcodeFallback(
                enabled = !capturing,
                modifier = Modifier.width(80.dp),
                onClick = {
                    capturing = true
                    takePhoto(imageCapture, executor) { bitmap ->
                        capturing = false
                        if (bitmap != null) {
                            vm.stashBarcode(bitmap)
                            onCaptured()
                        }
                    }
                },
            )
            Shutter(
                enabled = !capturing,
                onClick = {
                    capturing = true
                    // Burst a few frames so the engine can majority-vote the Pop number when the primary
                    // read is weak (Week-8 32→82 glyph misread). The engine only OCRs beyond the first
                    // frame on a low-confidence read, so a clean capture stays fast.
                    val started = SystemClock.elapsedRealtime()
                    takeBurst(imageCapture, executor, BURST_SIZE) { frames ->
                        capturing = false
                        Log.i("CameraCapture", "burst of ${frames.size} in ${SystemClock.elapsedRealtime() - started}ms")
                        if (frames.isNotEmpty()) {
                            vm.stashImage(frames)
                            onCaptured()
                        }
                    }
                },
            )
            Spacer(Modifier.width(80.dp))
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(74.dp).clip(CircleShape)
            .border(4.dp, Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else 0.5f)))
    }
}

/**
 * The barcode fallback: low contrast, no badge or pulse, never competing with the shutter. Its visual
 * weakness is deliberate — barcode was demoted from a co-equal cascade to a UI fallback so the image-first
 * pipeline stays the star.
 */
@Composable
private fun BarcodeFallback(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("▮▏▮▮▏▮", color = Color.White.copy(alpha = 0.55f), fontSize = 16.sp, letterSpacing = (-1).sp)
        Text("Scan barcode", color = Color.White.copy(alpha = 0.55f), fontFamily = Mono, fontSize = 10.sp)
    }
}

@Composable
private fun PermissionRationale(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onNotNow: () -> Unit,
) {
    val c = ArcanaTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("◉", color = c.iris, fontSize = 44.sp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Arcana needs camera access to identify new items",
            color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            lineHeight = 25.sp,
        )
        if (permanentlyDenied) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Camera access is turned off. Enable it in system settings to continue.",
                color = c.textDim, fontFamily = Mono, fontSize = 12.sp, textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(c.iris)
                .clickable(onClick = onGrant).padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (permanentlyDenied) "Open settings" else "Grant permission",
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Not now",
            color = c.textDim, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onNotNow).padding(8.dp),
        )
    }
}

/** How many frames the shutter captures for the escalation-burst vote (within the plan's 3–5). */
private const val BURST_SIZE = 4

/**
 * Capture [count] stills back-to-back and return the ones that succeeded (order preserved, primary first).
 * Stops early if a shot errors once at least one frame is in hand; returns empty only if the very first
 * shot fails. Sequential because a single [ImageCapture] serializes anyway — fast on a flagship.
 */
private fun takeBurst(
    imageCapture: ImageCapture,
    executor: Executor,
    count: Int,
    onResult: (List<Bitmap>) -> Unit,
) {
    val frames = ArrayList<Bitmap>(count)
    fun captureNext() {
        if (frames.size >= count) {
            onResult(frames)
            return
        }
        takePhoto(imageCapture, executor) { bitmap ->
            if (bitmap != null) frames.add(bitmap)
            if (bitmap == null) onResult(frames) else captureNext()
        }
    }
    captureNext()
}

/**
 * Take one still, convert to an upright [Bitmap], and hand it back on the main thread. Rotation from the
 * sensor's [ImageProxy] is baked in so downstream OCR/segmentation see an upright frame. Returns null on
 * capture error.
 */
private fun takePhoto(
    imageCapture: ImageCapture,
    executor: Executor,
    onResult: (Bitmap?) -> Unit,
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = runCatching {
                    val raw = image.toBitmap()
                    val degrees = image.imageInfo.rotationDegrees
                    if (degrees == 0) {
                        raw
                    } else {
                        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    }
                }.getOrNull()
                image.close()
                onResult(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(null)
            }
        },
    )
}
