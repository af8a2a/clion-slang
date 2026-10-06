SamplerState getSampler(uint index)
{
    return SamplerDescriptorHeap[index]; // builtin-sampler
}
Texture2D<float4> getResource(uint index)
{
    return ResourceDescriptorHeap[NonUniformResourceIndex(index)]; // builtin-resource
}
SamplerComparisonState getComparison(uint index)
{
    return SamplerDescriptorHeap[index]; // builtin-comparison
}
float localShadow()
{
    float ResourceDescriptorHeap = 1; // local-declaration
    return ResourceDescriptorHeap; // local-use
}
float parameterShadow(float SamplerDescriptorHeap) // parameter-declaration
{
    return SamplerDescriptorHeap; // parameter-use
}
namespace Custom
{
    float ResourceDescriptorHeap; // global-declaration
    float read() { return ResourceDescriptorHeap; } // global-use
}
struct CustomHeap
{
    float SamplerDescriptorHeap; // field-declaration
};
float readField(CustomHeap value)
{
    return value.SamplerDescriptorHeap; // field-use
}
float readGlobal() { return Custom::ResourceDescriptorHeap; } // qualified-use
// SamplerDescriptorHeap ResourceDescriptorHeap comment-only
static const String label = "SamplerDescriptorHeap ResourceDescriptorHeap"; // string-only
#if 0
SamplerState disabled() { return SamplerDescriptorHeap[0]; } // inactive
#endif
#define HEAP_ALIAS SamplerDescriptorHeap
SamplerState macroAlias() { return HEAP_ALIAS[0]; } // macro-alias
#define ResourceDescriptorHeap Custom::ResourceDescriptorHeap
float macroOverride() { return ResourceDescriptorHeap; } // macro-override
#undef ResourceDescriptorHeap
