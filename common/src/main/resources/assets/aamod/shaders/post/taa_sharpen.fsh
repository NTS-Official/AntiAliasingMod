#version 330

uniform sampler2D InSampler;

layout(std140) uniform TemporalConfig {
    mat4 Reproject;
    vec4 Jitter;
    vec4 Settings;
    vec4 Size;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 center = texture(InSampler, texCoord);
    fragColor = center;
    if (Settings.z <= 0.0) return;

    vec3 north = textureOffset(InSampler, texCoord, ivec2(0, 1)).rgb;
    vec3 south = textureOffset(InSampler, texCoord, ivec2(0, -1)).rgb;
    vec3 east = textureOffset(InSampler, texCoord, ivec2(1, 0)).rgb;
    vec3 west = textureOffset(InSampler, texCoord, ivec2(-1, 0)).rgb;
    vec3 minimumColor = min(center.rgb, min(min(north, south), min(east, west)));
    vec3 maximumColor = max(center.rgb, max(max(north, south), max(east, west)));
    vec3 range = maximumColor - minimumColor;
    float contrast = max(range.r, max(range.g, range.b));
    float gain = Settings.z / (1.0 + contrast * 2.0);
    vec3 detail = center.rgb - (north + south + east + west) * 0.25;
    fragColor = vec4(clamp(center.rgb + detail * gain, minimumColor, maximumColor), center.a);
}
