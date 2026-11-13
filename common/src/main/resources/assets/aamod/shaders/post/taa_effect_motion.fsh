#version 330

uniform sampler2D Sampler0;
#if !defined(EFFECT_WORLD) && EFFECT_OUTPUT == 2
uniform sampler2D LayerDepth;
#endif
#if defined(EFFECT_WORLD) || (EFFECT_OUTPUT != 1 && EFFECT_OUTPUT != 3)
uniform sampler2D EntryDepth;
#endif
#if !defined(EFFECT_WORLD) && EFFECT_OUTPUT == 2
uniform sampler2D ReferenceMotion;
uniform sampler2D ReferenceIdentity;
uniform sampler2D ReferenceCoherence;
#endif

layout(std140) uniform EffectMotionConfig {
    vec4 Size;
    vec4 Settings;
};

in vec4 previousClip;
in vec2 textureUv;
in vec2 effectState;
flat in vec3 identity;
flat in float coherenceGroup;
in vec2 previousLocal;
out vec4 fragColor;

void main() {
    float alpha = texture(Sampler0, textureUv).a * effectState.x;
    if (alpha <= max(Settings.x, 0.00001)) discard;
    vec2 rawUv = gl_FragCoord.xy * Size.xy;
    float currentDepth = gl_FragCoord.z;
#if !defined(EFFECT_WORLD) && EFFECT_OUTPUT == 2
    float layerDepth = texture(LayerDepth, rawUv).r;
#endif
    float tolerance = max(0.000001, max(abs(currentDepth) * 0.00001, fwidth(currentDepth) * 1.5));
    vec4 motion = vec4(0.0, 0.0, 0.0, -1.0);
    float confidence = all(greaterThanEqual(previousLocal, vec2(0.0))) && all(lessThanEqual(previousLocal, vec2(1.0))) ? effectState.y : 0.0;
    if (previousClip.w > 0.0 && confidence > 0.0) {
        vec3 previousNdc = previousClip.xyz / previousClip.w;
        float previousDepth = Settings.y > 0.5 ? previousNdc.z : previousNdc.z * 0.5 + 0.5;
        motion = vec4(previousNdc.xy * 0.5 + 0.5 - rawUv, previousDepth, confidence);
    }
#ifdef EFFECT_WORLD
    float worldDepth = texture(EntryDepth, rawUv).r;
    float worldTolerance = max(0.00000002, max(abs(worldDepth) * 0.00001, fwidth(currentDepth) * 0.002));
    if (abs(currentDepth - worldDepth) > worldTolerance) discard;
    if (motion.a > 0.0) motion.a = 1.0;
    fragColor = motion;
#elif EFFECT_OUTPUT == 2
    float entryDepth = texture(EntryDepth, rawUv).r;
    if (currentDepth + tolerance < entryDepth) discard;
    vec4 reference = texture(ReferenceMotion, rawUv);
    vec3 referenceIdentity = texture(ReferenceIdentity, rawUv).rgb;
    vec4 referenceCoherence = texture(ReferenceCoherence, rawUv);
    int group = int(coherenceGroup + 0.5);
    ivec3 groupBytes = ivec3(group & 255, (group >> 8) & 255, (group >> 16) & 255);
    bool sharedMotion = group > 0 && confidence > 0.0 && reference.a > 0.0 && referenceCoherence.a > 0.0 &&
        all(equal(groupBytes, ivec3(referenceCoherence.rgb * 255.0 + 0.5)));
    bool different = any(notEqual(ivec3(identity * 255.0 + 0.5), ivec3(referenceIdentity * 255.0 + 0.5)));
    vec2 delta = (motion.xy - reference.xy) * Size.zw;
    // Same model, reliable surfaces may share a warp. Motion variance still rejects deformation/parallax.
    fragColor = alpha * vec4(1.0, min(dot(delta, delta), 1024.0), different && !sharedMotion ? 1.0 : 0.0,
        sharedMotion ? 0.0 : abs(currentDepth - layerDepth));
#elif EFFECT_OUTPUT == 1
    // Hardware EQUAL uses the exact representative depth from the motion pass.
    fragColor = vec4(identity, confidence);
#elif EFFECT_OUTPUT == 3
    int group = int(coherenceGroup + 0.5);
    fragColor = vec4(vec3(group & 255, (group >> 8) & 255, (group >> 16) & 255) / 255.0, confidence);
#else
    float entryDepth = texture(EntryDepth, rawUv).r;
    if (currentDepth + tolerance < entryDepth) discard;
    fragColor = motion;
#endif
}
