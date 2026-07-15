# Mobile Client module boundaries

The application is a feature-first modular monolith.

- `app` is the composition root and owns top-level Navigation Compose routes.
- `feature:*` modules expose one capability UI and cannot depend directly on another feature module. The root build fails configuration if such a dependency is introduced.
- `core:contracts` holds narrow gateway, playback, clock, and connectivity boundaries used by the deterministic app-level acceptance harness.
- `platform:adaptive` owns window-characteristic policy without device-name detection.
- `design:system` owns the Quiet Screening Room theme and shared accessible presentation.

The walking skeleton intentionally does not connect server-backed content or playback. Future platform implementations satisfy the contracts without moving Emby DTOs, persistence entities, Media3 objects, or Android framework types into feature modules.
