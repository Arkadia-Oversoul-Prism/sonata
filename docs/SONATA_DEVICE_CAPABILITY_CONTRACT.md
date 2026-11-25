# SONATA-REQ-DEVICE-001 — Automatic Device Capability Detection

**Status:** Implemented in source; CI verification pending  
**Scope:** Device discovery and capability reporting only  
**Runtime decision:** Explicitly out of scope

## Product requirement

Sonata discovers device capabilities automatically. A user must never be asked
to provide their phone model, chipset, RAM, Android version, CPU architecture,
or accelerator details.

## Facts collected locally

`AndroidDeviceCapabilityProbe` reads:

- Manufacturer, model and Android device codename
- Android API level
- Supported ABIs
- Available processor count
- Total and currently available RAM
- Available app-filesystem storage
- Whether Android advertises the Vulkan hardware feature
- Current thermal status on API 29+

The probe requires no permissions and performs no network access. The values are
held in memory; this change adds no persistence, analytics, telemetry or upload.

## Interpretation boundaries

- Vulkan presence is a platform hint, **not** proof that a speech runtime or
  model can use GPU acceleration.
- A current thermal status is not a sustained thermal benchmark.
- `thermalValidated` therefore remains false until a separate sustained test
  passes.
- ABI, RAM and storage facts are inputs to later compatibility checks, not a
  model/runtime selection by themselves.
- This implementation does not select, download, load or execute a speech model.
- No production runtime is selected or approved by this change.

## Verification

The pure `DeviceCapabilityDetector` is unit tested for:
- Mapping Android facts into the capability record
- ARM64 ABI detection
- Thermal validation remaining false
- Vulkan not being treated as runtime availability
- Safe handling of absent/invalid resource values

The Android probe is a thin platform adapter. Its end-to-end values still need
physical-device observation on the household Samsung A23 and OPPO handset.

## Acceptance boundary

Passing JVM tests verifies the mapping contract, not actual device readings.
Physical-device validation remains separate. No model, runtime, release,
signature, deployment or merge is authorized by this implementation.
