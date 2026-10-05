#pragma once
class KalmanFilter {
public:
    KalmanFilter();
    void reset();
    float update(float measured_angle, float dt);
private:
    float x_est;
    float p_est;
    float q;
    float r;
};
