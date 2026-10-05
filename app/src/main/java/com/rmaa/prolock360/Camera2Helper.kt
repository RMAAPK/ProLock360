package com.rmaa.prolock360

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface

class Camera2Helper(
    private val context: Context,
    private val glRenderer: GLSurfaceRenderer,
    private val nativeTracker: NativeTracker
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var lastTimestamp = 0L

    fun startCamera() {
        startBackgroundThread()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            glRenderer.onSurfaceTextureReady = { surfaceTexture ->
                openCamera(manager, cameraId, surfaceTexture)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(manager: CameraManager, cameraId: String, surfaceTexture: android.graphics.SurfaceTexture) {
        imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                if (planes.isNotEmpty()) {
                    val yPlane = planes[0].buffer
                    val rowStride = planes[0].rowStride
                    val now = System.currentTimeMillis()
                    val dt = if (lastTimestamp == 0L) 0.033f else (now - lastTimestamp) / 1000f
                    lastTimestamp = now
                    
                    val startCalc = System.currentTimeMillis()
                    val angle = nativeTracker.processFrame(yPlane, image.width, image.height, rowStride, dt)
                    val latency = System.currentTimeMillis() - startCalc
                    
                    (context as MainActivity).updateTelemetry(angle, latency, 0)
                }
                image.close()
            }
        }, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val previewSurface = Surface(surfaceTexture)
                val readerSurface = imageReader!!.surface

                camera.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(previewSurface)
                        builder.addTarget(readerSurface)
                        
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1000000L) // 1ms
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 800)
                        
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, backgroundHandler)
            }
            override fun onDisconnected(camera: CameraDevice) {}
            override fun onError(camera: CameraDevice, error: Int) {}
        }, backgroundHandler)
    }

    fun stopCamera() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}
