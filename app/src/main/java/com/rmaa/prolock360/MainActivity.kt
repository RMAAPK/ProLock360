package com.rmaa.prolock360

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    private lateinit var glRenderer: GLSurfaceRenderer
    private lateinit var camera2Helper: Camera2Helper
    private val nativeTracker = NativeTracker()

    private lateinit var tvAngle: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvFps: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAngle = findViewById(R.id.tvAngle)
        tvLatency = findViewById(R.id.tvLatency)
        tvFps = findViewById(R.id.tvFps)

        glRenderer = GLSurfaceRenderer(findViewById(R.id.glSurfaceView), this)
        camera2Helper = Camera2Helper(this, glRenderer, nativeTracker)
        
        nativeTracker.initTracker()

        findViewById<SwitchMaterial>(R.id.swHorizonLock).setOnCheckedChangeListener { _, isChecked ->
            glRenderer.setHorizonLock(isChecked)
        }

        findViewById<Button>(R.id.btnZeroHorizon).setOnClickListener {
            nativeTracker.zeroHorizon()
            glRenderer.setAngleOffset(0f)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            camera2Helper.startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            camera2Helper.startCamera()
        }
    }

    fun updateTelemetry(angle: Float, latency: Long, fps: Int) {
        runOnUiThread {
            tvAngle.text = String.format("Angle: %.1f°", Math.toDegrees(angle.toDouble()))
            tvLatency.text = "Latency: ${latency} ms"
            tvFps.text = "FPS: $fps"
            glRenderer.setAngleOffset(angle)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        camera2Helper.stopCamera()
        nativeTracker.destroyTracker()
    }
}
