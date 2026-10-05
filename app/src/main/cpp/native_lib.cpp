#include <jni.h>
#include "polar_tracker.h"
#include "kalman_filter.h"

PolarTracker tracker;
KalmanFilter kf;

extern "C" JNIEXPORT void JNICALL
Java_com_rmaa_prolock360_NativeTracker_initTracker(JNIEnv* env, jobject /* this */) {
    tracker.reset();
    kf.reset();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_rmaa_prolock360_NativeTracker_processFrame(JNIEnv* env, jobject /* this */, 
        jobject yPlane, jint width, jint height, jint rowStride, jfloat dt) {
    
    uint8_t* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(yPlane));
    if (!buffer) return 0.0f;
    
    float raw_angle = tracker.process(buffer, width, height, rowStride);
    float filtered_angle = kf.update(raw_angle, dt);
    return filtered_angle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rmaa_prolock360_NativeTracker_zeroHorizon(JNIEnv* env, jobject /* this */) {
    tracker.reset();
    kf.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rmaa_prolock360_NativeTracker_destroyTracker(JNIEnv* env, jobject /* this */) {
    // Cleanup if needed
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_rmaa_prolock360_NativeTracker_getShiftX(JNIEnv* env, jobject /* this */) {
    return tracker.getShiftX();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_rmaa_prolock360_NativeTracker_getShiftY(JNIEnv* env, jobject /* this */) {
    return tracker.getShiftY();
}
