#include "kalman_filter.h"
#include <cmath>

KalmanFilter::KalmanFilter() : x_est(0), p_est(1.0f), q(0.01f), r(0.1f) {}

void KalmanFilter::reset() {
    x_est = 0.0f;
    p_est = 1.0f;
}

float KalmanFilter::update(float measured_angle, float dt) {
    // Prediction
    float x_pred = x_est;
    float p_pred = p_est + q * dt;
    
    // Measurement update
    // Unwrap phase
    float diff = measured_angle - x_pred;
    while(diff > M_PI) diff -= 2 * M_PI;
    while(diff < -M_PI) diff += 2 * M_PI;
    measured_angle = x_pred + diff;
    
    float k = p_pred / (p_pred + r);
    x_est = x_pred + k * (measured_angle - x_pred);
    p_est = (1.0f - k) * p_pred;
    
    return x_est;
}
