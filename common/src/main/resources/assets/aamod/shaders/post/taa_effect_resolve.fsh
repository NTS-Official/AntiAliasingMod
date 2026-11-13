#version 330

#moj_import <aamod:taa_effect_conflict.glsl>

uniform sampler2D CurrentSampler;
uniform sampler2D DepthSampler;
uniform sampler2D MotionSampler;
uniform sampler2D IdentitySampler;
uniform sampler2D ConflictSampler;
#ifndef EFFECT_WEIGHTS
uniform sampler2D HistorySampler;
#endif
uniform sampler2D HistoryDepthSampler;
uniform sampler2D HistoryIdentitySampler;
uniform sampler2D PreviousSampler;

layout(std140) uniform EffectConfig {
    vec4 Jitter;
    vec4 Settings;
    vec4 Size;
};

in vec2 texCoord;
out vec4 fragColor;

bool sameIdentity(vec3 first, vec3 second) {
    return all(equal(ivec3(first * 255.0 + 0.5), ivec3(second * 255.0 + 0.5)));
}

bool inside(vec2 uv) {
    return all(greaterThanEqual(uv, Size.xy * 0.5)) && all(lessThanEqual(uv, vec2(1.0) - Size.xy * 0.5));
}

void main() {
    vec2 rawUv = clamp(texCoord + Jitter.xy, Size.xy * 0.5, vec2(1.0) - Size.xy * 0.5);
    vec4 identity = texture(IdentitySampler, rawUv);
    float currentDepth = texture(DepthSampler, rawUv).r;
    float currentTolerance = max(0.000001, max(abs(currentDepth) * 0.002, fwidth(currentDepth) * 1.5));
    vec4 motion = texture(MotionSampler, rawUv);
    float expectedDepth = motion.z;
    float tolerance = max(0.000001, max(abs(expectedDepth) * 0.002, fwidth(expectedDepth) * 1.5));
    vec4 sampledCurrent = texture(CurrentSampler, rawUv);
    if (all(equal(sampledCurrent, vec4(0.0)))) {
        fragColor = vec4(0.0);
        return;
    }
    vec2 currentPixel = rawUv * Size.zw - 0.5;
    ivec2 currentBase = ivec2(floor(currentPixel));
    vec2 currentFraction = fract(currentPixel);
    vec4 reconstructed = vec4(0.0);
    float currentSupport = 0.0;
    for (int offsetY = 0; offsetY <= 1; offsetY++) {
        for (int offsetX = 0; offsetX <= 1; offsetX++) {
            ivec2 tap = clamp(currentBase + ivec2(offsetX, offsetY), ivec2(0), ivec2(Size.zw) - 1);
            vec4 tapIdentity = texelFetch(IdentitySampler, tap, 0);
            float tapDepth = texelFetch(DepthSampler, tap, 0).r;
            if (!sameIdentity(identity.rgb, tapIdentity.rgb) || abs(tapDepth - currentDepth) > currentTolerance) continue;
            float weight = (offsetX == 0 ? 1.0 - currentFraction.x : currentFraction.x) *
                (offsetY == 0 ? 1.0 - currentFraction.y : currentFraction.y);
            reconstructed += texelFetch(CurrentSampler, tap, 0) * weight;
            currentSupport += weight;
        }
    }
    vec4 current = currentSupport >= 0.999 ? reconstructed : texelFetch(CurrentSampler, ivec2(rawUv * Size.zw), 0);
#ifdef EFFECT_WEIGHTS
    float covered = current.a > 0.00001 && any(greaterThan(identity.rgb, vec3(0.0))) ? 1.0 : 0.0;
    fragColor = vec4(covered, 0.0, 0.0, 0.0);
#else
    fragColor = current;
#endif
    if (Settings.x <= 0.0 || currentSupport < 0.999 || current.a <= 0.00001 || identity.a <= 0.0 || motion.a <= 0.0) return;
    vec2 previousRawUv = rawUv + motion.xy;
    vec2 previousUv = previousRawUv - Jitter.zw;
    if (any(isnan(previousRawUv)) || any(isinf(previousRawUv)) || !inside(previousRawUv) || !inside(previousUv)) return;
    if (expectedDepth <= 0.0 || expectedDepth > 1.0) return;
    vec4 conflict = texture(ConflictSampler, rawUv);
    int reasons = effectConflictReasons(conflict);
    if (reasons != 0) {
#ifdef EFFECT_WEIGHTS
        fragColor.a = covered * float(reasons) / 255.0;
#endif
        return;
    }
    vec2 pixel = previousUv * Size.zw - 0.5;
    ivec2 base = ivec2(floor(pixel));
    vec2 fraction = fract(pixel);
    vec4 historical = vec4(0.0);
    float supported = 0.0;
    for (int offsetY = 0; offsetY <= 1; offsetY++) {
        for (int offsetX = 0; offsetX <= 1; offsetX++) {
            ivec2 tap = base + ivec2(offsetX, offsetY);
            vec2 tapUv = (vec2(tap) + 0.5) * Size.xy;
            vec2 tapRawUv = tapUv + Jitter.zw;
            if (!inside(tapUv) || !inside(tapRawUv)) continue;
            vec4 priorIdentity = texture(HistoryIdentitySampler, tapRawUv);
            float priorDepth = texture(HistoryDepthSampler, tapRawUv).r;
            if (priorIdentity.a <= 0.0 || !sameIdentity(identity.rgb, priorIdentity.rgb) ||
                abs(priorDepth - expectedDepth) > tolerance) continue;
            float weight = (offsetX == 0 ? 1.0 - fraction.x : fraction.x) * (offsetY == 0 ? 1.0 - fraction.y : fraction.y);
#ifndef EFFECT_WEIGHTS
            historical += texelFetch(HistorySampler, tap, 0) * weight;
#endif
            supported += weight;
        }
    }
    if (supported < 0.999) return;
    vec4 minimumColor = vec4(1e10);
    vec4 maximumColor = vec4(-1e10);
    ivec2 center = ivec2(rawUv * Size.zw);
    ivec2 maximumPixel = ivec2(Size.zw) - 1;
    for (int offsetY = -1; offsetY <= 1; offsetY++) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            ivec2 neighbor = clamp(center + ivec2(offsetX, offsetY), ivec2(0), maximumPixel);
            vec4 neighborIdentity = texelFetch(IdentitySampler, neighbor, 0);
            if (!sameIdentity(identity.rgb, neighborIdentity.rgb)) continue;
            vec4 color = texelFetch(CurrentSampler, neighbor, 0);
            minimumColor = min(minimumColor, color);
            maximumColor = max(maximumColor, color);
        }
    }
    vec4 previousRaw = texture(PreviousSampler, previousRawUv);
    vec4 uncertainty = max(maximumColor - minimumColor, vec4(0.002));
    vec4 difference = max(abs(previousRaw - current) - uncertainty, vec4(0.0));
    float change = max(max(difference.r, difference.g), max(difference.b, difference.a));
    float reactive = 1.0 - smoothstep(0.015, 0.12, change);
    float weight = Settings.x * min(identity.a, motion.a) * reactive;
#ifdef EFFECT_WEIGHTS
    fragColor = vec4(covered, weight > 0.0 ? 1.0 : 0.0, weight, 0.0);
#else
    historical = clamp(historical, minimumColor, maximumColor);
    fragColor = mix(current, historical, weight);
    fragColor.a = clamp(fragColor.a, 0.0, 1.0);
#endif
}
