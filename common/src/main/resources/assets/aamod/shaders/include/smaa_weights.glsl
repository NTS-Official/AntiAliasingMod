uniform sampler2D InSampler;
uniform sampler2D AreaSampler;
uniform sampler2D SearchSampler;

void main() {
    vec2 pixelCoord;
    vec4 offsets[3];
    SMAABlendingWeightCalculationVS(texCoord, pixelCoord, offsets);
    fragColor = SMAABlendingWeightCalculationPS(texCoord, pixelCoord, offsets, InSampler, AreaSampler, SearchSampler, vec4(0.0));
}
