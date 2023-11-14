package com.example.sportapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import java.nio.ByteBuffer
import android.Manifest
import android.media.ImageReader
import android.graphics.ImageFormat
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var previewSize: Size? = null
    private var mPrevFrame: Mat? = null
    private val motionThreshold: Int = 500
    private lateinit var imageProcessingThread: HandlerThread
    private lateinit var imageProcessingHandler: Handler

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "Unable to load OpenCV!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        textureView = findViewById(R.id.textureView)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            setupCamera()
            openCamera()
        }
        imageProcessingThread = HandlerThread("ImageProcessingThread")
        imageProcessingThread.start()
        imageProcessingHandler = Handler(imageProcessingThread.looper)
    }

    private fun setupCamera() {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                previewSize = streamConfigurationMap?.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull()
                this.cameraId = cameraId
                break
            }
        }
    }

    private fun openCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun onOpened(cameraDevice: CameraDevice) {
                    // Use the cameraDevice here
                    startPreview(cameraDevice)
                }

                override fun onDisconnected(cameraDevice: CameraDevice) {
                    cameraDevice.close()
                }

                override fun onError(cameraDevice: CameraDevice, error: Int) {
                    cameraDevice.close()
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun startPreview(cameraDevice: CameraDevice) {
        val imageReader = ImageReader.newInstance(previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val processedFrame = processImage(image)
                    displayProcessedFrame(processedFrame)
                    processedFrame.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image?.close()
            }
        }, imageProcessingHandler)

        val previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader.surface)
        }

        val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                session.setRepeatingRequest(previewRequestBuilder.build(), null, null)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(this@MainActivity, "Failed to configure camera.", Toast.LENGTH_SHORT).show()
            }
        }

        // Using the new method with SessionConfiguration
        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(imageReader.surface)),
            mainExecutor, // mainExecutor is available on API level 28 and above
            sessionStateCallback
        )

        cameraDevice.createCaptureSession(sessionConfiguration)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera()
                openCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processImage(image: Image): Mat {
        val yuvMat = imageToMat(image)
        val outMat = Mat()

        // Ensure correct conversion from YUV to grayscale
        Imgproc.cvtColor(yuvMat, outMat, Imgproc.COLOR_YUV2GRAY_420)
        yuvMat.release()

        return outMat
    }

    private fun imageToMat(image: Image): Mat {
        val planes = image.planes
        val yPlane = planes[0].buffer
        val uPlane = planes[1].buffer
        val vPlane = planes[2].buffer

        val ySize = yPlane.remaining()
        val uSize = uPlane.remaining()
        val vSize = vPlane.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yPlane.get(nv21, 0, ySize)
        uPlane.get(nv21, ySize, uSize)
        vPlane.get(nv21, ySize + uSize, vSize)

        val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)

        return yuvMat
    }

    private fun displayProcessedFrame(mat: Mat) {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)

        runOnUiThread {
            val canvas: Canvas? = textureView.lockCanvas()
            try {
                canvas?.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                if (canvas != null) {
                    textureView.unlockCanvasAndPost(canvas)
                }
                bitmap.recycle() // Recycle the bitmap to free memory
            }
        }
    }
}
