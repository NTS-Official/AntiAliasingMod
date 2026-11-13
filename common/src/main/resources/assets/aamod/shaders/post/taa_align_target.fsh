#version 330

uniform sampler2D RawColor;
uniform sampler2D RawDepth;
uniform sampler2D StableColor;

layout(std140) uniform AlignmentConfig {
    vec4 Sampling;
    vec4 Settings;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 rawUv = clamp(texCoord + Sampling.xy, Sampling.zw * 0.5, vec2(1.0) - Sampling.zw * 0.5);
    ivec2 size = textureSize(RawDepth, 0);
    ivec2 support = clamp(ivec2(rawUv * vec2(size)), ivec2(0), size - 1);
    gl_FragDepth = texelFetch(RawDepth, support, 0).r;
    fragColor = Settings.x > 0.5 ? texelFetch(RawColor, support, 0) : texture(StableColor, texCoord);
}
