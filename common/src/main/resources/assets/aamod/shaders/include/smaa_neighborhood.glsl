uniform sampler2D InSampler;
uniform sampler2D BlendSampler;

void main() {
    vec4 offsets;
    SMAANeighborhoodBlendingVS(texCoord, offsets);
    fragColor = SMAANeighborhoodBlendingPS(texCoord, offsets, InSampler, BlendSampler);
    fragColor.a = textureLod(InSampler, texCoord, 0.0).a;
}
