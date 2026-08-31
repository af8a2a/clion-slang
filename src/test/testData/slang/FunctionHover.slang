float4 SampleAtlas(
    Texture2DArray<float4> atlas,
    SamplerState atlasSampler,
    uint index,
    float2 uv,
    float2 scale,
    float2 offset,
    bool pointFilterMode)
{
    return float4(0.0);
}

float4 inspectSampleAtlas(
    Texture2DArray<float4> atlas,
    SamplerState atlasSampler)
{
    return SampleAtlas(
        atlas,
        atlasSampler,
        0,
        float2(0.0),
        float2(1.0),
        float2(0.0),
        false);
}
