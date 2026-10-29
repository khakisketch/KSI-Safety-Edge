// MOP 전송 QoS — 제어 차선 우선 · 예산 상한 · 백로그 가시성.
//
// WHY: 한 번 Flush가 큐를 끝까지 비우면서 링크 mutex를 잡고 있었다.
// 증거 아티팩트(대용량)가 쌓이면 RC의 명령·상태 질의·중지가 그 뒤에 밀려
// "조종자가 기다리는 메시지"가 "조용한 대량 전송" 뒤에 서게 된다. 이 시험은
// 그 구멍을 닫는다:
//
// ① 제어(CONTROL_RELIABLE)는 대기 중인 어떤 대량보다 먼저 나간다.
// ② 한 번의 Flush는 정해진 개수·바이트까지만 쓰고 나머지는 큐에 남는다.
// ③ backlog()로 "아직 안 나간 것"을 숨기지 않는다.
// ④ 쓰기 실패는 큐를 비우지 않는다(기존 보존 계약 유지).
// ⑤ 같은 클래스 안의 순서는 보존된다(증거의 순서 훼손 금지).

#include "ksi_safety/transport/mop_endpoint.hpp"
#include "ksi_safety/transport/mop_link.hpp"
#include "ksi_safety/transport/json.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "support/check.hpp"

using ksi::safety::JsonValue;
using ksi::safety::MopEdgeEndpoint;
using ksi::safety::MopLink;

namespace {

struct FakeTransport : ksi::safety::MopByteTransport {
    bool fail = false;
    std::string written;
    std::size_t writes = 0;
    bool WriteAll(const std::uint8_t* data, std::size_t len) override {
        if (fail) return false;
        written.append(reinterpret_cast<const char*>(data), len);
        ++writes;
        return true;
    }
};

// 한 번의 Flush가 쓴 바이트에서 채널 이름을 찾는다(프레임은 작은 envelope면
// 한 프레임에 그대로 들어가므로 문자열 검색으로 충분하다).
bool WroteChannel(const std::string& written, const char* channel) {
    return written.find(std::string("\"channel\":\"") + channel + "\"") !=
           std::string::npos;
}

JsonValue Payload(int index, std::size_t bytes = 0) {
    JsonValue payload = JsonValue::Obj();
    payload.Set("n", JsonValue::Int(index));
    if (bytes > 0) payload.Set("blob", JsonValue::Str(std::string(bytes, 'x')));
    return payload;
}

}  // namespace

int main() {
    std::int64_t now = 1000;
    auto clock = [&now]() { return now; };

    // 1) 기본 경로는 FIFO(핀 픽스처 바이트 보존).
    {
        FakeTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        for (int i = 0; i < 3; ++i) {
            endpoint.Send("EVIDENCE_TRANSFER", "ARTIFACT_CHUNK", Payload(i),
                          /*reliable=*/false);
        }
        endpoint.Send("EVENT_RELIABLE", "OBSERVATION", Payload(9),
                      /*reliable=*/false);
        endpoint.Send("CONTROL_RELIABLE", "MISSION_STATUS", Payload(9),
                      /*reliable=*/false);
        CHECK(endpoint.outboxSize() == 5, "five envelopes queued");
        CHECK(link.backlog() == 5, "backlog sees the queue");

        // 기본 경로는 FIFO — 핀 픽스처의 바이트 순서를 보존한다.
        const std::size_t sent = link.Flush();
        CHECK(sent == 5, "one flush drains a small queue");
        CHECK(link.backlog() == 0, "queue empty after the flush");
        const std::size_t bulk_at = transport.written.find("EVIDENCE_TRANSFER");
        const std::size_t event_at = transport.written.find("EVENT_RELIABLE");
        const std::size_t control_at = transport.written.find("CONTROL_RELIABLE");
        CHECK(bulk_at != std::string::npos, "bulk envelope was sent");
        CHECK(event_at != std::string::npos, "event envelope was sent");
        CHECK(control_at != std::string::npos, "control envelope was sent");
        CHECK(bulk_at < event_at, "the default path stays FIFO");
        CHECK(event_at < control_at, "the default path stays FIFO to the end");
        // 같은 클래스 안의 FIFO: 대량 0 → 1 → 2.
        const std::size_t n0 = transport.written.find("\"n\":0");
        const std::size_t n1 = transport.written.find("\"n\":1");
        const std::size_t n2 = transport.written.find("\"n\":2");
        CHECK(n0 != std::string::npos && n1 != std::string::npos &&
                  n2 != std::string::npos,
              "bulk payloads keep their order");
        CHECK(n0 < n1 && n1 < n2, "same-class order is preserved");
    }

    // 2) 개수 예산: 한 번에 32개까지만, 나머지는 큐에 남는다.
    {
        FakeTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        for (int i = 0; i < 100; ++i) {
            endpoint.Send("EVIDENCE_TRANSFER", "ARTIFACT_CHUNK", Payload(i),
                          /*reliable=*/false);
        }
        const std::size_t first = link.Flush();
        CHECK(first == 32, "the envelope budget bounds one flush");
        CHECK(link.backlog() == 68, "the rest waits in the queue");
        const std::size_t second = link.Flush();
        CHECK(second == 32, "the next flush continues draining");
        CHECK(link.backlog() == 36, "the queue shrinks by what was sent");
    }

    // 3) 바이트 예산: 큰 payload가 쌓여도 한 번에 쓰는 양은 제한된다.
    {
        FakeTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        for (int i = 0; i < 6; ++i) {
            endpoint.Send("EVIDENCE_TRANSFER", "ARTIFACT_CHUNK",
                          Payload(i, 100 * 1024), /*reliable=*/false);
        }
        const std::size_t sent = link.Flush();
        CHECK(sent == 2, "the byte budget stops the batch");
        CHECK(link.backlog() == 4, "the remaining bulk stays queued");
    }

    // 4) 쓰기 실패는 큐를 비우지 않는다(기존 보존 계약).
    {
        FakeTransport transport;
        transport.fail = true;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        endpoint.Send("EVENT_RELIABLE", "OBSERVATION", Payload(1),
                      /*reliable=*/false);
        CHECK(link.Flush() == 0, "a failed write sends nothing");
        CHECK(link.backlog() == 1, "the envelope stays queued for the retry");
    }

    // 5) 대기 중인 대량 사이로 새 제어가 들어와도 먼저 나간다.
    {
        FakeTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        for (int i = 0; i < 50; ++i) {
            endpoint.Send("EVIDENCE_TRANSFER", "ARTIFACT_CHUNK", Payload(i),
                          /*reliable=*/false);
        }
        endpoint.Send("CONTROL_RELIABLE", "SESSION_STOP", Payload(0),
                      /*reliable=*/false);
        transport.written.clear();
        // 우선 차선 ON: 사람이 기다리는 제어 메시지가 대량 뒤에 서지 않는다.
        const std::size_t sent = link.Flush(1, 256U * 1024U, true);
        CHECK(sent == 1, "one envelope per flush when asked");
        CHECK(WroteChannel(transport.written, "CONTROL_RELIABLE"),
              "the control command is not stuck behind the bulk");
        CHECK(link.backlog() == 50, "the bulk is still there, untouched");
    }

    // 6) 우선 차선 ON: 제어 → 관측 → 대량 순으로 건너뛴다(클래스 내 FIFO 유지).
    {
        FakeTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        for (int i = 0; i < 3; ++i) {
            endpoint.Send("EVIDENCE_TRANSFER", "ARTIFACT_CHUNK", Payload(i),
                          /*reliable=*/false);
        }
        endpoint.Send("EVENT_RELIABLE", "OBSERVATION", Payload(9),
                      /*reliable=*/false);
        endpoint.Send("CONTROL_RELIABLE", "MISSION_STATUS", Payload(9),
                      /*reliable=*/false);
        const std::size_t sent = link.Flush(32, 256U * 1024U, true);
        CHECK(sent == 5, "the lane drains the whole queue");
        const std::size_t control_at = transport.written.find("CONTROL_RELIABLE");
        const std::size_t event_at = transport.written.find("EVENT_RELIABLE");
        const std::size_t bulk_at = transport.written.find("EVIDENCE_TRANSFER");
        CHECK(control_at < event_at, "control precedes the observation");
        CHECK(event_at < bulk_at, "the observation precedes bulk evidence");
        const std::size_t n0 = transport.written.find("\"n\":0");
        const std::size_t n1 = transport.written.find("\"n\":1");
        const std::size_t n2 = transport.written.find("\"n\":2");
        CHECK(n0 < n1 && n1 < n2, "same-class order is preserved");
    }

    // 7) 느린(막힌) 전송 중에도 다른 경로는 멈추지 않는다.
    //    이전 구현은 전송 중 링크 mutex를 잡고 있어서, RC의 명령·상태
    //    질의·중지가 멈춘 전송 뒤에 서 있었다.
    {
        class BlockingTransport : public ksi::safety::MopByteTransport {
         public:
            std::atomic<bool> entered{false};
            std::atomic<bool> release{false};
            bool WriteAll(const std::uint8_t*, std::size_t) override {
                entered.store(true);
                while (!release.load()) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
                return true;
            }
        };
        BlockingTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        endpoint.Send("EVENT_RELIABLE", "OBSERVATION", Payload(1),
                      /*reliable=*/false);
        std::thread writer([&link] { link.Flush(); });
        // 전송이 실제로 막혔을 때까지 기다린다.
        for (int i = 0; i < 200 && !transport.entered.load(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(transport.entered.load(), "전송이 시작되었다");
        // 막힌 전송 중에도 링크 상태 조회와 새 발신이 즉시 끝나야 한다.
        const auto before = std::chrono::steady_clock::now();
        const std::size_t queued = link.backlog();
        link.WithEndpoint([](ksi::safety::MopEdgeEndpoint* ep) {
            ep->Send("CONTROL_RELIABLE", "HEARTBEAT", Payload(0),
                     /*reliable=*/false);
        });
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - before);
        CHECK(elapsed.count() < 200, "막힌 전송 중에도 링크 조회가 멈추지 않는다");
        CHECK(queued == 0, "백로그 조회가 즉시 답한다");
        transport.release.store(true);
        writer.join();
    }

    // 8) 멈춘 전송은 "연결 단절"로 보고한다 — 무한 대기 금지.
    //    실측(Manifold 13분 soak): 상대가 읽기를 멈추면 분석 루프가 통째로
    //    멈췄다. 링크 mutex는 안 잡지만 "분석이 진행 중"이라는 사실이 거짓이 된다.
    {
        class StallingTransport : public ksi::safety::MopByteTransport {
         public:
          bool WriteAll(const std::uint8_t*, std::size_t) override {
            std::this_thread::sleep_for(std::chrono::milliseconds(120));
            return true;
          }
        };
        StallingTransport transport;
        MopEdgeEndpoint endpoint("flight-1", clock);
        MopLink link(&transport, &endpoint);
        link.set_write_stall_budget(50);
        endpoint.Send("EVENT_RELIABLE", "OBSERVATION", Payload(1),
                      /*reliable=*/false);
        const std::size_t sent = link.Flush();
        CHECK(sent == 0, "멈춘 전송은 성공으로 세지 않는다");
        CHECK(link.writeStalled(), "쓰기 정지가 래치된다");
        CHECK(link.writeStalls() == 1, "정지 횟수가 센다");
        CHECK(!link.writeStallReason().empty(), "사유가 이름으로 남는다");
        CHECK(link.backlog() == 1, "envelope는 재시도 위해 큐에 남는다");
        // 재연결 후에는 래치를 지운다.
        link.clearWriteStall();
        CHECK(!link.writeStalled(), "재연결 후 래치를 지운다");
    }

    return edge_test::failures == 0 ? 0 : 1;
}
