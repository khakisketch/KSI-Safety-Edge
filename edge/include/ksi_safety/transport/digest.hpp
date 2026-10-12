// Transport digests and encodings shared by the MOP path (A9-2).
//
// SHA-1/SHA-256 are self-contained implementations (the edge library links
// no crypto dependency); UUIDv5 follows RFC 4122 with the DNS namespace
// constant, byte-compatible with Python `uuid` and the Kotlin Uuid5 port.
#ifndef KSI_SAFETY_TRANSPORT_DIGEST_HPP
#define KSI_SAFETY_TRANSPORT_DIGEST_HPP

#include <cstddef>
#include <cstdint>
#include <string>

namespace ksi::safety {

// Lowercase hex SHA-256 (64 chars).
std::string Sha256Hex(const std::uint8_t* data, std::size_t len);
std::string Sha256Hex(const std::string& bytes);

// Lowercase hex SHA-1 (40 chars) — UUIDv5 name hashing.
std::string Sha1Hex(const std::uint8_t* data, std::size_t len);

// RFC 4122 v5 UUID text (lowercase) from a namespace UUID text + name.
// `namespace_uuid` must be canonical 8-4-4-4-12 hex.
std::string Uuid5(const std::string& namespace_uuid, const std::string& name);

// RFC 4122 DNS namespace + name (Python `uuid.uuid5(uuid.NAMESPACE_DNS, n)`).
std::string Uuid5Dns(const std::string& name);

// Standard base64 (RFC 4648 with padding).
std::string Base64Encode(const std::uint8_t* data, std::size_t len);

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_DIGEST_HPP
