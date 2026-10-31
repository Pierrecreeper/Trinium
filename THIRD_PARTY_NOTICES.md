# Third-party origins

The Trinium-specific source in this review candidate is All Rights Reserved to pierrecreeper. The earlier MIT-licensed distribution keeps its original permissions. Rewriting an attribution header does not make third-party work original; third-party rights are preserved here.

## Bundled native code

`natives/linux-x86_64/libtrinium_native.so` is built from `native/Cargo.toml`, `native/Cargo.lock`, and all four `native/src/*.rs` modules. The Cargo lockfile declares only `trinium_native`; there are no external Cargo crate dependencies. All four Rust source modules and both Cargo files are also embedded in the JAR under `trinium-build/native/`.

Rust links portions of its standard library and compiler runtime into a native output. Their original licenses remain applicable. This package retains the installed Rust 1.98.0 library copyright inventory and the accompanying license texts under `src/main/resources/META-INF/third-party/`, which are included in the JAR. These notices are not relicensed as Trinium code.

- Compiler: Rust 1.98.0, commit `88d9e12ae178fab0fb5cc050a94da85685d449ea`.
- Source: https://github.com/rust-lang/rust/tree/88d9e12ae178fab0fb5cc050a94da85685d449ea/library
- Copyright inventory: `META-INF/third-party/rust-1.98.0-COPYRIGHT-library.html`.
- Rust toolchain origin: https://www.rust-lang.org/tools/install
- Pinned native build environment: `ci/environment.json`, official `library/rust` container by immutable digest.

The Linux output links against the operating system's `libc` and `libgcc_s`. Their shared libraries are not redistributed inside the JAR. Origins: https://sourceware.org/glibc/ and https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html . The Rust-provided GCC runtime-exception and license notices are retained with the runtime inventory.

## Java dependencies

Java dependency coordinates, versions and scopes are listed in `pom.xml`. They are compile-time, provided or test dependencies; the mod does not shade them. The reviewed JAR inventory rejects nested JARs and classes outside the Trinium namespace except the project's original package-access bridge `com/mojang/renderpearl/backend/opengl/TriniumGlDrawBridge.class`. That bridge is authored interoperation code, not a redistributed Minecraft implementation class.

Minecraft's official compile-time client is downloaded from the Mojang URL in `scripts/minecraft-client.json` and checked for the recorded size and SHA-1. It is excluded from the source repository, source archive and mod JAR. Its own terms remain applicable. https://www.minecraft.net/en-us/eula

Fabric Loader, Mixin, MixinExtras, LWJGL, JOML, and the game's other libraries keep their own licenses. Their exact versions and source origins can be located using the Maven coordinates in the POM; no ownership of those libraries is claimed. Optional Iris, Sodium, resource packs and third-party shader packs are not redistributed.

## Project provenance

The project started from the user's Trinium export, then was substantially developed using generative-AI assistance, including Java/Rust code, tests and documentation. The inspected source files identify pierrecreeper as project owner; no separately attributed third-party application implementation was identified in this source inventory. This is an inventory finding, not an independent guarantee of originality or rights ownership. The logo was supplied by the user; its separate creation history was not established here.
