#version 330

uniform sampler2D CurrentSampler;
uniform sampler2D DepthSampler;
uniform sampler2D HistorySampler;
uniform sampler2D HistoryDepthSampler;
uniform sampler2D PreviousSampler;
uniform sampler2D MotionSampler;

layout(std140) uniform TemporalConfig {
    mat4 Reproject;
    vec4 Jitter;
    vec4 Settings;
    vec4 Size;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 toYCoCg(vec3 color) {
    return vec3(dot(color, vec3(0.25, 0.5, 0.25)), color.r * 0.5 - color.b * 0.5,
                color.g * 0.5 - color.r * 0.25 - color.b * 0.25);
}

vec3 fromYCoCg(vec3 color) {
    return vec3(color.x + color.y - color.z, color.x + color.z, color.x - color.y - color.z);
}

void main() {
    vec2 currentUv = clamp(texCoord + Jitter.xy, Size.xy * 0.5, vec2(1.0) - Size.xy * 0.5);
    vec4 current = texture(CurrentSampler, currentUv);
    fragColor = current;
    float depth = texture(DepthSampler, currentUv).r;
    if (Settings.x <= 0.0 || depth <= 0.0000001) return;
    vec4 motionVector = texture(MotionSampler, currentUv);
    if (motionVector.a < -0.5) return;
    vec2 previousRawUv;
    float expectedDepth;
    if (motionVector.a > 0.5) {
        previousRawUv = currentUv + motionVector.xy;
        expectedDepth = motionVector.z;
    } else {
        float ndcDepth = Settings.y > 0.5 ? depth : depth * 2.0 - 1.0;
        vec4 previousClip = Reproject * vec4(currentUv * 2.0 - 1.0, ndcDepth, 1.0);
        if (previousClip.w <= 0.0) return;
        vec3 previousNdc = previousClip.xyz / previousClip.w;
        previousRawUv = previousNdc.xy * 0.5 + 0.5;
        expectedDepth = Settings.y > 0.5 ? previousNdc.z : previousNdc.z * 0.5 + 0.5;
    }
    if (any(isnan(previousRawUv)) || any(isinf(previousRawUv)) || isnan(expectedDepth) || isinf(expectedDepth)) return;
    vec2 previousUv = previousRawUv - Jitter.zw;
    vec2 border = Size.xy * 0.5;
    if (any(lessThan(previousUv, border)) || any(greaterThan(previousUv, vec2(1.0) - border)) ||
        any(lessThan(previousRawUv, border)) || any(greaterThan(previousRawUv, vec2(1.0) - border))) return;
    float storedDepth = texture(HistoryDepthSampler, previousRawUv).r;
    float tolerance = max(0.000001, max(abs(expectedDepth) * 0.02, fwidth(expectedDepth) * 2.0));
    if (expectedDepth <= 0.0 || expectedDepth > 1.0 || abs(storedDepth - expectedDepth) > tolerance) return;
    vec3 minimumColor = vec3(1e10);
    vec3 maximumColor = vec3(-1e10);
    vec3 mean = vec3(0.0);
    vec3 squareMean = vec3(0.0);
    vec3 previousMean = vec3(0.0);
    vec3 previousSquareMean = vec3(0.0);
    ivec2 currentPixel = ivec2(currentUv * Size.zw);
    ivec2 previousPixel = ivec2(previousRawUv * Size.zw);
    ivec2 maximumPixel = ivec2(Size.zw) - ivec2(1);
    for (int offsetY = -1; offsetY <= 1; offsetY++) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            ivec2 offset = ivec2(offsetX, offsetY);
            vec3 neighbor = toYCoCg(texelFetch(CurrentSampler, clamp(currentPixel + offset, ivec2(0), maximumPixel), 0).rgb);
            vec3 previousNeighbor = toYCoCg(texelFetch(PreviousSampler, clamp(previousPixel + offset, ivec2(0), maximumPixel), 0).rgb);
            minimumColor = min(minimumColor, neighbor);
            maximumColor = max(maximumColor, neighbor);
            mean += neighbor / 9.0;
            squareMean += neighbor * neighbor / 9.0;
            previousMean += previousNeighbor / 9.0;
            previousSquareMean += previousNeighbor * previousNeighbor / 9.0;
        }
    }
    vec3 deviation = sqrt(max(squareMean - mean * mean, vec3(0.0)));
    vec3 previousDeviation = sqrt(max(previousSquareMean - previousMean * previousMean, vec3(0.0)));
    minimumColor = max(minimumColor, mean - 1.25 * deviation);
    maximumColor = min(maximumColor, mean + 1.25 * deviation);
    vec3 history = clamp(toYCoCg(texture(HistorySampler, previousUv).rgb), minimumColor, maximumColor);
    vec3 previousRaw = toYCoCg(texture(PreviousSampler, previousRawUv).rgb);
    vec3 currentColor = toYCoCg(current.rgb);
    vec3 samplingUncertainty = 2.0 * max(deviation, previousDeviation);
    float difference = length(max(abs(previousRaw - currentColor) - samplingUncertainty, vec3(0.0)));
    float reactive = 1.0 - smoothstep(0.04, 0.15, difference);
    float motion = length((previousUv - texCoord) * Size.zw);
    float weight = Settings.x * reactive * mix(1.0, 0.65, clamp(motion / 16.0, 0.0, 1.0));
    fragColor = vec4(max(fromYCoCg(mix(currentColor, history, weight)), vec3(0.0)), current.a);
}
