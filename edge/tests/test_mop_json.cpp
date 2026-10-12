// MOP JSON/JCS (A9-2): digest goldens produced by the REAL Python modules
// (libs/ksi_contracts + rfc8785) and already pinned on the Kotlin side
// (rc-core MopContractTest), plus ES6 number vectors, parser strictness and
// uuid5/namespace parity.
#include <cstdio>
#include <cstdint>
#include <limits>
#include <string>

#include "ksi_safety/transport/digest.hpp"
#include "ksi_safety/transport/json.hpp"
#include "ksi_safety/transport/mop_envelope.hpp"
#include "support/check.hpp"

using ksi::safety::DigestSha256Json;
using ksi::safety::JsonValue;
using ksi::safety::MopProtocol;
using ksi::safety::ParseJson;
using ksi::safety::Sha1Hex;
using ksi::safety::Sha256Hex;
using ksi::safety::Uuid5Dns;
using ksi::safety::VerifyDigest;
using ksi::safety::WriteCanonicalJson;

namespace {

JsonValue ParseOrDie(const std::string& json) {
    JsonValue value;
    std::string error;
    if (!ParseJson(json, &value, &error)) {
        CHECK(false, "fixture JSON must parse");
        return JsonValue::Null();
    }
    return value;
}

}  // namespace

int main() {
    // --- hash primitives ----------------------------------------------------
    CHECK(Sha256Hex("") ==
              "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
          "sha256(empty) FIPS vector");
    CHECK(Sha256Hex("abc") ==
              "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
          "sha256(abc) FIPS vector");
    CHECK(Sha1Hex(reinterpret_cast<const std::uint8_t*>("abc"), 3) ==
              "a9993e364706816aba3e25717850c26c9cd0d89d",
          "sha1(abc) RFC 3174 vector");

    // --- uuid5 / namespace parity (same goldens as Kotlin MopContractTest) --
    CHECK(Uuid5Dns("ksi-safety-edge-harness") ==
              "bcf20008-2c56-56a4-a896-d918b7df6364",
          "MOP namespace uuid5 parity");
    CHECK(MopProtocol::NewMsgId({"edge", "flt-1", "boot-1", "1",
                                 "CONTROL_RELIABLE", "MISSION_PREPARED",
                                 "3"}) ==
              "f922155e-bfb5-58e5-a4ad-3006fcf1d921",
          "edge msg_id uuid5 parity");

    // --- ES6 number formatting (rfc8785 / ECMAScript Number::toString) ------
    const struct {
        double value;
        const char* expected;
    } kNumberVectors[] = {
        {0.0, "0"},
        {-0.0, "0"},
        {15.0, "15"},
        {-15.0, "-15"},
        {14.9, "14.9"},
        {0.1, "0.1"},
        {0.5, "0.5"},
        {1000000.0, "1000000"},
        {1e20, "100000000000000000000"},
        {1e21, "1e+21"},
        {1e22, "1e+22"},
        {1e-6, "0.000001"},
        {1e-7, "1e-7"},
        {4.774438, "4.774438"},
        {0.8980000019073486, "0.8980000019073486"},
        {1.5e300, "1.5e+300"},
        {2.5e-10, "2.5e-10"},
        {123.456, "123.456"},
    };
    for (const auto& vector : kNumberVectors) {
        const std::string actual =
            WriteCanonicalJson(JsonValue::Double(vector.value));
        CHECK(actual == vector.expected,
              "ES6 double formatting matches rfc8785");
        if (actual != vector.expected) {
            std::fprintf(stderr, "  value %.17g: got '%s' want '%s'\n",
                         vector.value, actual.c_str(), vector.expected);
        }
    }
    CHECK(WriteCanonicalJson(JsonValue::Int(9007199254740991LL)) ==
              "9007199254740991",
          "int64 exact digits");
    CHECK(WriteCanonicalJson(JsonValue::Int(-42)) == "-42", "negative int");

    // --- digest goldens (pinned in Kotlin MopContractTest from Python) ------
    const struct {
        const char* json;
        const char* digest;
    } kDigestVectors[] = {
        {R"({"a":1})",
         "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862"},
        {R"({"a":[1.5,false,null,"x"],"b":1})",
         "579b623880179dc6e38e7e2cbbfada8e0a04cf74c18634df24e2be594dac59eb"},
        {R"({"nested":{"y":"text","z":true},"scores":[0.91,0.87,1.0]})",
         "032cefa668fb4fa8a8fee14f20500a93dfa0331dadb16f08016f970115371940"},
        {R"({"big":9007199254740991,"float":0.1,"int_neg":-42})",
         "4fd7201e5d9ec688aea6508baba8a284c782e3d35a91f1334f02b0b83ecac00b"},
        {R"({"bundle_sha256":"abababababababababababababababababababababababababababababababab","supported":true,"exclusions":[]})",
         "c2a10bcfdbc43fdf3a10aab3897897c88344b2024b3e97d488e055a9fad6f13a"},
        {R"({"b":1,"a":"x","nested":{"z":null,"y":[1,2.5,"s"]}})",
         "7c0dbcd05892d8331036bae6d327062c8f42eb206b6761d817f623e0b7cd60a5"},
        {R"({"schema_version":1,"confidence":0.8980000019073486,"label":"person","bbox":[0.0,1.5,2.0,3.25]})",
         "156e3b4bf594a19157848c5e967c74179bdf27a2a9d3539cdad9769c4ac58b60"},
        {R"({"k":[true,false,null],"u":"\ud55c\uae00","esc":"line\nquote\"back\\slash"})",
         "43a379241b58a25566c38f8699d8a42a5115a30949f130dc25acbd5b70fe3f59"},
    };
    for (const auto& vector : kDigestVectors) {
        const JsonValue value = ParseOrDie(vector.json);
        CHECK(DigestSha256Json(value) == vector.digest,
              "JCS digest parity with Python");

        // Canonical string spot checks (key order + escaping).
    }
    {
        const JsonValue sample =
            ParseOrDie(R"({"b":1,"a":"x","nested":{"z":null,"y":[1,2.5,"s"]}})");
        CHECK(WriteCanonicalJson(sample) ==
                  R"({"a":"x","b":1,"nested":{"y":[1,2.5,"s"],"z":null}})",
              "canonical bytes: sorted keys, ES6 numbers, null");
        const JsonValue korean =
            ParseOrDie(R"({"k":[true,false,null],"u":"\ud55c\uae00","esc":"line\nquote\"back\\slash"})");
        CHECK(WriteCanonicalJson(korean) ==
                  "{\"esc\":\"line\\nquote\\\"back\\\\slash\",\"k\":[true,false,null],\"u\":\"\ud55c\uae00\"}",
              "canonical bytes: escapes minimal, UTF-8 passthrough");
    }

    // --- verify/verify_digest semantics -------------------------------------
    {
        JsonValue object = ParseOrDie(R"({"x":"y"})");
        object.Set("content_sha256", JsonValue::Str(DigestSha256Json(object)));
        CHECK(VerifyDigest(object, "content_sha256"), "verify_digest accepts");
        const JsonValue* field = object.Find("content_sha256");
        object.Set("x", JsonValue::Str("z"));
        CHECK(!VerifyDigest(object, "content_sha256"),
              "verify_digest rejects after tamper");
        CHECK(field != nullptr, "field accessor sanity");
    }

    // --- parser strictness ---------------------------------------------------
    const char* kBadJson[] = {
        "01",                    // leading zero
        "1.",                    // fraction without digits
        "1e",                    // exponent without digits
        R"("\x")",               // invalid escape
        "\"raw\tcontrol\"",      // raw control character
        R"({"a":1,})",           // trailing comma
        R"({"a":1} extra",       // trailing content
        "9223372036854775808",   // int64 overflow
        R"([1,2)",               // unterminated array
        R"({"a":})",             // missing value
    };
    for (const char* json : kBadJson) {
        JsonValue value;
        std::string error;
        CHECK(!ParseJson(json, &value, &error),
              "malformed JSON must be rejected");
    }
    {
        JsonValue value;
        std::string error;
        CHECK(ParseJson("-9223372036854775808", &value, &error),
              "INT64_MIN parses");
        CHECK(value.intValue() == std::numeric_limits<std::int64_t>::min(),
              "INT64_MIN value");
        std::string error2;
        JsonValue dup;
        CHECK(ParseJson(R"({"a":1,"a":2})", &dup, &error2),
              "duplicate keys parse (last wins)");
        CHECK(dup.Find("a") != nullptr && dup.Find("a")->intValue() == 2,
              "duplicate key: last value wins");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
