uniform sampler2D InSampler;

void main() {
    vec4 offsets[3];
    SMAAEdgeDetectionVS(texCoord, offsets);
    fragColor = vec4(SMAAColorEdgeDetectionPS(texCoord, offsets, InSampler), 0.0, 0.0);
}
