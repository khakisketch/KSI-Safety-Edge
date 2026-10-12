// MOP byte framing (A9-2) — the wire contract between the Manifold PSDK
// application and the Android RC app:
//
//   frame := u32be(chunkLength) | u8(flags) | chunk[chunkLength]
//             flags bit0 MORE = another chunk of the same message follows
//
// Byte-identical to the Kotlin implementation
// (android-rc/rc-msdk/.../MopFrameCodec.kt); golden vectors in both suites
// pin the same bytes. There is NO resync marker: a corrupt chunk length
// cannot be recovered mid-stream, so [MopReassembler] discards its buffered
// state and counts the error — the link layer must reconnect and start a
// fresh reassembler (see MopLink).
#ifndef KSI_SAFETY_TRANSPORT_MOP_FRAME_HPP
#define KSI_SAFETY_TRANSPORT_MOP_FRAME_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ksi::safety {

struct MopFrameCodec {
    static constexpr std::uint32_t kDefaultMaxChunk = 64U * 1024U;
    static constexpr std::uint8_t kFlagMore = 0x01;

    // Splits one message into 1..n frames (each chunk <= max_chunk bytes).
    static std::vector<std::string> Encode(const std::string& message,
                                           std::uint32_t max_chunk =
                                               kDefaultMaxChunk);
};

class MopReassembler {
 public:
    explicit MopReassembler(
        std::size_t max_message_bytes = 4U * 1024U * 1024U,
        std::uint32_t max_chunk_bytes = MopFrameCodec::kDefaultMaxChunk)
        : max_message_bytes_(max_message_bytes),
          max_chunk_bytes_(max_chunk_bytes) {}

    // Discard any partial frame/message state (transport reconnect/reset).
    void Reset();

    // Feed raw transport bytes; appends every COMPLETE message to `out`.
    // A chunk above max_chunk_bytes_ or a message above max_message_bytes_
    // is a corrupt/desynced stream: the state is dropped, `errors()` grows,
    // and this feed stops (no resync marker exists).
    void Feed(const std::uint8_t* data, std::size_t len,
              std::vector<std::string>* out);

    std::uint64_t errors() const { return errors_; }

 private:
    std::size_t max_message_bytes_;
    std::uint32_t max_chunk_bytes_;
    std::uint8_t header_[5] = {0, 0, 0, 0, 0};
    std::size_t header_read_ = 0;
    std::int64_t chunk_length_ = -1;
    std::size_t chunk_received_ = 0;
    bool more_ = false;
    std::string message_;
    std::uint64_t errors_ = 0;
};

}  // namespace ksi::safety

#endif  // KSI_SAFETY_TRANSPORT_MOP_FRAME_HPP
