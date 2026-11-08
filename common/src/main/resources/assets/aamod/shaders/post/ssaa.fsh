#version 330

uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 footprint = InSize / OutSize;
    vec2 lower = texCoord * InSize - 0.5 * footprint;
    vec2 upper = lower + footprint;
    ivec2 first = ivec2(floor(lower));
    ivec2 last = ivec2(InSize) - ivec2(1);
    vec4 color = vec4(0.0);
    float total = 0.0;
    for (int row = 0; row < 5; row++) {
        for (int column = 0; column < 5; column++) {
            ivec2 samplePixel = first + ivec2(column, row);
            vec2 coverage = max(vec2(0.0), min(upper, vec2(samplePixel + ivec2(1))) - max(lower, vec2(samplePixel)));
            float weight = coverage.x * coverage.y;
            if (weight > 0.0) {
                color += texelFetch(InSampler, clamp(samplePixel, ivec2(0), last), 0) * weight;
                total += weight;
            }
        }
    }
    fragColor = color / max(total, 0.000001);
}
