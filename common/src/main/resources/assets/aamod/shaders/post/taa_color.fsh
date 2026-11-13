#version 330

uniform sampler2D InSampler;

layout(std140) uniform ColorConfig {
    vec4 OffsetAndBoundary;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = clamp(texCoord + OffsetAndBoundary.xy, OffsetAndBoundary.zw, vec2(1.0) - OffsetAndBoundary.zw);
    fragColor = texture(InSampler, uv);
}
