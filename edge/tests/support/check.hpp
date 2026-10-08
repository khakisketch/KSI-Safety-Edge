// Minimal assert-style test helper shared by all edge/ tests.
//
// Usage: define nothing; include this header, then use CHECK(cond, msg).
// main() must `return edge_test::failures == 0 ? 0 : 1;` (or an equivalent
// count) so that ctest observes a nonzero exit on failure.
#ifndef KSI_SAFETY_EDGE_TEST_CHECK_HPP
#define KSI_SAFETY_EDGE_TEST_CHECK_HPP

#include <cstdio>

namespace edge_test {
inline int failures = 0;
}  // namespace edge_test

#define CHECK(cond, msg)                                                       \
    do {                                                                       \
        if (!(cond)) {                                                         \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__,       \
                         (msg));                                              \
            ++edge_test::failures;                                             \
        }                                                                      \
    } while (0)

#endif  // KSI_SAFETY_EDGE_TEST_CHECK_HPP
