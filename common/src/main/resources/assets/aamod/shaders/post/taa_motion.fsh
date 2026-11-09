#version 330

layout(std140) uniform MotionConfig {
    vec4 MotionSize;
    vec4 MotionFlags;
    mat4 MotionTexture;
};

uniform sampler2D Sampler0;
uniform sampler2D WorldDepth;

in vec4 priorClip;
in vec2 texCoord;
in float vertexAlpha;
flat in float historyValid;
out vec4 fragColor;

void main() {
    float textureAlpha = MotionFlags.w > 0.5 ? texture(Sampler0, texCoord).a : 1.0;
    if (textureAlpha < MotionFlags.x) discard;
    if (MotionFlags.y > 0.5 && textureAlpha * vertexAlpha <= 0.001) discard;
    vec2 currentUv = gl_FragCoord.xy * MotionSize.xy;
    float worldDepth = texture(WorldDepth, currentUv).r;
    float tolerance = max(0.00000002, max(abs(worldDepth) * 0.00001, fwidth(gl_FragCoord.z) * 0.002));
    if (gl_FragCoord.z + tolerance < worldDepth) discard;
    if (MotionFlags.y < 0.5 && abs(gl_FragCoord.z - worldDepth) > tolerance) discard;
    fragColor = vec4(0.0, 0.0, 0.0, -1.0);
    if (historyValid < 0.5 || MotionFlags.y > 0.5 || priorClip.w <= 0.0) return;
    vec3 previousNdc = priorClip.xyz / priorClip.w;
    float previousDepth = MotionFlags.z > 0.5 ? previousNdc.z : previousNdc.z * 0.5 + 0.5;
    if (any(isnan(previousNdc)) || any(isinf(previousNdc)) || previousDepth <= 0.0 || previousDepth > 1.0) return;
    fragColor = vec4(previousNdc.xy * 0.5 + 0.5 - currentUv, previousDepth, 1.0);
}
