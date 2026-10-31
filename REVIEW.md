# Native-code review

This repository is prepared for Modrinth's source-code request. The only bundled native executable is the Linux x86-64 Rust library. No other platforms are claimed by this candidate.

## Review procedure

1. Inspect `native/src/` and the JNI entry points. Rust provides section connectivity, transparency sorting and synchronous OpenGL command dispatch. Buffer sizes and command bounds are checked; OpenGL function pointers come from the caller. See the Rust contract tests and Java native bridge classes.
2. Inspect `src/main/java/`, especially `NativeBridge`, `NativeBinary` and `LocalNativeBuild`. The loader checks the native container and recorded checksum. An installed Cargo toolchain can compile the bundled source locally; the mod does not download compilers. The CLI exposes the same native self-test.
3. Run the **Review release** GitHub Actions workflow for the exact commit. It uses a digest-pinned official Rust container, pinned compiler/Maven/JDK versions and commit-pinned Actions. All source input hashes and the bundled native checksum are stored in `META-INF/trinium-build.json`.
4. The workflow builds twice from clean source directories with different paths. Each actual JAR is checked for unexpected binaries/classes, Rust source correspondence and license metadata, then run with `-Xcheck:jni --require-native --selftest`. The entire JAR must match byte-for-byte. A failure prevents a successful workflow.
5. Download the successful run's `Trinium-0.1.0-mc26.3.jar` and `SHA256SUMS.txt`; use that unmodified JAR for submission. Share the exact source commit and workflow URL with moderation. Local builds are diagnostic evidence, not proof that a GitHub run has executed.

Build artifacts include both independent JARs, Maven/Rust test logs, JNI test logs, `reproducibility.json`, checksums and the exact source archive. A container digest pins the native compiler/linker environment; unrelated local compilers and operating systems can produce different bytes. No claim is made that historical uploads were built through this new workflow.

## Licensing and disclosures

The candidate's Trinium-specific code is All Rights Reserved, with explicit private review/build permission for Modrinth moderation. Runtime and third-party notices stay intact. Historical MIT-licensed copies retain their original permissions; they have not been retroactively relicensed.

The code and documentation have substantial AI assistance. Declare the applicable AI-generated code/text disclosures. Modrinth's rules can prohibit public listing of primarily AI-generated projects; source availability and reproducible builds do not override that rule. Ask moderation whether an unlisted distribution is appropriate rather than misrepresenting the project's origin.

Policies checked: https://modrinth.com/legal/rules and https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai .
