package com.rmaa.prolock360

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLSurfaceRenderer(private val glSurfaceView: GLSurfaceView, private val context: Context) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var surfaceTexture: SurfaceTexture? = null
    var surfaceTextureId: Int = 0
    private var isHorizonLockActive = true
    private var angleOffset = 0f
    private var shiftX = 0f
    private var shiftY = 0f
    
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
    
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uMatrix;
        void main() {
          gl_Position = uMatrix * aPosition;
          vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() {
          vec2 centered = vTexCoord - vec2(0.5, 0.5);
          float r = length(centered);
          if (r > 0.5) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); }
          else { gl_FragColor = texture2D(uTexture, vTexCoord); }
        }
    """.trimIndent()

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var matrixHandle: Int = 0
    
    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer
    private val mMatrix = FloatArray(16)
    
    // Photo/Video logic
    @Volatile var takePhotoReq = false
    var photoPath = ""
    
    @Volatile var isRecording = false
    private var encoderSurface: android.opengl.EGLSurface? = null
    private var mediaCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var inputSurface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var videoWidth = 720
    private var videoHeight = 1280
    
    init {
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        val vertices = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertices).position(0)
        
        val texCoords = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texBuffer.put(texCoords).position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        surfaceTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, surfaceTextureId)
        
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        surfaceTexture = SurfaceTexture(surfaceTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")

        onSurfaceTextureReady?.invoke(surfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        videoWidth = width
        videoHeight = height
    }

    private fun drawScene(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        
        Matrix.setIdentityM(mMatrix, 0)
        if (isHorizonLockActive) {
            val degrees = Math.toDegrees(angleOffset.toDouble()).toFloat()
            Matrix.scaleM(mMatrix, 0, 1.3f, 1.3f, 1.0f)
            val normX = shiftX / 640f * 2.0f
            val normY = shiftY / 480f * 2.0f
            Matrix.translateM(mMatrix, 0, normX, normY, 0f)
            Matrix.rotateM(mMatrix, 0, -degrees, 0f, 0f, 1f)
        }
        
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texBuffer)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()
        
        // 1. Draw to screen
        val display = EGL14.eglGetCurrentDisplay()
        val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val contextEgl = EGL14.eglGetCurrentContext()
        
        drawScene(videoWidth, videoHeight)
        
        // Take Photo
        if (takePhotoReq) {
            takePhotoReq = false
            savePhoto(videoWidth, videoHeight, photoPath)
        }
        
        // 2. Draw to video encoder if recording
        if (isRecording && encoderSurface != null) {
            EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, contextEgl)
            drawScene(720, 1280) // Encoder resolution
            
            val timestampNs = surfaceTexture!!.timestamp
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, timestampNs)
            EGL14.eglSwapBuffers(display, encoderSurface)
            
            drainEncoder(false)
            
            // Restore screen surface
            EGL14.eglMakeCurrent(display, drawSurface, readSurface, contextEgl)
        }
    }
    
    fun startRecording(path: String) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        mediaCodec?.start()

        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
        
        // Create EGL window surface for encoder in GL thread later?
        // We must run EGL setup on GL thread.
        glSurfaceView.queueEvent {
            val display = EGL14.eglGetCurrentDisplay()
            val configAttribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, configs.size, numConfigs, 0)
            
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            encoderSurface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, surfaceAttribs, 0)
            isRecording = true
        }
    }

    fun stopRecording() {
        isRecording = false
        glSurfaceView.queueEvent {
            drainEncoder(true)
            if (encoderSurface != null) {
                EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), encoderSurface)
                encoderSurface = null
            }
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            if (muxerStarted) muxer?.stop()
            muxer?.release()
            muxer = null
            inputSurface?.release()
            inputSurface = null
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = mediaCodec ?: return
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break else continue
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw RuntimeException("format changed twice")
                trackIndex = muxer!!.addTrack(encoder.outputFormat)
                muxer!!.start()
                muxerStarted = true
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0) {
                    if (!muxerStarted) throw RuntimeException("muxer hasn't started")
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun savePhoto(width: Int, height: Int, path: String) {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Flip bitmap vertically because OpenGL is upside down
        val matrix = android.graphics.Matrix()
        matrix.preScale(1.0f, -1.0f)
        val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        
        FileOutputStream(path).use { out ->
            flippedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
    }
    
    fun capturePhoto(path: String) {
        photoPath = path
        takePhotoReq = true
        glSurfaceView.requestRender()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        glSurfaceView.requestRender()
    }

    fun setHorizonLock(active: Boolean) { isHorizonLockActive = active }
    fun setAngleOffset(angle: Float) { angleOffset = angle }
    fun setTranslationOffset(x: Float, y: Float) { shiftX = x; shiftY = y }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
