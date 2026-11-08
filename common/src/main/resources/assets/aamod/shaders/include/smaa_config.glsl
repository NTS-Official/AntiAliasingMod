layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};
in vec2 texCoord;
out vec4 fragColor;
#define SMAA_GLSL_3
#define SMAA_RT_METRICS vec4(1.0 / OutSize, OutSize)
