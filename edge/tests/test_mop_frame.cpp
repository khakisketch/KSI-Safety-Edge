// MOP framing (A9-2): byte golden vectors SHARED with the Kotlin suite
// (android-rc/rc-msdk/src/test/.../MopFrameCodecTest.kt), partial feeds,
// corrupt chunk lengths, size caps and reset semantics.
#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

#include "ksi_safety/transport/mop_frame.hpp"
#include "support/check.hpp"

using ksi::safety::MopFrameCodec;
using ksi::safety::MopReassembler;

namespace {

std::string Hex(const std::string& bytes) {
    static const char kHex[] = "0123456789abcdef";
    std::string out;
    for (const char raw : bytes) {
        const auto b = static_cast<unsigned char>(raw);
        out.push_back(kHex[b >> 4]);
        out.push_back(kHex[b & 0x0F]);
    }
    return out;
}

std::string Concat(const std::vector<std::string>& frames) {
    std::string out;
    for (const auto& frame : frames) out += frame;
    return out;
}

std::vector<std::string> FeedSliced(MopReassembler* reassembler,
                                    const std::string& data,
                                    std::size_t slice) {
    std::vector<std::string> messages;
    for (std::size_t offset = 0; offset < data.size(); offset += slice) {
        const std::size_t n = std::min(slice, data.size() - offset);
        reassembler->Feed(
            reinterpret_cast<const std::uint8_t*>(data.data() + offset), n,
            &messages);
    }
    return messages;
}

}  // namespace

int main() {
    // --- golden vectors (mirrored byte-for-byte in the Kotlin suite) --------
    {
        const auto frames = MopFrameCodec::Encode("hello");
        CHECK(frames.size() == 1, "hello: single frame");
        CHECK(Hex(Concat(frames)) == "000000050068656c6c6f",
              "hello golden hex");
    }
    {
        const auto frames = MopFrameCodec::Encode("");
        CHECK(frames.size() == 1, "empty: still one frame");
        CHECK(Hex(Concat(frames)) == "0000000000", "empty golden hex");
    }
    {
        const auto frames = MopFrameCodec::Encode("abc", 2);
        CHECK(frames.size() == 2, "split: two frames");
        CHECK(Hex(Concat(frames)) == "00000002016162000000010063",
              "split golden hex (flags: MORE then final)");
        CHECK((static_cast<unsigned char>(frames[0][4]) &
               MopFrameCodec::kFlagMore) != 0,
              "first split frame carries MORE");
        CHECK((static_cast<unsigned char>(frames[1][4]) &
               MopFrameCodec::kFlagMore) == 0,
              "final split frame clears MORE");
    }

    // --- reassembly across partial feeds -----------------------------------
    {
        MopReassembler reassembler;
        const auto split = Concat(MopFrameCodec::Encode("abc", 2));
        const auto messages = FeedSliced(&reassembler, split, 1);
        CHECK(messages.size() == 1 && messages[0] == "abc",
              "byte-by-byte feed rebuilds the split message");
        CHECK(reassembler.errors() == 0, "no framing errors on clean feed");
    }
    {
        MopReassembler reassembler;
        const auto stream =
            Concat(MopFrameCodec::Encode("hello")) +
            Concat(MopFrameCodec::Encode("world"));
        const auto messages = FeedSliced(&reassembler, stream, 3);
        CHECK(messages.size() == 2 && messages[0] == "hello" &&
                  messages[1] == "world",
              "two messages survive odd-sliced feeds");
    }

    // --- corrupt chunk length: counted + buffered state discarded ----------
    {
        MopReassembler reassembler;
        // chunkLength 0x00010001 = 65537 > 64 KiB wire guard.
        const std::string corrupt = std::string("\x00\x01\x00\x01\x00", 5);
        std::vector<std::string> messages;
        reassembler.Feed(
            reinterpret_cast<const std::uint8_t*>(corrupt.data()),
            corrupt.size(), &messages);
        CHECK(messages.empty(), "oversize chunk produces no message");
        CHECK(reassembler.errors() == 1, "oversize chunk counted once");
        // The reassembler is clean again: a full valid frame still works.
        messages.clear();
        const auto hello = Concat(MopFrameCodec::Encode("hello"));
        reassembler.Feed(reinterpret_cast<const std::uint8_t*>(hello.data()),
                         hello.size(), &messages);
        CHECK(messages.size() == 1 && messages[0] == "hello",
              "valid frame after a corrupt one");
        CHECK(reassembler.errors() == 1, "error count unchanged by valid frame");
    }
    {
        // Message cap: two 6-byte chunks (MORE) exceed a 10-byte cap.
        MopReassembler reassembler(10);
        const std::string stream =
            std::string("\x00\x00\x00\x06\x01", 5) + "aaaaaa" +
            std::string("\x00\x00\x00\x06\x00", 5) + "bbbbbb";
        std::vector<std::string> messages;
        reassembler.Feed(reinterpret_cast<const std::uint8_t*>(stream.data()),
                         stream.size(), &messages);
        CHECK(messages.empty(), "message above cap produces no message");
        CHECK(reassembler.errors() == 1, "message cap violation counted");
    }

    // --- reset discards partial state --------------------------------------
    {
        MopReassembler reassembler;
        const auto hello = Concat(MopFrameCodec::Encode("hello"));
        reassembler.Feed(
            reinterpret_cast<const std::uint8_t*>(hello.data()), 3, nullptr);
        reassembler.Reset();
        std::vector<std::string> messages;
        reassembler.Feed(reinterpret_cast<const std::uint8_t*>(hello.data()),
                         hello.size(), &messages);
        CHECK(messages.size() == 1 && messages[0] == "hello",
              "stale partial bytes never leak across Reset");
        CHECK(reassembler.errors() == 0, "reset itself is not an error");
    }

    // --- zero chunk size is rejected (Kotlin `require` parity) --------------
    {
        bool threw = false;
        try {
            MopFrameCodec::Encode("x", 0);
        } catch (const std::invalid_argument&) {
            threw = true;
        }
        CHECK(threw, "Encode(0) rejects like the Kotlin require");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
