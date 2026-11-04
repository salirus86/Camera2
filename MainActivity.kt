package com.example.umnyradar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val modelReady = AtomicBoolean(false)
    @Volatile private var detector: ObjectDetector? = null
    @Volatile private var lastTimestamp = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startModelAndCamera()
            else statusText.text = "Для работы нужно разрешить доступ к камере"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startModelAndCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startModelAndCamera() {
        statusText.text = "Загружаем модель распознавания…"
        Thread {
            try {
                val modelFile = File(filesDir, "efficientdet_lite0.tflite")
                if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                    URL(MODEL_URL).openStream().use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val modelBytes = modelFile.readBytes()
                val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                    .order(ByteOrder.nativeOrder())
                modelBuffer.put(modelBytes)
                modelBuffer.rewind()
                val base = BaseOptions.builder().setModelAssetBuffer(modelBuffer).build()
                val options = ObjectDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.45f)
                    .setMaxResults(30)
                    .build()
                detector = ObjectDetector.createFromOptions(this, options)
                modelReady.set(true)
                runOnUiThread {
                    statusText.text = "Камера активна • распознавание включено"
                    startCamera()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Не удалось загрузить модель. Проверь интернет и перезапусти приложение."
                }
            }
        }.start()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                analyze(image)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                statusText.text = "Не удалось запустить камеру"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!modelReady.get()) return
            val bmp0 = image.toBitmap()
            val bmp = rotateBitmap(bmp0, image.imageInfo.rotationDegrees)
            val mpImage = BitmapImageBuilder(bmp).build()
            val result = detector?.detect(mpImage) ?: return

            val found = mutableListOf<DrawItem>()
            var people = 0
            var transport = 0
            for (detection in result.detections()) {
                val category = detection.categories().firstOrNull() ?: continue
                val label = category.categoryName().lowercase()
                val isPerson = label == "person"
                val isTransport = label in setOf("car", "truck", "bus", "motorcycle", "bicycle")
                if (!isPerson && !isTransport) continue
                if (isPerson) people++ else transport++
                val title = when (label) {
                    "person" -> "ЧЕЛОВЕК"
                    "car" -> "АВТОМОБИЛЬ"
                    "truck" -> "ГРУЗОВИК"
                    "bus" -> "АВТОБУС"
                    "motorcycle" -> "МОТОЦИКЛ"
                    "bicycle" -> "ВЕЛОСИПЕД"
                    else -> label
                } + " ${(category.score() * 100).toInt()}%"
                val b = detection.boundingBox()
                val color = if (isPerson) 0xFF35C759.toInt()
                    else if (label == "truck" || label == "bus") 0xFFFF9500.toInt()
                    else 0xFF168CFF.toInt()
                found.add(DrawItem(RectF(b), title, color))
            }
            runOnUiThread {
                overlay.update(found, bmp.width, bmp.height)
                countText.text = "Люди: $people   Транспорт: $transport"
            }
            bmp.recycle()
            if (bmp0 !== bmp) bmp0.recycle()
        } catch (_: Exception) {
            // Drop a bad frame and continue analyzing the next one.
        } finally {
            image.close()
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    override fun onDestroy() {
        detector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        // MediaPipe EfficientDet-Lite0 model trained on the COCO object categories.
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/metadata/1.tflite"
    }
}
