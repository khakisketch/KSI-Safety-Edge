#include "ksi_safety/transport/digest.hpp"

#include <cstdio>
#include <cstring>

namespace ksi::safety {
namespace {

// --- SHA-256 (FIPS 180-4) ---------------------------------------------------

constexpr std::uint32_t kSha256K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

inline std::uint32_t Rotr(std::uint32_t v, unsigned n) {
    return (v >> n) | (v << (32U - n));
}

void Sha256Digest(const std::uint8_t* data, std::size_t len,
                  std::uint8_t out[32]) {
    std::uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                          0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    const std::uint64_t bit_len = static_cast<std::uint64_t>(len) * 8U;
    std::size_t offset = 0;
    std::uint8_t block[64];
    auto compress = [&](const std::uint8_t* chunk) {
        std::uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<std::uint32_t>(chunk[i * 4]) << 24) |
                   (static_cast<std::uint32_t>(chunk[i * 4 + 1]) << 16) |
                   (static_cast<std::uint32_t>(chunk[i * 4 + 2]) << 8) |
                   static_cast<std::uint32_t>(chunk[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            const std::uint32_t s0 = Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^
                                     (w[i - 15] >> 3);
            const std::uint32_t s1 = Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^
                                     (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        std::uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        std::uint32_t e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const std::uint32_t s1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
            const std::uint32_t ch = (e & f) ^ (~e & g);
            const std::uint32_t t1 = hh + s1 + ch + kSha256K[i] + w[i];
            const std::uint32_t s0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
            const std::uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            const std::uint32_t t2 = s0 + maj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    };
    while (offset + 64 <= len) {
        compress(data + offset);
        offset += 64;
    }
    std::size_t tail = len - offset;
    std::memcpy(block, data + offset, tail);
    block[tail] = 0x80;
    if (tail >= 56) {
        std::memset(block + tail + 1, 0, 64 - tail - 1);
        compress(block);
        tail = 0;
        std::memset(block, 0, 56);
    } else {
        std::memset(block + tail + 1, 0, 56 - tail - 1);
    }
    for (int i = 0; i < 8; ++i) {
        block[56 + i] = static_cast<std::uint8_t>(bit_len >> (56 - i * 8));
    }
    compress(block);
    for (int i = 0; i < 8; ++i) {
        out[i * 4] = static_cast<std::uint8_t>(h[i] >> 24);
        out[i * 4 + 1] = static_cast<std::uint8_t>(h[i] >> 16);
        out[i * 4 + 2] = static_cast<std::uint8_t>(h[i] >> 8);
        out[i * 4 + 3] = static_cast<std::uint8_t>(h[i]);
    }
}

// --- SHA-1 (RFC 3174) -------------------------------------------------------

void Sha1Digest(const std::uint8_t* data, std::size_t len, std::uint8_t out[20]) {
    std::uint32_t h[5] = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476,
                          0xC3D2E1F0};
    const std::uint64_t bit_len = static_cast<std::uint64_t>(len) * 8U;
    std::size_t offset = 0;
    std::uint8_t block[64];
    auto compress = [&](const std::uint8_t* chunk) {
        std::uint32_t w[80];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<std::uint32_t>(chunk[i * 4]) << 24) |
                   (static_cast<std::uint32_t>(chunk[i * 4 + 1]) << 16) |
                   (static_cast<std::uint32_t>(chunk[i * 4 + 2]) << 8) |
                   static_cast<std::uint32_t>(chunk[i * 4 + 3]);
        }
        for (int i = 16; i < 80; ++i) {
            w[i] = Rotr(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 31);
        }
        std::uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; ++i) {
            std::uint32_t f, k;
            if (i < 20) {
                f = (b & c) | (~b & d); k = 0x5A827999;
            } else if (i < 40) {
                f = b ^ c ^ d; k = 0x6ED9EBA1;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC;
            } else {
                f = b ^ c ^ d; k = 0xCA62C1D6;
            }
            const std::uint32_t temp =
                ((a << 5) | (a >> 27)) + f + e + k + w[i];
            e = d; d = c; c = (b << 30) | (b >> 2); b = a; a = temp;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    };
    while (offset + 64 <= len) {
        compress(data + offset);
        offset += 64;
    }
    std::size_t tail = len - offset;
    std::memcpy(block, data + offset, tail);
    block[tail] = 0x80;
    if (tail >= 56) {
        std::memset(block + tail + 1, 0, 64 - tail - 1);
        compress(block);
        tail = 0;
        std::memset(block, 0, 56);
    } else {
        std::memset(block + tail + 1, 0, 56 - tail - 1);
    }
    for (int i = 0; i < 8; ++i) {
        block[56 + i] = static_cast<std::uint8_t>(bit_len >> (56 - i * 8));
    }
    compress(block);
    for (int i = 0; i < 5; ++i) {
        out[i * 4] = static_cast<std::uint8_t>(h[i] >> 24);
        out[i * 4 + 1] = static_cast<std::uint8_t>(h[i] >> 16);
        out[i * 4 + 2] = static_cast<std::uint8_t>(h[i] >> 8);
        out[i * 4 + 3] = static_cast<std::uint8_t>(h[i]);
    }
}

std::string ToHex(const std::uint8_t* data, std::size_t len) {
    static const char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(len * 2);
    for (std::size_t i = 0; i < len; ++i) {
        out.push_back(kHex[data[i] >> 4]);
        out.push_back(kHex[data[i] & 0x0F]);
    }
    return out;
}

bool ParseUuid(const std::string& text, std::uint8_t out[16]) {
    int nibble = 0;
    int hi = 0;
    for (char ch : text) {
        if (ch == '-') continue;
        int value;
        if (ch >= '0' && ch <= '9') {
            value = ch - '0';
        } else if (ch >= 'a' && ch <= 'f') {
            value = ch - 'a' + 10;
        } else if (ch >= 'A' && ch <= 'F') {
            value = ch - 'A' + 10;
        } else {
            return false;
        }
        if (nibble % 2 == 0) {
            hi = value;
        } else {
            if (nibble / 2 >= 16) return false;
            out[nibble / 2] = static_cast<std::uint8_t>((hi << 4) | value);
        }
        ++nibble;
    }
    return nibble == 32;
}

}  // namespace

std::string Sha256Hex(const std::uint8_t* data, std::size_t len) {
    std::uint8_t digest[32];
    Sha256Digest(data, len, digest);
    return ToHex(digest, sizeof(digest));
}

namespace {

// Streaming SHA-256 (same FIPS 180-4 core as Sha256Digest, incremental so
// multi-MB files hash without full buffering).
struct Sha256Stream {
    std::uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                          0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::uint8_t buf[64] = {};
    std::size_t buffered = 0;
    std::uint64_t total = 0;

    void Compress(const std::uint8_t* chunk) {
        std::uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<std::uint32_t>(chunk[i * 4]) << 24) |
                   (static_cast<std::uint32_t>(chunk[i * 4 + 1]) << 16) |
                   (static_cast<std::uint32_t>(chunk[i * 4 + 2]) << 8) |
                   static_cast<std::uint32_t>(chunk[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            const std::uint32_t s0 = Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^
                                     (w[i - 15] >> 3);
            const std::uint32_t s1 = Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^
                                     (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        std::uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        std::uint32_t e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const std::uint32_t s1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
            const std::uint32_t ch = (e & f) ^ (~e & g);
            const std::uint32_t t1 = hh + s1 + ch + kSha256K[i] + w[i];
            const std::uint32_t s0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
            const std::uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            const std::uint32_t t2 = s0 + maj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    void Update(const std::uint8_t* data, std::size_t len) {
        total += len;
        while (len > 0) {
            const std::size_t room = 64 - buffered;
            const std::size_t take = len < room ? len : room;
            std::memcpy(buf + buffered, data, take);
            buffered += take;
            data += take;
            len -= take;
            if (buffered == 64) {
                Compress(buf);
                buffered = 0;
            }
        }
    }

    void Final(std::uint8_t out[32]) {
        const std::uint64_t bit_len = total * 8U;
        std::uint8_t pad = 0x80;
        Update(&pad, 1);
        std::uint8_t zero = 0;
        while (buffered != 56) {
            Update(&zero, 1);
        }
        std::uint8_t len_bytes[8];
        for (int i = 0; i < 8; ++i) {
            len_bytes[i] = static_cast<std::uint8_t>(bit_len >> (56 - i * 8));
        }
        // Bypass Update: exactly 8 bytes land in a fresh block.
        std::memcpy(buf + 56, len_bytes, 8);
        Compress(buf);
        for (int i = 0; i < 8; ++i) {
            out[i * 4] = static_cast<std::uint8_t>(h[i] >> 24);
            out[i * 4 + 1] = static_cast<std::uint8_t>(h[i] >> 16);
            out[i * 4 + 2] = static_cast<std::uint8_t>(h[i] >> 8);
            out[i * 4 + 3] = static_cast<std::uint8_t>(h[i]);
        }
    }
};

}  // namespace

std::string Sha256HexOfFile(const char* path, std::string* error) {
    auto fail = [&](const std::string& message) {
        if (error != nullptr) *error = message;
        return std::string();
    };
    if (path == nullptr || path[0] == '\0') {
        return fail("empty file path");
    }
    std::FILE* file = std::fopen(path, "rb");
    if (file == nullptr) {
        return fail(std::string("cannot open file: ") + path);
    }
    Sha256Stream stream;
    std::uint8_t chunk[65536];
    for (;;) {
        const std::size_t n = std::fread(chunk, 1, sizeof(chunk), file);
        if (n > 0) {
            stream.Update(chunk, n);
        }
        if (n < sizeof(chunk)) {
            if (std::ferror(file)) {
                std::fclose(file);
                return fail(std::string("short read: ") + path);
            }
            break;
        }
    }
    std::fclose(file);
    std::uint8_t digest[32];
    stream.Final(digest);
    if (error != nullptr) error->clear();
    return ToHex(digest, sizeof(digest));
}

std::string Sha256Hex(const std::string& bytes) {
    return Sha256Hex(reinterpret_cast<const std::uint8_t*>(bytes.data()),
                     bytes.size());
}

std::string Sha1Hex(const std::uint8_t* data, std::size_t len) {
    std::uint8_t digest[20];
    Sha1Digest(data, len, digest);
    return ToHex(digest, sizeof(digest));
}

std::string Uuid5(const std::string& namespace_uuid, const std::string& name) {
    std::uint8_t ns[16];
    if (!ParseUuid(namespace_uuid, ns)) {
        return {};
    }
    std::string buffer(reinterpret_cast<const char*>(ns), sizeof(ns));
    buffer += name;
    std::uint8_t digest[20];
    Sha1Digest(reinterpret_cast<const std::uint8_t*>(buffer.data()),
               buffer.size(), digest);
    std::uint8_t uuid[16];
    std::memcpy(uuid, digest, sizeof(uuid));
    uuid[6] = static_cast<std::uint8_t>((uuid[6] & 0x0F) | 0x50);  // v5
    uuid[8] = static_cast<std::uint8_t>((uuid[8] & 0x3F) | 0x80);  // variant
    const std::string hex = ToHex(uuid, sizeof(uuid));
    return hex.substr(0, 8) + "-" + hex.substr(8, 4) + "-" +
           hex.substr(12, 4) + "-" + hex.substr(16, 4) + "-" +
           hex.substr(20, 12);
}

std::string Uuid5Dns(const std::string& name) {
    return Uuid5("6ba7b810-9dad-11d1-80b4-00c04fd430c8", name);
}

std::string Base64Encode(const std::uint8_t* data, std::size_t len) {
    static const char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    std::size_t i = 0;
    while (i + 3 <= len) {
        const std::uint32_t triple =
            (static_cast<std::uint32_t>(data[i]) << 16) |
            (static_cast<std::uint32_t>(data[i + 1]) << 8) |
            static_cast<std::uint32_t>(data[i + 2]);
        out.push_back(kAlphabet[(triple >> 18) & 0x3F]);
        out.push_back(kAlphabet[(triple >> 12) & 0x3F]);
        out.push_back(kAlphabet[(triple >> 6) & 0x3F]);
        out.push_back(kAlphabet[triple & 0x3F]);
        i += 3;
    }
    if (i < len) {
        const std::size_t remaining = len - i;
        std::uint32_t triple =
            static_cast<std::uint32_t>(data[i]) << 16;
        if (remaining == 2) {
            triple |= static_cast<std::uint32_t>(data[i + 1]) << 8;
        }
        out.push_back(kAlphabet[(triple >> 18) & 0x3F]);
        out.push_back(kAlphabet[(triple >> 12) & 0x3F]);
        out.push_back(remaining == 2 ? kAlphabet[(triple >> 6) & 0x3F] : '=');
        out.push_back('=');
    }
    return out;
}

}  // namespace ksi::safety
