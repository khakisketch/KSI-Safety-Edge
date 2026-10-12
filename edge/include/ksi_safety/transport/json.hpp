// Minimal JSON value model + RFC 8785 (JCS) canonical serialization for the
// MOP transport (A9-2). Digest semantics mirror the Python `ksi_contracts`
// module and the Kotlin `CanonicalJson` object: the hex SHA256 of the
// canonical bytes of the object excluding the digest field itself.
//
// The parser is strict (no trailing content, no duplicate handling beyond
// replace, control characters must be escaped, integers must fit int64)
// and the writer always emits ES6-number/JCS form, so the SAME values
// produce the SAME bytes in Python, Kotlin and C++ — pinned by golden
// vectors in both test suites.
#ifndef KSI_SAFETY_TRANSPORT_JSON_HPP
#define KSI_SAFETY_TRANSPORT_JSON_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace ksi::safety {

class JsonValue {
 public:
    enum class Type { kNull, kBool, kInt, kDouble, kString, kArray, kObject };

    using Array = std::vector<JsonValue>;
    using Member = std::pair<std::string, JsonValue>;
    using Object = std::vector<Member>;  // insertion order; writers sort

    JsonValue() = default;

    static JsonValue Null();
    static JsonValue Bool(bool value);
    static JsonValue Int(std::int64_t value);
    static JsonValue Double(double value);
    static JsonValue Str(std::string value);
    static JsonValue Arr(Array value = {});
    static JsonValue Obj(Object value = {});

    Type type() const { return type_; }
    bool IsNull() const { return type_ == Type::kNull; }
    bool IsBool() const { return type_ == Type::kBool; }
    bool IsInt() const { return type_ == Type::kInt; }
    bool IsDouble() const { return type_ == Type::kDouble; }
    bool IsString() const { return type_ == Type::kString; }
    bool IsArray() const { return type_ == Type::kArray; }
    bool IsObject() const { return type_ == Type::kObject; }

    bool boolValue(bool fallback = false) const {
        return IsBool() ? bool_ : fallback;
    }
    std::int64_t intValue(std::int64_t fallback = 0) const {
        return IsInt() ? int_ : fallback;
    }
    double doubleValue(double fallback = 0.0) const {
        if (IsDouble()) return double_;
        if (IsInt()) return static_cast<double>(int_);
        return fallback;
    }
    // Empty string when the value is not a string.
    const std::string& strValue() const { return str_; }
    const Array& arrayValue() const { return array_; }
    const Object& objectValue() const { return object_; }

    const JsonValue* Find(const std::string& key) const;
    JsonValue* FindMutable(const std::string& key);
    // Object: replace an existing key in place or append.
    void Set(const std::string& key, JsonValue value);
    bool Erase(const std::string& key);
    // Array/object element count; 0 for scalars.
    std::size_t Size() const;
    void Push(JsonValue value);

 private:
    Type type_ = Type::kNull;
    bool bool_ = false;
    std::int64_t int_ = 0;
    double double_ = 0.0;
    std::string str_;
    Array array_;
    Object object_;
};

// Strict parse. Returns false with `*error` set on malformed input.
bool ParseJson(const std::string& text, JsonValue* out, std::string* error);

// RFC 8785 canonical bytes as a UTF-8 string (object keys sorted by UTF-16
// code units, ES6 numbers, minimal escaping). Throws std::invalid_argument
// only for NaN/Infinity doubles (callers never construct those).
std::string WriteCanonicalJson(const JsonValue& value);

// JCS helpers (Python `digest_sha256` / `digest_excluding` / `verify_digest`).
std::string DigestSha256Json(const JsonValue& value);
std::string DigestExcluding(const JsonValue& object, const std::string& field);
bool VerifyDigest(const JsonValue& object, const std::string& field);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_JSON_HPP
