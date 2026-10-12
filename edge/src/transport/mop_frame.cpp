#include "ksi_safety/transport/mop_frame.hpp"

#include <cstring>
#include <stdexcept>

namespace ksi::safety {

std::vector<std::string> MopFrameCodec::Encode(const std::string& message,
                                               std::uint32_t max_chunk) {
    if (max_chunk == 0) {
        // Mirror of the Kotlin `require(maxChunk in 1..MAX_CHUNK_GUARD)`:
        // zero would loop forever below.
        throw std::invalid_argument("max_chunk must be >= 1");
    }
    std::vector<std::string> frames;
    auto make_frame = [](const char* data, std::size_t size, bool more) {
        std::string frame;
        frame.resize(5 + size);
        const auto length = static_cast<std::uint32_t>(size);
        frame[0] = static_cast<char>((length >> 24) & 0xFF);
        frame[1] = static_cast<char>((length >> 16) & 0xFF);
        frame[2] = static_cast<char>((length >> 8) & 0xFF);
        frame[3] = static_cast<char>(length & 0xFF);
        frame[4] = static_cast<char>(more ? kFlagMore : 0);
        if (size > 0) {
            std::memcpy(&frame[5], data, size);
        }
        return frame;
    };
    if (message.empty()) {
        frames.push_back(make_frame(nullptr, 0, false));
        return frames;
    }
    std::size_t offset = 0;
    while (offset < message.size()) {
        const std::size_t end =
            offset + max_chunk < message.size() ? offset + max_chunk
                                                : message.size();
        frames.push_back(make_frame(message.data() + offset, end - offset,
                                    end < message.size()));
        offset = end;
    }
    return frames;
}

void MopReassembler::Reset() {
    header_read_ = 0;
    chunk_length_ = -1;
    chunk_received_ = 0;
    more_ = false;
    message_.clear();
}

void MopReassembler::Feed(const std::uint8_t* data, std::size_t len,
                          std::vector<std::string>* out) {
    std::size_t offset = 0;
    while (offset < len) {
        if (chunk_length_ < 0) {
            const std::size_t take =
                (5 - header_read_) < (len - offset) ? (5 - header_read_)
                                                    : (len - offset);
            std::memcpy(header_ + header_read_, data + offset, take);
            header_read_ += take;
            offset += take;
            if (header_read_ < 5) break;
            chunk_length_ = (static_cast<std::int64_t>(header_[0]) << 24) |
                            (static_cast<std::int64_t>(header_[1]) << 16) |
                            (static_cast<std::int64_t>(header_[2]) << 8) |
                            static_cast<std::int64_t>(header_[3]);
            more_ = (header_[4] & MopFrameCodec::kFlagMore) != 0;
            if (chunk_length_ > static_cast<std::int64_t>(max_chunk_bytes_) ||
                message_.size() + static_cast<std::size_t>(chunk_length_) >
                    max_message_bytes_) {
                ++errors_;
                Reset();
                return;
            }
        }
        const std::size_t need =
            static_cast<std::size_t>(chunk_length_) - chunk_received_;
        const std::size_t take =
            need < (len - offset) ? need : (len - offset);
        message_.append(reinterpret_cast<const char*>(data + offset), take);
        offset += take;
        chunk_received_ += take;
        if (chunk_received_ == static_cast<std::size_t>(chunk_length_)) {
            if (!more_) {
                if (out != nullptr) out->push_back(message_);
                message_.clear();
            }
            header_read_ = 0;
            chunk_length_ = -1;
            chunk_received_ = 0;
        }
    }
}

}  // namespace ksi::safety
