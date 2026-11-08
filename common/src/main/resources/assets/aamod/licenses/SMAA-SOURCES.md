# SMAA upstream sources

The SMAA implementation uses the official `iryoku/smaa` source and lookup tables:

- Repository: https://github.com/iryoku/smaa
- Revision: `71c806a838bdd7d517df19192a20f0c61b3ca29d`
- Shader: `SMAA.hlsl`, imported as `shaders/include/smaa.glsl` with upstream notices retained. Text encoding and line endings are normalized for Minecraft resources.
- Lookup inputs: `Textures/AreaTexDX9.dds` and `Textures/SearchTex.dds` from the same revision.
- License: `SMAA-LICENSE.txt` in this directory and the notice in the imported shader.

Original download SHA-256:

| File | SHA-256 |
| --- | --- |
| SMAA.hlsl | `4DB53D92EF0B45661F8450303589FFABCE93C352CAE4282A6F32DD2B80EFBEB9` |
| AreaTexDX9.dds | `638B22435CB9944C7F98DE365C69C1E81039DD68E8D0A710704122C22D6C819C` |
| SearchTex.dds | `891E444B6DDE7DD98F6902C805CD04E3469892AA1667C92F764D94789477EF3A` |

## Lookup conversion

Both inputs are uncompressed DDS files. Read little-endian width at byte 16,
height at byte 12, bits per pixel at byte 88, and image data starting at byte 128.
Preserve the source row order without a vertical flip.

- Area: 160 × 560, 16 bits per pixel. For each consecutive source byte pair,
  write PNG RGBA `(first, second, 0, 255)`. The DX9 luminance/alpha pair becomes
  red/green to match the official GLSL `SMAA_AREATEX_SELECT(sample).rg`.
- Search: 64 × 16, 8 bits per pixel. For each source byte, write PNG RGBA
  `(value, value, 0, 255)`. The official shader reads the red channel.
- Output paths: `textures/effect/smaa_area.png` and `smaa_search.png`.
- Load as unorm color data with linear filtering and clamp-to-edge; do not apply
  sRGB conversion or generate mipmaps.

The GLSL 3 path and official preset macros are used. Color edge detection is
followed by blending weight calculation and neighborhood blending; subsample
indices are zero for SMAA 1x. The original center alpha is preserved by the
neighborhood wrapper. Edge and weight targets clear to zero on each use.
The final pass copies from an independent output texture to avoid sampling the
color attachment currently being written.

Preset settings remain the upstream values:

| Preset | Threshold | Search steps | Diagonal steps | Corner rounding |
| --- | --- | --- | --- | --- |
| Low | 0.15 | 4 | Disabled | Disabled |
| Medium | 0.10 | 8 | Disabled | Disabled |
| High | 0.10 | 16 | 8 | 25 |
| Ultra | 0.05 | 32 | 16 | 25 |
