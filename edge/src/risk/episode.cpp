#include "ksi_safety/risk/episode.hpp"

namespace ksi::safety {

const char* ToString(EpisodeState state) {
    switch (state) {
        case EpisodeState::kNoRelation:
            return "NO_RELATION";
        case EpisodeState::kFar:
            return "FAR";
        case EpisodeState::kNearCandidate:
            return "NEAR_CANDIDATE";
        case EpisodeState::kNear:
            return "NEAR";
        case EpisodeState::kExitPending:
            return "EXIT_PENDING";
        case EpisodeState::kUnknown:
            return "UNKNOWN";
    }
    return "UNKNOWN";
}

const char* ToString(EpisodeEventKind kind) {
    switch (kind) {
        case EpisodeEventKind::kCreated:
            return "CREATED";
        case EpisodeEventKind::kUpdated:
            return "UPDATED";
        case EpisodeEventKind::kClosed:
            return "CLOSED";
    }
    return "UNKNOWN";
}

const char* ToString(EpisodeCloseReason reason) {
    switch (reason) {
        case EpisodeCloseReason::kExitConfirmed:
            return "EXIT_CONFIRMED";
        case EpisodeCloseReason::kExitNotObserved:
            return "EXIT_NOT_OBSERVED";
        case EpisodeCloseReason::kObservationLost:
            return "OBSERVATION_LOST";
        case EpisodeCloseReason::kSessionEnd:
            return "SESSION_END";
    }
    return "UNKNOWN";
}

}  // namespace ksi::safety
