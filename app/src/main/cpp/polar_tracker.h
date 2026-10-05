#pragma once
#include <vector>
#include <cstdint>

class PolarTracker {
public:
    PolarTracker();
    float process(const uint8_t* y_plane, int width, int height, int stride);
    void reset();
    float getTotalAngle() const { return total_angle; }
    float getShiftX() const { return filtered_shift_x; }
    float getShiftY() const { return filtered_shift_y; }

private:
    std::vector<float> extractRing(const uint8_t* y_plane, int width, int height, int stride);
    float computeCorrelationShift(const std::vector<float>& current_ring);
    void computeTranslation(const uint8_t* y_plane, int width, int height, int stride);
    
    std::vector<float> prev_ring;
    std::vector<uint8_t> prev_patch;
    
    float total_angle;
    
    // Translation tracking
    float total_shift_x;
    float total_shift_y;
    float filtered_shift_x;
    float filtered_shift_y;
    
    int num_bins;
    int patch_size;
    int search_window;
};
