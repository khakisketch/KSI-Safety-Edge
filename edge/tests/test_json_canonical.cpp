// Canonical JSON number formatting (RFC 8785 §3.2.2.3) — golden vectors.
//
// WHY THIS TEST EXISTS: the Manifold 3 toolchain is GCC 9.4 / clang 8, which
// has no std::to_chars for floating point, so the Edge could not build the
// canonical writer on the device at all. The implementation now formats
// doubles with the ES6 shortest-round-trip rule (the same rule the Python
// rfc8785 and the Kotlin JCS sides implement) using only C printf.
//
// The vectors below are the canonical bytes for representative doubles and
// were cross-checked against the Python side (ksi_contracts.canonical), so a
// change in these bytes IS a change in the cross-language digest contract.
//
// One value is deliberately included: 1.2345678901234568e18, where GCC's
// std::to_chars prints MORE digits than ES6 requires (1234567890123456768 vs
// 1234567890123456800). The ES6 bytes are the contract — the Python and
// Kotlin sides already produce them.
#include "ksi_safety/transport/json.hpp"

#include <string>
#include <vector>

#include "support/check.hpp"

int main() {
    const std::vector<double> values = {
        0.1,
        0.5,
        1.0,
        1.5,
        2.5,
        3.0,
        100.0,
        123456.789,
        1.0 / 3.0,
        2.0 / 3.0,
        0.0001,
        1e-5,
        1e-6,
        1e-7,
        1e16,
        1e17,
        1e21,
        1e22,
        1.2345678901234568e17,
        1234567890123456789.0,
        5e-324,
        2.2250738585072014e-308,
        1.7976931348623157e308,
        -0.1,
        -123456.789,
        9007199254740992.0,
        0.30000000000000004,
        6378137.0,
        37.56653599999999,
        0.000001234,
        6.02e23,
    };
    const std::vector<std::string> expected = {
        "{\"v\":0.1}",
        "{\"v\":0.5}",
        "{\"v\":1}",
        "{\"v\":1.5}",
        "{\"v\":2.5}",
        "{\"v\":3}",
        "{\"v\":100}",
        "{\"v\":123456.789}",
        "{\"v\":0.3333333333333333}",
        "{\"v\":0.6666666666666666}",
        "{\"v\":0.0001}",
        "{\"v\":0.00001}",
        "{\"v\":0.000001}",
        "{\"v\":1e-7}",
        "{\"v\":10000000000000000}",
        "{\"v\":100000000000000000}",
        "{\"v\":1e+21}",
        "{\"v\":1e+22}",
        "{\"v\":123456789012345680}",
        "{\"v\":1234567890123456800}",
        "{\"v\":5e-324}",
        "{\"v\":2.2250738585072014e-308}",
        "{\"v\":1.7976931348623157e+308}",
        "{\"v\":-0.1}",
        "{\"v\":-123456.789}",
        "{\"v\":9007199254740992}",
        "{\"v\":0.30000000000000004}",
        "{\"v\":6378137}",
        "{\"v\":37.56653599999999}",
        "{\"v\":0.000001234}",
        "{\"v\":6.02e+23}",
    };
    CHECK(values.size() == expected.size(), "vector sizes match");
    for (std::size_t i = 0; i < values.size(); ++i) {
        ksi::safety::JsonValue object = ksi::safety::JsonValue::Obj();
        object.Set("v", ksi::safety::JsonValue::Double(values[i]));
        const std::string actual = ksi::safety::WriteCanonicalJson(object);
        CHECK(actual == expected[i],
              ("canonical bytes for double #" + std::to_string(i)).c_str());
    }
    return edge_test::failures == 0 ? 0 : 1;
}
