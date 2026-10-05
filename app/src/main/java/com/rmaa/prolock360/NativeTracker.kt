package com.rmaa.prolock360

import java.nio.ByteBuffer

class NativeTracker {
    init {
        System.loadLibrary("prolock360")
    }

    external fun initTracker()
    external fun processFrame(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int, dt: Float): Float
    external fun zeroHorizon()
    external fun destroyTracker()
}
