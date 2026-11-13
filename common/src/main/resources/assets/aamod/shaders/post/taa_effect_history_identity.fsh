#version 330

#moj_import <aamod:taa_effect_conflict.glsl>

uniform sampler2D IdentitySampler;
uniform sampler2D ConflictSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 identity = texelFetch(IdentitySampler, pixel, 0);
    if (effectConflictReasons(texelFetch(ConflictSampler, pixel, 0)) != 0) identity.a = 0.0;
    fragColor = identity;
}
