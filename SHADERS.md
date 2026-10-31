# Independent Trinium shaders (0.1.0 alpha)

Trinium can compile and execute the composite/final subset documented below without Iris or Sodium.
It does not embed either mod. Fabric Loader is required; Fabric API is optional. If Iris is already
installed, the optional Iris backend remains available for its broader pack
compatibility; Trinium does not run two shader pipelines simultaneously.

The optional Trinium interface for Iris is verified specifically against
**Iris 1.11.7+mc26.3**, with **Sodium 0.9.2 for Minecraft 26.3**. It exposes the
pack's categories and presets, including Complementary Reimagined r5.9.3. With a
different Iris version, this interface reports unavailability and the Iris binary
cache is disabled; Iris retains its own rendering and normal compilation. Trinium
does not switch to its independent pipeline while Iris is installed. This does
not make Iris or Sodium mandatory for Trinium itself.

## Use

Put `Trinium-Clear.zip` in `shaderpacks`, then open Video settings > Shaders.
Click a pack and choose Apply. Shader settings edits remain a draft until Apply;
Cancel discards them. Shaders off restores normal Minecraft rendering. The selected
shader and each pack's options are saved in `config/trinium/shaders.properties`
when using the independent backend. The optional Iris backend uses Iris's own
configuration and option storage.
Saved shaders load when the first world frame is rendered after a restart.
Disabling Trinium globally also stops its independent shader pipeline. The saved
selection is retained and can reload when Trinium is enabled again; Apply refuses
to start shaders while Trinium is disabled. Shaders off remains usable. If saving
that choice fails, rendering still stops for the session and an explicit message
explains that the choice was not saved for the next launch.
When a pack update removes a saved option or an allowed value, that obsolete
preference falls back to the new pack's default; compatible preferences are kept.
Explicit invalid edits are still rejected. Settings use a 64 KiB UTF-8 byte budget;
an oversized save leaves the previous configuration intact.

Trinium Clear is an original Trinium color grading example with exposure, saturation
and contrast controls. It adds no custom shadows, lighting, water or geometry.

## Supported pack subset

- ZIP or directory with `shaders/`, optionally within one wrapper directory.
- Paired `composite.vsh` / `.fsh`, then `composite1` through `composite15`, and
  `final.vsh` / `.fsh`, in that order. At least one pair must exist.
- `world0`, `world-1`, `world1` overrides, falling back to the root programs.
  Custom dimensions currently use `world0`.
- Root or sibling `#include "..."` sources within the pack. Parent traversal is
  deliberately rejected. Include cycles, missing files and excessive expansion fail.
  Include and option definitions inside block comments are ignored.
- Explicit location 0 vertex position and location 1 UV, or the simple legacy
  fullscreen forms `gl_Vertex`, `gl_MultiTexCoord0` and `ftransform()`.
- GLSL 330 core after conversion. Simple `varying`, `texture2D`, `texture2DLod`,
  `gl_FragColor` and constant-index `gl_FragData[0..7]` forms are translated.
  Composite passes can write several color targets; the final pass has one output.
- The bounded render-target extension described below supports explicit
  output routing, floating-point HDR, auxiliary history and requested mipmaps.
- Numeric/string/boolean `#define NAME value // [allowed values]` options and
  presence toggles such as `//#define NAME // description`, with English labels
  from `shaders/lang/en_us.lang`. A boolean value remains a value; a presence
  toggle enables or removes its definition.
  Settings currently appear in one flat list; custom category/profile declarations
  are not interpreted by this backend.

Available active uniforms (arrays and different types are rejected):

| Uniform | Type | Meaning |
| --- | --- | --- |
| `colortex0`, `gcolor` | sampler2D | Latest color target 0; seeded from the complete vanilla scene each frame |
| `colortex1` through `colortex7` | sampler2D | Auxiliary composite data; initialized to their clear colors, never geometry normals/materials |
| `gdepth`, `gnormal`, `composite`, `gaux1` through `gaux4` | sampler2D | Legacy names for auxiliary color targets 1 through 7; they do not acquire gbuffer data automatically |
| `depthtex0` | sampler2D | World depth with reversed depth converted to near=0, far=1; includes the vanilla scene, with no separate translucent depth layer |
| `viewWidth`, `viewHeight`, `aspectRatio` | float | Current framebuffer dimensions |
| `frameCounter` | int | Shader frame count |
| `frameTimeCounter`, `frameTime` | float | Elapsed seconds wrapping at 3600, and time since previous shader frame |
| `worldTime`, `worldDay` | int | Overworld clock ticks modulo 24000, and day count |
| `rainStrength` | float | Current world rain strength |
| `cameraPosition` | vec3 | Current camera position |
| `near`, `far` | float | Approximate scene ranges, 0.05 and render distance in blocks |

Each image pass uses separate source/destination textures. The pipeline executes
after Minecraft's world post effects and before the GUI; Minecraft's vignette and
other GUI overlays can still affect the final screenshot. Framebuffer size changes
reallocate and initialize the working images, resetting auxiliary history. Raw GL
bindings (including pixel-unpack buffers), all touched texture units and samplers,
program, VAO, viewport, polygon mode, indexed color masks/blend enables and affected
capabilities are restored afterward,
leaving RenderPearl's state caches consistent. Fullscreen draws use Trinium's existing
Rust OpenGL command transport when it is available.

## Compatibility limits

**Complementary Reimagined r5.9.3 is not supported by the independent backend.**
Packs requiring gbuffer material passes, shadow maps, deferred/prepare/compute stages,
custom uniforms or textures, per-program flip/scale rules, or geometry-derived
normals/materials need a substantially broader world renderer. The new composite
MRT/HDR support does not provide those missing stages. Unsupported program stages,
pipeline properties and active inputs detected by the loader are rejected. This is
not a complete shaderpack validator; a successful compilation alone does not
establish compatibility outside the documented subset.
Applying only a complex pack's final color pass would not reproduce that pack.
This is not full OptiFine/Iris shaderpack compatibility or a completed Rust world renderer.

Compilation is synchronous on Apply (and first world load for a saved selection).
A rejected or failed pack leaves the previous compiled shader and configuration
intact. The new pipeline's images are allocated and framebuffer-validated at the
current resolution before replacing the old pipeline. Failed dimension-specific
replacement also retains the previous pipeline and records the error. A runtime framebuffer failure stops the additional shader renderer and
logs the reason. Source work is bounded to 4096 entries, 2 MiB per file/expanded
program, 32 MiB total text and 32 include levels. ZIP files are never extracted.

Shaders add GPU work. No FPS improvement or instant shader loading is claimed.


## Composite render targets

Composite passes accept `/* DRAWBUFFERS:041 */` or
`/* RENDERTARGETS:0,4,1 */`: fragment output locations 0, 1 and 2 write to logical
color targets 0, 4 and 1, respectively. Duplicate targets, conflicting routes and
indices outside 0–7 are rejected. The default is target 0. `final` always presents
one color directly into Minecraft's color target. Without a final program, the
latest target 0 is copied back to Minecraft.

A written target uses two distinct images. Every pass reads the current images,
writes alternate images and flips only the targets it wrote. Other targets keep
their previous contents. This avoids a read/write feedback loop even when the
same logical target is read and written by a pass.

Supported literal declarations, using `colortexN` or its legacy name:

```glsl
/* const int colortex4Format = RGBA16F; */
const bool colortex4Clear = false;
const vec4 colortex4ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const bool colortex4MipmapEnabled = true;
```

Formats are `R8`, `RG8`, `RGB8`, `RGBA8`; the corresponding `16F` and `32F`
formats; `R11F_G11F_B10F`; and `R8_SNORM`, `RG8_SNORM`, `RGB8_SNORM`,
`RGBA8_SNORM`. They are accepted only when the actual GPU supports rendering
into them. Integer formats are not supported. Formats default to RGBA8. Format
constants may be inside a block comment, as in the shaderpack convention; single
line comments do not declare metadata.

Clear defaults to true. Auxiliary targets clear to black, except target 1 which
clears to white, unless an explicit clear color is provided. `Clear=false` preserves
auxiliary history between frames; both images are initialized on first allocation
and resize. **`colortex0Clear=false` is rejected:** target 0 is seeded from the
finished vanilla world every frame, so it cannot reproduce a gbuffer pack's scene
history. Auxiliary targets provide real history without making that false promise.

A true `MipmapEnabled` directive generates a target's mip chain immediately before
that program and selects a mipmapped sampler for that program. Programs without the
directive use the base level. Literal clear colors accept one or four finite numbers,
including exponent and `f` suffix forms. Format and clear declarations are global;
conflicting declarations are rejected. Mipmap requests are per program. Conditional
metadata (`#if`, `#ifdef`, etc.) and expression-valued metadata are rejected rather
than guessed. The normal GLSL preprocessor still handles shader code.

The independent shader renderer requires OpenGL 4.3 **or**
`GL_ARB_program_interface_query`, with both required query entry points present.
It validates each linked fragment output's location and floating-point type before
allocating images. On older drivers without that extension, Apply reports a clear
error before changing GL state and preserves the previously selected pack. This
requirement is specific to Trinium shaders; Minecraft's OpenGL backend can accept
OpenGL 3.3. Iris remains a separate optional rendering path with its own requirements.

Only sampled, written or explicitly mipmapped targets are allocated. Alternate
images exist only for written targets. Allocation checks texture dimensions,
texture-unit count, simultaneous draw-buffer/color-attachment limits and, with
OpenGL 4.3, internal-format support/renderability. Each actual image is also checked
in a framebuffer. Estimated image storage, including alternate images, depth and
mip levels, is limited to 256 MiB; RGB estimates round up to RGBA storage. This is
an image budget, not a limit on all driver memory. A transactional resize or pack
replacement can temporarily keep both old and new images alive (up to roughly
512 MiB combined), in addition to Minecraft's images and driver overhead.
Allocation is transactional;
invalid dimensions, budget excess or failed GL allocation do not replace the
previous images. Shaders can still add substantial GPU/memory cost.

This implementation follows the documented routing, format and mipmap contracts
from [Iris render targets](https://shaders.properties/current/reference/constants/rendertargets/),
[Iris buffer formats](https://shaders.properties/current/reference/constants/buffer_format/),
[Iris clearing](https://shaders.properties/current/reference/constants/buffer_clear_color/),
[Iris mipmaps](https://shaders.properties/current/reference/constants/colortex_mipmaps/)
and [OptiFine's shader documentation](https://github.com/sp614x/optifine/blob/master/OptiFineDoc/doc/shaders.txt).
No Iris or OptiFine implementation code is embedded. The independent renderer
still does not support Complementary Reimagined, custom shadow passes or full
Iris/OptiFine shaderpack compatibility.
