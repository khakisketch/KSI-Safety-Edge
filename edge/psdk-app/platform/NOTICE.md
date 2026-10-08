# Platform glue provenance

The `*.c` / `*.h` files in this directory are DJI PSDK sample-derived
(MIT) platform glue, taken via the Site Intelligence adaptation at
`KSI-Site-Intelligence-Platform@cedeb27eb8aa49b3f2df3414a9a022dd544d5089`
(`archive/acquisition-2026/edge/psdk-tap/src/platform/`). Code below each
file's provenance header is unchanged from the source revision.

| File | Original SHA256 |
|---|---|
| `osal_glue.c` | `a662e44a4981155f33e5dc2f2c8f370c0bf108115fb42424473c781b35bb4db7` |
| `osal_glue.h` | `3ffc3ccb27ae958f3fb7a2748aa3a5abe65ea18b317ff7e45274e9cd98144433` |
| `socket_glue.c` | `11544c928d33ab28385a2696fdbe0e7349093b7e70f96dbcb1a3bdb66e1c94cb` |
| `socket_glue.h` | `8f388db0582f2d2fd83d6f94382dbb45931e8eed068313da2a0c1fb8366299d9` |
| `usb_bulk_glue.c` | `61bb7d5dada66a3608deca4def68ae9c58c61697e379f0ed228032bbf87ec65a` |
| `usb_bulk_glue.h` | `dbb9159cabdda8799ae93e1174b4054bcf8c58361424a87f4217c9275bb203b9` |
| `logger_console_glue.c` | `56f2945a7f0d6f651aafaa71ac32db632d65021125a32e2a602210c2ddc4c9a8` |
| `logger_console_glue.h` | `ad60b36f6454a9acd3861c70e6d159b86c19c445c97883965513a65c61010184` |

Verify with (from an SI checkout at that commit):

```sh
git -C <SI> show cedeb27:archive/acquisition-2026/edge/psdk-tap/src/platform/<file> | sha256sum
```

Open items carried over from the glue comments: the USB bulk
channel/interface map (bulk4/5) is unresolved until verified on real
hardware — see `usb_bulk_glue.c` and the app README.
