#version 330

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform FxaaConfig {
    float Subpixel;
    float EdgeThreshold;
    float MinimumThreshold;
    int SearchSteps;
};

in vec2 texCoord;
out vec4 fragColor;

const int MAX_SEARCH_STEPS = 5;
const float SEARCH_DISTANCES[MAX_SEARCH_STEPS] = float[](1.0, 1.5, 2.0, 4.0, 12.0);

float luminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

float sampleLuminance(vec2 position) {
    return luminance(textureLod(InSampler, position, 0.0).rgb);
}

void main() {
    vec2 texel = 1.0 / InSize;
    vec4 centerColor = textureLod(InSampler, texCoord, 0.0);
    float center = luminance(centerColor.rgb);
    float north = sampleLuminance(texCoord + vec2(0.0, -texel.y));
    float south = sampleLuminance(texCoord + vec2(0.0, texel.y));
    float west = sampleLuminance(texCoord + vec2(-texel.x, 0.0));
    float east = sampleLuminance(texCoord + vec2(texel.x, 0.0));
    float minimum = min(center, min(min(north, south), min(west, east)));
    float maximum = max(center, max(max(north, south), max(west, east)));
    float contrast = maximum - minimum;
    if (contrast <= 0.0 || contrast < max(MinimumThreshold, maximum * EdgeThreshold)) {
        fragColor = centerColor;
        return;
    }

    float northWest = sampleLuminance(texCoord + vec2(-texel.x, -texel.y));
    float northEast = sampleLuminance(texCoord + vec2(texel.x, -texel.y));
    float southWest = sampleLuminance(texCoord + vec2(-texel.x, texel.y));
    float southEast = sampleLuminance(texCoord + vec2(texel.x, texel.y));
    float horizontal = abs(northWest + southWest - 2.0 * west)
        + 2.0 * abs(north + south - 2.0 * center)
        + abs(northEast + southEast - 2.0 * east);
    float vertical = abs(northWest + northEast - 2.0 * north)
        + 2.0 * abs(west + east - 2.0 * center)
        + abs(southWest + southEast - 2.0 * south);
    bool horizontalEdge = horizontal >= vertical;

    float negativeNeighbor = horizontalEdge ? north : west;
    float positiveNeighbor = horizontalEdge ? south : east;
    float negativeGradient = abs(negativeNeighbor - center);
    float positiveGradient = abs(positiveNeighbor - center);
    bool negativeSide = negativeGradient >= positiveGradient;
    float gradient = max(negativeGradient, positiveGradient);
    float localAverage = 0.5 * (center + (negativeSide ? negativeNeighbor : positiveNeighbor));
    vec2 normalStep = horizontalEdge ? vec2(0.0, texel.y) : vec2(texel.x, 0.0);
    if (negativeSide) normalStep = -normalStep;
    vec2 tangentStep = horizontalEdge ? vec2(texel.x, 0.0) : vec2(0.0, texel.y);
    vec2 edgeCenter = texCoord + 0.5 * normalStep;
    vec2 negativePosition = edgeCenter - tangentStep;
    vec2 positivePosition = edgeCenter + tangentStep;
    float negativeDelta = sampleLuminance(negativePosition) - localAverage;
    float positiveDelta = sampleLuminance(positivePosition) - localAverage;
    float gradientThreshold = gradient * 0.25;
    bool negativeDone = abs(negativeDelta) >= gradientThreshold;
    bool positiveDone = abs(positiveDelta) >= gradientThreshold;

    for (int searchIndex = 1; searchIndex < MAX_SEARCH_STEPS; searchIndex++) {
        if (searchIndex >= SearchSteps || (negativeDone && positiveDone)) break;
        float distanceStep = SEARCH_DISTANCES[searchIndex];
        if (!negativeDone) {
            negativePosition -= tangentStep * distanceStep;
            negativeDelta = sampleLuminance(negativePosition) - localAverage;
            negativeDone = abs(negativeDelta) >= gradientThreshold;
        }
        if (!positiveDone) {
            positivePosition += tangentStep * distanceStep;
            positiveDelta = sampleLuminance(positivePosition) - localAverage;
            positiveDone = abs(positiveDelta) >= gradientThreshold;
        }
    }

    float negativeDistance = horizontalEdge ? texCoord.x - negativePosition.x : texCoord.y - negativePosition.y;
    float positiveDistance = horizontalEdge ? positivePosition.x - texCoord.x : positivePosition.y - texCoord.y;
    bool negativeCloser = negativeDistance < positiveDistance;
    float closestDelta = negativeCloser ? negativeDelta : positiveDelta;
    float edgeOffset = 0.5 - min(negativeDistance, positiveDistance) / (negativeDistance + positiveDistance);
    if ((closestDelta < 0.0) == (center < localAverage)) edgeOffset = 0.0;

    float neighborhoodAverage = (2.0 * (north + south + west + east)
        + northWest + northEast + southWest + southEast) / 12.0;
    float subpixel = clamp(abs(neighborhoodAverage - center) / contrast, 0.0, 1.0);
    subpixel = subpixel * subpixel * (3.0 - 2.0 * subpixel);
    subpixel = subpixel * subpixel * Subpixel;
    float offset = max(edgeOffset, subpixel);
    vec3 filtered = textureLod(InSampler, texCoord + normalStep * offset, 0.0).rgb;
    fragColor = vec4(filtered, centerColor.a);
}
