#include "ksi_safety/transport/json.hpp"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <stdexcept>

#include "ksi_safety/transport/digest.hpp"

namespace ksi::safety {

// --- JsonValue --------------------------------------------------------------

JsonValue JsonValue::Null() { return JsonValue(); }

JsonValue JsonValue::Bool(bool value) {
    JsonValue v;
    v.type_ = Type::kBool;
    v.bool_ = value;
    return v;
}

JsonValue JsonValue::Int(std::int64_t value) {
    JsonValue v;
    v.type_ = Type::kInt;
    v.int_ = value;
    return v;
}

JsonValue JsonValue::Double(double value) {
    JsonValue v;
    v.type_ = Type::kDouble;
    v.double_ = value;
    return v;
}

JsonValue JsonValue::Str(std::string value) {
    JsonValue v;
    v.type_ = Type::kString;
    v.str_ = std::move(value);
    return v;
}

JsonValue JsonValue::Arr(Array value) {
    JsonValue v;
    v.type_ = Type::kArray;
    v.array_ = std::move(value);
    return v;
}

JsonValue JsonValue::Obj(Object value) {
    JsonValue v;
    v.type_ = Type::kObject;
    v.object_ = std::move(value);
    return v;
}

const JsonValue* JsonValue::Find(const std::string& key) const {
    for (const auto& member : object_) {
        if (member.first == key) return &member.second;
    }
    return nullptr;
}

JsonValue* JsonValue::FindMutable(const std::string& key) {
    for (auto& member : object_) {
        if (member.first == key) return &member.second;
    }
    return nullptr;
}

void JsonValue::Set(const std::string& key, JsonValue value) {
    if (auto* existing = FindMutable(key)) {
        *existing = std::move(value);
    } else {
        object_.emplace_back(key, std::move(value));
    }
}

bool JsonValue::Erase(const std::string& key) {
    for (auto it = object_.begin(); it != object_.end(); ++it) {
        if (it->first == key) {
            object_.erase(it);
            return true;
        }
    }
    return false;
}

std::size_t JsonValue::Size() const {
    if (IsArray()) return array_.size();
    if (IsObject()) return object_.size();
    return 0;
}

void JsonValue::Push(JsonValue value) { array_.push_back(std::move(value)); }

namespace {

// --- UTF-16 code-unit ordering (JCS key sort) -------------------------------

std::vector<std::uint16_t> ToUtf16(const std::string& text) {
    std::vector<std::uint16_t> units;
    units.reserve(text.size());
    std::size_t i = 0;
    while (i < text.size()) {
        const auto byte = static_cast<unsigned char>(text[i]);
        std::uint32_t code_point;
        std::size_t length;
        if (byte < 0x80) {
            code_point = byte;
            length = 1;
        } else if ((byte >> 5) == 0x6) {
            code_point = byte & 0x1F;
            length = 2;
        } else if ((byte >> 4) == 0xE) {
            code_point = byte & 0x0F;
            length = 3;
        } else if ((byte >> 3) == 0x1E) {
            code_point = byte & 0x07;
            length = 4;
        } else {
            code_point = byte;  // invalid UTF-8: byte-wise fallback
            length = 1;
        }
        for (std::size_t k = 1; k < length && i + k < text.size(); ++k) {
            code_point = (code_point << 6) |
                         (static_cast<unsigned char>(text[i + k]) & 0x3F);
        }
        i += length;
        if (code_point >= 0x10000) {
            code_point -= 0x10000;
            units.push_back(static_cast<std::uint16_t>(0xD800 + (code_point >> 10)));
            units.push_back(static_cast<std::uint16_t>(0xDC00 + (code_point & 0x3FF)));
        } else {
            units.push_back(static_cast<std::uint16_t>(code_point));
        }
    }
    return units;
}

bool Utf16Less(const std::string& a, const std::string& b) {
    if (a == b) return false;
    const auto x = ToUtf16(a);
    const auto y = ToUtf16(b);
    return std::lexicographical_compare(x.begin(), x.end(), y.begin(), y.end());
}

// --- ES6 number formatting (RFC 8785 §3.2.2.3) ------------------------------

// Shortest round-trip digits, WITHOUT std::to_chars for floating point.
//
// WHY: std::to_chars(double) needs GCC 11+, but the Manifold 3 toolchain is
// GCC 9.4 / clang 8 — the device could not build this file at all.
//
// The algorithm is the ES6 one (RFC 8785 §3.2.2.3), the same rule the Python
// (rfc8785) and Kotlin (JCS) sides implement: find the smallest number of
// significant digits k (1..17) whose decimal form reads back as the very same
// double, and among those the value closest to it (ties to even — which is
// what printf's "%.*e" does). The digit sequence and exponent are then
// rewritten to ECMAScript Number::toString notation below.
//
// NOTE: GCC's std::to_chars is NOT always shortest (e.g. it prints
// 1234567890123456768 for 1.2345678901234568e18 where ES6 requires
// 1234567890123456800). This implementation follows ES6, so the C++ side now
// agrees with the Python/Kotlin sides on those values instead of diverging.
std::string ShortestRoundTrip(double value) {
    for (int digits = 1; digits <= 17; ++digits) {
        char buffer[64];
        const int written = std::snprintf(buffer, sizeof(buffer), "%.*e",
                                          digits - 1, value);
        if (written <= 0 || static_cast<std::size_t>(written) >= sizeof(buffer)) {
            continue;
        }
        if (std::strtod(buffer, nullptr) == value) {
            return std::string(buffer, static_cast<std::size_t>(written));
        }
    }
    char buffer[64];
    const int written = std::snprintf(buffer, sizeof(buffer), "%.17e", value);
    if (written <= 0 || static_cast<std::size_t>(written) >= sizeof(buffer)) {
        throw std::invalid_argument("number formatting failed");
    }
    return std::string(buffer, static_cast<std::size_t>(written));
}

// The digits come from the shortest round-trip form; only the NOTATION is
// rewritten here to ECMAScript Number::toString rules (RFC 8785 §3.2.2.3).
std::string FormatEs6Double(double value) {
    if (std::isnan(value) || std::isinf(value)) {
        throw std::invalid_argument("non-finite number is not JSON");
    }
    if (value == 0.0) return "0";
    const std::string text = ShortestRoundTrip(value);
    const bool negative = text[0] == '-';
    const std::size_t start = negative ? 1 : 0;
    std::string digits;
    int int_len = 0;
    int exponent = 0;
    bool seen_dot = false;
    bool has_exponent = false;
    for (std::size_t i = start; i < text.size(); ++i) {
        const char ch = text[i];
        if (ch == '.') {
            seen_dot = true;
            int_len = static_cast<int>(digits.size());
        } else if (ch == 'e' || ch == 'E') {
            has_exponent = true;
            exponent = std::atoi(text.c_str() + i + 1);
            break;
        } else {
            digits.push_back(ch);
        }
    }
    if (!seen_dot) int_len = static_cast<int>(digits.size());
    std::size_t leading_zeros = 0;
    while (leading_zeros < digits.size() && digits[leading_zeros] == '0') {
        ++leading_zeros;
    }
    digits.erase(0, leading_zeros);
    if (digits.empty()) return "0";
    // value = 0.<digits> × 10^k
    const int k = int_len - static_cast<int>(leading_zeros) +
                  (has_exponent ? exponent : 0);
    std::string out = negative ? "-" : "";
    if (k >= 1 && k <= 21) {
        if (static_cast<int>(digits.size()) <= k) {
            out += digits;
            out.append(static_cast<std::size_t>(k) - digits.size(), '0');
        } else {
            out += digits.substr(0, static_cast<std::size_t>(k));
            out += '.';
            out += digits.substr(static_cast<std::size_t>(k));
        }
    } else if (k <= 0 && k > -6) {
        out += "0.";
        out.append(static_cast<std::size_t>(-k), '0');
        out += digits;
    } else {
        out += digits.substr(0, 1);
        if (digits.size() > 1) {
            out += '.';
            out += digits.substr(1);
        }
        out += 'e';
        const int scientific = k - 1;
        out += scientific >= 0 ? '+' : '-';
        out += std::to_string(std::abs(scientific));
    }
    return out;
}

// --- Canonical writer -------------------------------------------------------

void WriteString(const std::string& value, std::string* out) {
    out->push_back('"');
    for (const char raw : value) {
        const auto byte = static_cast<unsigned char>(raw);
        switch (byte) {
            case '"': out->append("\\\""); break;
            case '\\': out->append("\\\\"); break;
            case '\b': out->append("\\b"); break;
            case '\f': out->append("\\f"); break;
            case '\n': out->append("\\n"); break;
            case '\r': out->append("\\r"); break;
            case '\t': out->append("\\t"); break;
            default:
                if (byte < 0x20) {
                    char escape[8];
                    std::snprintf(escape, sizeof(escape), "\\u%04x", byte);
                    out->append(escape);
                } else {
                    out->push_back(raw);
                }
        }
    }
    out->push_back('"');
}

void WriteValue(const JsonValue& value, std::string* out) {
    switch (value.type()) {
        case JsonValue::Type::kNull:
            out->append("null");
            break;
        case JsonValue::Type::kBool:
            out->append(value.boolValue() ? "true" : "false");
            break;
        case JsonValue::Type::kInt:
            out->append(std::to_string(value.intValue()));
            break;
        case JsonValue::Type::kDouble:
            out->append(FormatEs6Double(value.doubleValue()));
            break;
        case JsonValue::Type::kString:
            WriteString(value.strValue(), out);
            break;
        case JsonValue::Type::kArray: {
            out->push_back('[');
            bool first = true;
            for (const auto& element : value.arrayValue()) {
                if (!first) out->push_back(',');
                first = false;
                WriteValue(element, out);
            }
            out->push_back(']');
            break;
        }
        case JsonValue::Type::kObject: {
            std::vector<const JsonValue::Member*> members;
            members.reserve(value.objectValue().size());
            for (const auto& member : value.objectValue()) {
                members.push_back(&member);
            }
            std::sort(members.begin(), members.end(),
                      [](const JsonValue::Member* a,
                         const JsonValue::Member* b) {
                          return Utf16Less(a->first, b->first);
                      });
            out->push_back('{');
            bool first = true;
            for (const auto* member : members) {
                if (!first) out->push_back(',');
                first = false;
                WriteString(member->first, out);
                out->push_back(':');
                WriteValue(member->second, out);
            }
            out->push_back('}');
            break;
        }
    }
}

// --- Strict parser ----------------------------------------------------------

class Parser {
 public:
    explicit Parser(const std::string& text) : text_(text) {}

    bool Parse(JsonValue* out, std::string* error) {
        SkipWs();
        if (!ParseValue(out, error, 0)) return false;
        SkipWs();
        if (pos_ != text_.size()) {
            *error = "trailing content at offset " + std::to_string(pos_);
            return false;
        }
        return true;
    }

 private:
    bool ParseValue(JsonValue* out, std::string* error, int depth) {
        if (depth > 64) {
            *error = "nesting too deep";
            return false;
        }
        if (pos_ >= text_.size()) {
            *error = "unexpected end of input";
            return false;
        }
        switch (text_[pos_]) {
            case '{': return ParseObject(out, error, depth);
            case '[': return ParseArray(out, error, depth);
            case '"': {
                std::string value;
                if (!ParseString(&value, error)) return false;
                *out = JsonValue::Str(std::move(value));
                return true;
            }
            default: break;
        }
        if (text_.compare(pos_, 4, "true") == 0) {
            pos_ += 4;
            *out = JsonValue::Bool(true);
            return true;
        }
        if (text_.compare(pos_, 5, "false") == 0) {
            pos_ += 5;
            *out = JsonValue::Bool(false);
            return true;
        }
        if (text_.compare(pos_, 4, "null") == 0) {
            pos_ += 4;
            *out = JsonValue::Null();
            return true;
        }
        if (text_[pos_] == '-' || (text_[pos_] >= '0' && text_[pos_] <= '9')) {
            return ParseNumber(out, error);
        }
        *error = "unexpected character at offset " + std::to_string(pos_);
        return false;
    }

    bool ParseObject(JsonValue* out, std::string* error, int depth) {
        ++pos_;  // '{'
        JsonValue::Object members;
        SkipWs();
        if (pos_ < text_.size() && text_[pos_] == '}') {
            ++pos_;
            *out = JsonValue::Obj(std::move(members));
            return true;
        }
        while (true) {
            SkipWs();
            if (pos_ >= text_.size() || text_[pos_] != '"') {
                *error = "object key expected at offset " + std::to_string(pos_);
                return false;
            }
            std::string key;
            if (!ParseString(&key, error)) return false;
            SkipWs();
            if (pos_ >= text_.size() || text_[pos_] != ':') {
                *error = "':' expected at offset " + std::to_string(pos_);
                return false;
            }
            ++pos_;
            SkipWs();
            JsonValue value;
            if (!ParseValue(&value, error, depth + 1)) return false;
            bool replaced = false;
            for (auto& member : members) {
                if (member.first == key) {
                    member.second = std::move(value);  // last one wins
                    replaced = true;
                    break;
                }
            }
            if (!replaced) members.emplace_back(std::move(key), std::move(value));
            SkipWs();
            if (pos_ >= text_.size()) {
                *error = "unterminated object";
                return false;
            }
            if (text_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (text_[pos_] == '}') {
                ++pos_;
                *out = JsonValue::Obj(std::move(members));
                return true;
            }
            *error = "',' or '}' expected at offset " + std::to_string(pos_);
            return false;
        }
    }

    bool ParseArray(JsonValue* out, std::string* error, int depth) {
        ++pos_;  // '['
        JsonValue::Array elements;
        SkipWs();
        if (pos_ < text_.size() && text_[pos_] == ']') {
            ++pos_;
            *out = JsonValue::Arr(std::move(elements));
            return true;
        }
        while (true) {
            SkipWs();
            JsonValue value;
            if (!ParseValue(&value, error, depth + 1)) return false;
            elements.push_back(std::move(value));
            SkipWs();
            if (pos_ >= text_.size()) {
                *error = "unterminated array";
                return false;
            }
            if (text_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (text_[pos_] == ']') {
                ++pos_;
                *out = JsonValue::Arr(std::move(elements));
                return true;
            }
            *error = "',' or ']' expected at offset " + std::to_string(pos_);
            return false;
        }
    }

    bool ParseString(std::string* out, std::string* error) {
        ++pos_;  // '"'
        out->clear();
        while (true) {
            if (pos_ >= text_.size()) {
                *error = "unterminated string";
                return false;
            }
            const auto byte = static_cast<unsigned char>(text_[pos_]);
            if (byte == '"') {
                ++pos_;
                return true;
            }
            if (byte < 0x20) {
                *error = "raw control character in string";
                return false;
            }
            if (byte != '\\') {
                out->push_back(text_[pos_]);
                ++pos_;
                continue;
            }
            ++pos_;  // backslash
            if (pos_ >= text_.size()) {
                *error = "unterminated escape";
                return false;
            }
            const char escape = text_[pos_++];
            switch (escape) {
                case '"': out->push_back('"'); break;
                case '\\': out->push_back('\\'); break;
                case '/': out->push_back('/'); break;
                case 'b': out->push_back('\b'); break;
                case 'f': out->push_back('\f'); break;
                case 'n': out->push_back('\n'); break;
                case 'r': out->push_back('\r'); break;
                case 't': out->push_back('\t'); break;
                case 'u': {
                    std::uint32_t code_point;
                    if (!ParseHex4(&code_point, error)) return false;
                    if (code_point >= 0xD800 && code_point <= 0xDBFF) {
                        // High surrogate: a low surrogate must follow.
                        if (text_.compare(pos_, 2, "\\u") != 0) {
                            *error = "unpaired high surrogate";
                            return false;
                        }
                        pos_ += 2;
                        std::uint32_t low;
                        if (!ParseHex4(&low, error)) return false;
                        if (low < 0xDC00 || low > 0xDFFF) {
                            *error = "bad low surrogate";
                            return false;
                        }
                        code_point = 0x10000 + ((code_point - 0xD800) << 10) +
                                     (low - 0xDC00);
                    } else if (code_point >= 0xDC00 && code_point <= 0xDFFF) {
                        *error = "unpaired low surrogate";
                        return false;
                    }
                    AppendUtf8(code_point, out);
                    break;
                }
                default:
                    *error = "invalid escape";
                    return false;
            }
        }
    }

    bool ParseHex4(std::uint32_t* out, std::string* error) {
        if (pos_ + 4 > text_.size()) {
            *error = "truncated \\u escape";
            return false;
        }
        std::uint32_t value = 0;
        for (int i = 0; i < 4; ++i) {
            const char ch = text_[pos_ + static_cast<std::size_t>(i)];
            value <<= 4;
            if (ch >= '0' && ch <= '9') {
                value |= static_cast<std::uint32_t>(ch - '0');
            } else if (ch >= 'a' && ch <= 'f') {
                value |= static_cast<std::uint32_t>(ch - 'a' + 10);
            } else if (ch >= 'A' && ch <= 'F') {
                value |= static_cast<std::uint32_t>(ch - 'A' + 10);
            } else {
                *error = "bad hex in \\u escape";
                return false;
            }
        }
        pos_ += 4;
        *out = value;
        return true;
    }

    static void AppendUtf8(std::uint32_t code_point, std::string* out) {
        if (code_point < 0x80) {
            out->push_back(static_cast<char>(code_point));
        } else if (code_point < 0x800) {
            out->push_back(static_cast<char>(0xC0 | (code_point >> 6)));
            out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        } else if (code_point < 0x10000) {
            out->push_back(static_cast<char>(0xE0 | (code_point >> 12)));
            out->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        } else {
            out->push_back(static_cast<char>(0xF0 | (code_point >> 18)));
            out->push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
            out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
        }
    }

    bool ParseNumber(JsonValue* out, std::string* error) {
        const std::size_t start = pos_;
        if (pos_ < text_.size() && text_[pos_] == '-') ++pos_;
        if (pos_ >= text_.size() || text_[pos_] < '0' || text_[pos_] > '9') {
            *error = "digit expected at offset " + std::to_string(pos_);
            return false;
        }
        if (text_[pos_] == '0') {
            ++pos_;
            if (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') {
                *error = "leading zero at offset " + std::to_string(pos_);
                return false;
            }
        } else {
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') {
                ++pos_;
            }
        }
        bool is_double = false;
        if (pos_ < text_.size() && text_[pos_] == '.') {
            is_double = true;
            ++pos_;
            if (pos_ >= text_.size() || text_[pos_] < '0' || text_[pos_] > '9') {
                *error = "fraction digit expected at offset " +
                         std::to_string(pos_);
                return false;
            }
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') {
                ++pos_;
            }
        }
        if (pos_ < text_.size() &&
            (text_[pos_] == 'e' || text_[pos_] == 'E')) {
            is_double = true;
            ++pos_;
            if (pos_ < text_.size() &&
                (text_[pos_] == '+' || text_[pos_] == '-')) {
                ++pos_;
            }
            if (pos_ >= text_.size() || text_[pos_] < '0' || text_[pos_] > '9') {
                *error = "exponent digit expected at offset " +
                         std::to_string(pos_);
                return false;
            }
            while (pos_ < text_.size() && text_[pos_] >= '0' && text_[pos_] <= '9') {
                ++pos_;
            }
        }
        const std::string slice = text_.substr(start, pos_ - start);
        if (is_double) {
            char* end = nullptr;
            const double parsed = std::strtod(slice.c_str(), &end);
            if (end == nullptr || *end != '\0' || std::isnan(parsed) ||
                std::isinf(parsed)) {
                *error = "bad double at offset " + std::to_string(start);
                return false;
            }
            *out = JsonValue::Double(parsed);
            return true;
        }
        // Integer: must fit int64 (product payloads never exceed it).
        std::uint64_t magnitude = 0;
        std::size_t i = slice[0] == '-' ? 1 : 0;
        for (; i < slice.size(); ++i) {
            const std::uint64_t digit =
                static_cast<std::uint64_t>(slice[i] - '0');
            if (magnitude >
                (std::numeric_limits<std::uint64_t>::max() - digit) / 10U) {
                *error = "integer out of range at offset " + std::to_string(start);
                return false;
            }
            magnitude = magnitude * 10U + digit;
        }
        constexpr std::uint64_t kMax =
            static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
        if (slice[0] == '-') {
            if (magnitude > kMax + 1ULL) {
                *error = "integer out of range at offset " + std::to_string(start);
                return false;
            }
            *out = JsonValue::Int(magnitude == kMax + 1ULL
                                      ? std::numeric_limits<std::int64_t>::min()
                                      : -static_cast<std::int64_t>(magnitude));
        } else {
            if (magnitude > kMax) {
                *error = "integer out of range at offset " + std::to_string(start);
                return false;
            }
            *out = JsonValue::Int(static_cast<std::int64_t>(magnitude));
        }
        return true;
    }

    void SkipWs() {
        while (pos_ < text_.size()) {
            const char ch = text_[pos_];
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                ++pos_;
            } else {
                break;
            }
        }
    }

    const std::string& text_;
    std::size_t pos_ = 0;
};

}  // namespace

bool ParseJson(const std::string& text, JsonValue* out, std::string* error) {
    Parser parser(text);
    std::string local_error;
    if (!parser.Parse(out, &local_error)) {
        if (error != nullptr) *error = local_error;
        return false;
    }
    return true;
}

std::string WriteCanonicalJson(const JsonValue& value) {
    std::string out;
    WriteValue(value, &out);
    return out;
}

std::string DigestSha256Json(const JsonValue& value) {
    return Sha256Hex(WriteCanonicalJson(value));
}

std::string DigestExcluding(const JsonValue& object, const std::string& field) {
    if (!object.IsObject()) return {};
    JsonValue copy = object;
    copy.Erase(field);
    return DigestSha256Json(copy);
}

bool VerifyDigest(const JsonValue& object, const std::string& field) {
    if (!object.IsObject()) return false;
    const JsonValue* claimed = object.Find(field);
    if (claimed == nullptr || !claimed->IsString()) return false;
    return DigestExcluding(object, field) == claimed->strValue();
}

}  // namespace ksi::safety
