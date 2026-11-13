#version 330

in vec4 Position;
in vec4 PreviousClip;
in vec2 UV0;
in vec2 EffectState;
in vec4 SurfaceIdentity;
in vec2 PreviousLocal;

out vec4 previousClip;
out vec2 textureUv;
out vec2 effectState;
flat out vec3 identity;
flat out float coherenceGroup;
out vec2 previousLocal;

void main() {
    gl_Position = Position;
    previousClip = PreviousClip;
    textureUv = UV0;
    effectState = EffectState;
    identity = SurfaceIdentity.rgb;
    coherenceGroup = SurfaceIdentity.a;
    previousLocal = PreviousLocal;
}
