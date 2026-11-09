#version 330

layout(std140) uniform MotionConfig {
    vec4 MotionSize;
    vec4 MotionFlags;
    mat4 MotionTexture;
};

in vec4 Position;
in vec4 PreviousClip;
in vec2 UV0;
in vec2 MotionState;

out vec4 priorClip;
out vec2 texCoord;
out float vertexAlpha;
flat out float historyValid;

void main() {
    gl_Position = Position;
    priorClip = PreviousClip;
    texCoord = (MotionTexture * vec4(UV0, 0.0, 1.0)).xy;
    vertexAlpha = MotionState.x;
    historyValid = MotionState.y;
}
