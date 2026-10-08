// provenance: ClockRegistry verification gating tests.

#include "ksi_safety/time/provenance.hpp"

#include <cstdio>

#include "support/check.hpp"

using ksi::safety::ClockRegistry;
using ksi::safety::TimeSourceKind;
using ksi::safety::Timestamp;

int main() {
    // Make without Verify -> verified=false (both kinds).
    {
        ClockRegistry reg;
        Timestamp dev =
            reg.Make("cam0", TimeSourceKind::kDeviceSensor, 1234);
        CHECK(!dev.verified, "device ts unverified without Verify");
        CHECK(dev.kind == TimeSourceKind::kDeviceSensor, "device kind kept");
        CHECK(dev.value_ns == 1234, "device value kept");
        Timestamp host =
            reg.Make("host_mono", TimeSourceKind::kHostReceive, 5678);
        CHECK(!host.verified, "host ts unverified without Verify");
        CHECK(!reg.IsVerified("cam0", TimeSourceKind::kDeviceSensor),
              "IsVerified false initially");
    }
    // After Verify(kDeviceSensor) -> verified=true for that pair only.
    {
        ClockRegistry reg;
        reg.Verify("cam0", TimeSourceKind::kDeviceSensor, "psdk-doc-3.1");
        CHECK(reg.IsVerified("cam0", TimeSourceKind::kDeviceSensor),
              "device domain verified after Verify");
        Timestamp dev =
            reg.Make("cam0", TimeSourceKind::kDeviceSensor, 999);
        CHECK(dev.verified, "device ts verified after Verify");
        // Verifying one kind never verifies the other kind of the same domain.
        CHECK(!reg.IsVerified("cam0", TimeSourceKind::kHostReceive),
              "host kind of same domain stays unverified");
        Timestamp host_same =
            reg.Make("cam0", TimeSourceKind::kHostReceive, 1000);
        CHECK(!host_same.verified,
              "host ts of device-verified domain stays unverified");
        CHECK(host_same.kind == TimeSourceKind::kHostReceive,
              "Verify does not change kind");
        // Other domains unaffected.
        Timestamp other =
            reg.Make("cam1", TimeSourceKind::kDeviceSensor, 1001);
        CHECK(!other.verified, "other domain stays unverified");
    }
    // Verify does not change kind: host-receive domain never yields device.
    {
        ClockRegistry reg;
        reg.Verify("host_mono", TimeSourceKind::kHostReceive, "clock-mono");
        Timestamp host =
            reg.Make("host_mono", TimeSourceKind::kHostReceive, 42);
        CHECK(host.verified, "host ts verified after host Verify");
        CHECK(host.kind == TimeSourceKind::kHostReceive,
              "host Make still yields kHostReceive (kind not upgradable)");
        Timestamp dev =
            reg.Make("host_mono", TimeSourceKind::kDeviceSensor, 43);
        CHECK(!dev.verified,
              "host-receive domain never yields verified kDeviceSensor");
        CHECK(dev.kind == TimeSourceKind::kDeviceSensor,
              "requested device kind preserved but unverified");
    }
    if (edge_test::failures == 0) {
        std::printf("provenance: all checks passed\n");
    }
    return edge_test::failures == 0 ? 0 : 1;
}
