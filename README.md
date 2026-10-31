# Trinium 0.1.0 Alpha

Client performance experiments and transparent video settings for Minecraft 26.3.

This is the first public alpha candidate. Earlier local 0.4–0.13 labels were development builds, not a public release sequence. The public version number is now **0.1.0**, as requested.

## Installation

1. Use Minecraft **26.3**, **Java 25**, and **Fabric Loader 0.20.1 or later**.
2. Put `Trinium-0.1.0-mc26.3.jar` in the client `mods` folder. Remove the previous Trinium JAR first.
3. Open **Options > Video settings** for Trinium's English video menu.

Install this mod on the client. Fabric API, Sodium and Iris are **not required** by Trinium. Extra mods may have their own dependencies. Do not label this JAR as a Forge or NeoForge mod; CurseForge is the distribution website.

The supplied native library targets **Linux x86-64**. Java fallback is available when the native library cannot load. Windows, macOS and ARM native binaries have not been validated or included in this release. No Rust compiler is needed to use the bundled Linux library.

## Included features

- Rust section-visibility helpers and stable transparency sorting, with Java fallback.
- Rust OpenGL command transport and bounded batching for identified vanilla terrain submissions.
- Mesh-owned preparation caches and model-buffer reservations.
- Conservative camera-frustum filtering for known vanilla chests. Simulation is unchanged.
- Persistent shader compilation caches with content checks and bounded storage.
- A transparent English video menu with 37 settings, small sliders, scrolling and keyboard navigation.
- A standalone **composite/final** shader loader with multiple color targets, GPU-supported HDR/SNORM formats, auxiliary history and mipmaps.

Trinium does not automatically lower render distance, quality or the selected frame-rate limit. Choose **Unlimited** if you want uncapped FPS. There are no in-game `/trinium` commands.

F3 displays `Renderer: Trinium 3D Render` only after real Rust GPU submission is active. With optional Iris/Sodium, those renderers retain ownership of rendering.

## Shaders

Put a supported pack in `shaderpacks`, then open **Video settings > Shaders**. Select a pack and choose **Apply**. Shader settings remain a draft until Apply; Cancel discards them.

The original Trinium `Trinium-Clear.zip` example provides color grading. It adds no custom shadows, lighting, water or geometry. Its source is under `examples`.

The standalone loader is a bounded post-processing implementation. **Complementary Reimagined and general Iris/OptiFine geometry/shadow packs are not supported independently yet.** A successful final-pass compilation does not establish full shaderpack compatibility. See [SHADERS.md](SHADERS.md) for supported inputs and limits.

The optional adapter has been tested with Iris **1.11.7+mc26.3** and Sodium **0.9.2+mc26.3**. These are separate projects, are not bundled, and are not prerequisites for Trinium's own subset. Other Iris versions retain their own rendering; the version-specific Trinium adapter is disabled.

Standalone shaders require OpenGL 4.3 or `GL_ARB_program_interface_query`, with its query functions available. Missing capabilities refuse shader application before replacing a working pipeline. Image storage is budgeted to 256 MiB per pipeline; an old/new transactional replacement can temporarily retain both pipelines.

## Alpha status

This is **not a complete Rust replacement for Minecraft/RenderPearl**. Geometry extraction, meshing, lighting, world generation and simulation still rely on Minecraft. All shaderpacks, three-second world creation, instant startup/reload and a general FPS improvement have not been established. Entities and chests still have a rendering cost.

Climate-distance snapshots, the direct entity writer and loading-grid batching remain **disabled by default** because their full-game performance evidence did not establish a stable improvement. Do not enable research flags on a public install merely because an isolated microbenchmark is faster.

Compatibility is tested on the reported local Linux/Intel setup. VulkanMod and Nvidium are declared incompatible. The standalone shader pipeline requires the OpenGL backend. Multiplayer protocol, movement and combat are unchanged; no server-specific anti-cheat approval is claimed.

## Configuration and troubleshooting

Trinium writes local files under `config/trinium`. It sends no telemetry and downloads no tools during startup. To disable it, close Minecraft and set `mode=off` in `config/trinium/trinium.properties`. An explicit OFF choice survives configuration migration; it also disables Trinium's shader rendering and video-screen replacement.

`diagnostic.json` contains local counters and platform information. Zero counters mean a path was not used; they are not an FPS measurement. For a bug report, include Minecraft/loader/mod versions, GPU/driver, the failing steps and relevant log excerpts. Remove personal data before sharing logs.

Shader Off stops the runtime shader even if preferences cannot be written; the UI then reports that the change only applies to the current session. An invalid replacement pack preserves the previous working shader. Updating a pack can discard obsolete saved option values, while explicitly invalid new selections are still rejected.

Documented opt-out JVM flags remain available in [SHADERS.md](SHADERS.md) and the source. Changing platform-specific flags is not required for a normal installation.

## Build and validation

Requires JDK 25, Maven 3.9+, Python 3, Cargo/Rust and a native linker. Supply the official licensed Minecraft 26.3 client locally; game libraries are not redistributed.

```sh
python3 scripts/prepare-game.py /path/to/minecraft-26.3-client.jar
mvn -B clean verify
java --enable-native-access=ALL-UNNAMED -Xcheck:jni -jar target/Trinium-0.1.0-mc26.3.jar --require-native --selftest
python3 scripts/test-portability-packaging.py
bash scripts/test-native-isolation.sh
```

Maven runs the original CPU/native contract tests through exec-maven-plugin. GPU and Minecraft tests are separate, serialized offline copied-world probes; they never use a user's account or modify the source world. The supplied multi-platform CI workflow has not been run for this release and does not constitute cross-platform validation.

## License and provenance

All Rights Reserved for Trinium-specific code, copyright 2026 pierrecreeper. Third-party runtime notices remain included. The license is included in the JAR and source bundle. No third-party shaderpacks, Minecraft implementation classes or downloaded dependencies are redistributed.

Code, tests and documentation were developed with substantial generative-AI assistance and tested as recorded in the validation report. Gameplay previews are actual, unedited test captures. Publication remains subject to CurseForge moderation; preparation of this package does not mean the project has been uploaded or approved.

## Modrinth review candidate

See [REVIEW.md](REVIEW.md) for the exact-source build procedure and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for native/runtime origins. This review candidate changes licensing and build evidence; previous game-test results belong to their original artifacts. Use the JAR from the successful review workflow for submission.
