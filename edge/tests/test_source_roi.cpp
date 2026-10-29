#include "ksi_safety/observation/source_roi.hpp"

#include <cstdint>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

using namespace ksi::safety;
#define CHECK(c) do { if (!(c)) { std::cerr << __LINE__ << ": " #c "\n"; return 1; } } while (false)

int main() {
    std::string error;
    SourceRoi roi;
    CHECK(DescribeSourceRoi({1.2F, 1.1F, 3.1F, 3.0F}, 5, 4, &roi, &error));
    CHECK(roi.x == 1 && roi.y == 1 && roi.width == 3 && roi.height == 2);
    CHECK(roi.byte_size == 18 && !roi.clipped);
    std::vector<std::uint8_t> source(5 * 4 * 3);
    for (std::size_t i = 0; i < source.size(); ++i) source[i] = static_cast<std::uint8_t>(i);
    std::vector<std::uint8_t> crop;
    CHECK(CopySourceRoiRgb(source.data(), source.size(), 5, 4, roi, 18, &crop, &error));
    CHECK(crop.size() == 18 && crop[0] == source[18] && crop[9] == source[33]);
    CHECK(!CopySourceRoiRgb(source.data(), source.size(), 5, 4, roi, 17, &crop, &error));
    CHECK(crop.empty());
    CHECK(!CopySourceRoiRgb(source.data(), source.size() - 1, 5, 4, roi, 100, &crop, &error));
    CHECK(!CopySourceRoiRgb(nullptr, source.size(), 5, 4, roi, 100, &crop, &error));
    CHECK(DescribeSourceRoi({-10, -2, 10, 10}, 5, 4, &roi, &error));
    CHECK(roi.clipped && roi.x == 0 && roi.y == 0 && roi.byte_size == source.size());
    CHECK(CopySourceRoiRgb(source.data(), source.size(), 5, 4, roi, 100, &crop, &error));
    CHECK(crop == source);
    CHECK(!DescribeSourceRoi({6, 0, 10, 3}, 5, 4, &roi, &error));
    CHECK(!DescribeSourceRoi({2, 0, 1, 3}, 5, 4, &roi, &error));
    CHECK(!DescribeSourceRoi({0, 0, std::numeric_limits<float>::infinity(), 3}, 5, 4, &roi, &error));
    CHECK(!DescribeSourceRoi({std::numeric_limits<float>::quiet_NaN(), 0, 1, 3}, 5, 4, &roi, &error));
    CHECK(!DescribeSourceRoi({0, 0, 1, 3}, 0, 4, &roi, &error));
    CHECK(!DescribeSourceRoi({0, 0, 1, 3}, 5, 4, nullptr, &error));
    CHECK(DescribeSourceRoi({-std::numeric_limits<float>::max(), 0,
                             std::numeric_limits<float>::max(), 3}, 5, 4, &roi, &error));
    roi.x = std::numeric_limits<std::uint32_t>::max();
    CHECK(!CopySourceRoiRgb(source.data(), source.size(), 5, 4, roi, 100, &crop, &error));
    CHECK(!CopySourceRoiRgb(source.data(), source.size(), 5, 4, roi, 0, &crop, &error));
    std::cout << "source_roi: byte-exact original RGB, clipping, malformed input and byte caps pass\n";
}
