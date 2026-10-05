#include "polar_tracker.h"
#include <cmath>
#include <algorithm>

PolarTracker::PolarTracker() : total_angle(0.0f), num_bins(360), patch_size(64), search_window(12) {
    prev_ring.resize(num_bins, 0.0f);
    total_shift_x = 0;
    total_shift_y = 0;
    filtered_shift_x = 0;
    filtered_shift_y = 0;
}

void PolarTracker::reset() {
    total_angle = 0.0f;
    total_shift_x = 0;
    total_shift_y = 0;
    filtered_shift_x = 0;
    filtered_shift_y = 0;
    std::fill(prev_ring.begin(), prev_ring.end(), 0.0f);
    prev_patch.clear();
}

float PolarTracker::process(const uint8_t* y_plane, int width, int height, int stride) {
    std::vector<float> current_ring = extractRing(y_plane, width, height, stride);
    
    float shift_angle = 0.0f;
    bool has_prev = false;
    for (float v : prev_ring) {
        if (v > 0) { has_prev = true; break; }
    }
    
    if (has_prev) {
        shift_angle = computeCorrelationShift(current_ring);
        computeTranslation(y_plane, width, height, stride);
    } else {
        // extract first patch
        int cx = width / 2;
        int cy = height / 2;
        prev_patch.resize(patch_size * patch_size);
        for(int y = 0; y < patch_size; y++) {
            for(int x = 0; x < patch_size; x++) {
                int py = cy - patch_size/2 + y;
                int px = cx - patch_size/2 + x;
                prev_patch[y * patch_size + x] = y_plane[py * stride + px];
            }
        }
    }
    
    prev_ring = current_ring;
    total_angle += shift_angle;
    return total_angle;
}

void PolarTracker::computeTranslation(const uint8_t* y_plane, int width, int height, int stride) {
    int cx = width / 2;
    int cy = height / 2;
    
    int best_dx = 0;
    int best_dy = 0;
    long min_sad = 1e9;
    
    for (int dy = -search_window; dy <= search_window; dy += 2) {
        for (int dx = -search_window; dx <= search_window; dx += 2) {
            long sad = 0;
            for(int y = 0; y < patch_size; y += 2) {
                for(int x = 0; x < patch_size; x += 2) {
                    int py = cy - patch_size/2 + y + dy;
                    int px = cx - patch_size/2 + x + dx;
                    uint8_t curr_p = y_plane[py * stride + px];
                    uint8_t prev_p = prev_patch[y * patch_size + x];
                    sad += std::abs(curr_p - prev_p);
                }
            }
            if (sad < min_sad) {
                min_sad = sad;
                best_dx = dx;
                best_dy = dy;
            }
        }
    }
    
    // Accumulate the physical shift (inverse of camera motion to lock)
    total_shift_x -= best_dx;
    total_shift_y += best_dy; // inverted Y for openGL coords
    
    // Smooth the shifts using a simple low-pass EMA filter
    filtered_shift_x = filtered_shift_x * 0.9f + total_shift_x * 0.1f;
    filtered_shift_y = filtered_shift_y * 0.9f + total_shift_y * 0.1f;
    
    // Update patch for next frame
    for(int y = 0; y < patch_size; y++) {
        for(int x = 0; x < patch_size; x++) {
            int py = cy - patch_size/2 + y;
            int px = cx - patch_size/2 + x;
            prev_patch[y * patch_size + x] = y_plane[py * stride + px];
        }
    }
}

std::vector<float> PolarTracker::extractRing(const uint8_t* y_plane, int width, int height, int stride) {
    std::vector<float> ring(num_bins, 0.0f);
    float cx = width / 2.0f;
    float cy = height / 2.0f;
    float radius = 0.35f * std::min(width, height);
    
    for (int i = 0; i < num_bins; i++) {
        float angle = i * 2.0f * M_PI / num_bins;
        float x = cx + radius * std::cos(angle);
        float y = cy + radius * std::sin(angle);
        
        int ix = std::clamp((int)x, 0, width - 1);
        int iy = std::clamp((int)y, 0, height - 1);
        
        ring[i] = y_plane[iy * stride + ix];
    }
    return ring;
}

float PolarTracker::computeCorrelationShift(const std::vector<float>& current_ring) {
    int max_shift = 45;
    int best_shift = 0;
    float max_corr = -1e9f;
    
    std::vector<float> corr(max_shift * 2 + 1, 0.0f);
    
    for (int s = -max_shift; s <= max_shift; s++) {
        float c = 0;
        for (int i = 0; i < num_bins; i++) {
            int j = (i + s + num_bins) % num_bins;
            c += prev_ring[i] * current_ring[j];
        }
        corr[s + max_shift] = c;
        if (c > max_corr) {
            max_corr = c;
            best_shift = s;
        }
    }
    
    float delta = 0;
    if (best_shift > -max_shift && best_shift < max_shift) {
        float y1 = corr[best_shift - 1 + max_shift];
        float y2 = corr[best_shift + max_shift];
        float y3 = corr[best_shift + 1 + max_shift];
        float denom = (y1 - 2*y2 + y3);
        if (denom != 0) {
            delta = 0.5f * (y1 - y3) / denom;
        }
    }
    
    float exact_shift = best_shift + delta;
    return exact_shift * (2.0f * M_PI / num_bins);
}
