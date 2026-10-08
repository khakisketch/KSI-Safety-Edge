#include "ksi_safety/time/matching.hpp"

namespace ksi::safety {

const char* ToString(PoseQuality quality) {
    switch (quality) {
        case PoseQuality::kNone:
            return "NONE";
        case PoseQuality::kReceiveNearest:
            return "RECEIVE_NEAREST";
        case PoseQuality::kSourceVerified:
            return "SOURCE_VERIFIED";
    }
    return "UNKNOWN";
}

const char* ToString(PoseReason reason) {
    switch (reason) {
        case PoseReason::kOk:
            return "OK";
        case PoseReason::kNoSamples:
            return "NO_SAMPLES";
        case PoseReason::kOutsideMaxGap:
            return "OUTSIDE_MAX_GAP";
        case PoseReason::kCaptureTimeUnverified:
            return "CAPTURE_TIME_UNVERIFIED";
        case PoseReason::kSourceTimeUnverified:
            return "SOURCE_TIME_UNVERIFIED";
        case PoseReason::kDomainMismatch:
            return "DOMAIN_MISMATCH";
        case PoseReason::kNoBracket:
            return "NO_BRACKET";
        case PoseReason::kBracketTooWide:
            return "BRACKET_TOO_WIDE";
    }
    return "UNKNOWN";
}

}  // namespace ksi::safety
