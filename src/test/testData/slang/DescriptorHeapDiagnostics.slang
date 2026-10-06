#language slang 2026
public struct SamplerHandle { public uint index; };
[ForceInline]
public SamplerState resolveUniform(SamplerHandle handle)
{
    return SamplerDescriptorHeap[handle.index];
}
[ForceInline]
public SamplerState resolveNonUniform(SamplerHandle handle)
{
    return SamplerDescriptorHeap[NonUniformResourceIndex(handle.index)];
}
SamplerComparisonState comparison(uint index) { return SamplerDescriptorHeap[index]; }
Texture2D<float4> texture(uint index) { return ResourceDescriptorHeap[index]; }
RWTexture2D<float4> rwTexture(uint index) { return ResourceDescriptorHeap[index]; }
StructuredBuffer<float4> structured(uint index) { return ResourceDescriptorHeap[index]; }
RWStructuredBuffer<float4> rwStructured(uint index) { return ResourceDescriptorHeap[index]; }
ByteAddressBuffer raw(uint index) { return ResourceDescriptorHeap[index]; }
RWByteAddressBuffer rwRaw(uint index) { return ResourceDescriptorHeap[index]; }
struct Constants { float4 color; };
ConstantBuffer<Constants> constants(uint index) { return ResourceDescriptorHeap[index]; }
float4 sample(uint index, float2 uv)
{
    Texture2D<float4> t = ResourceDescriptorHeap[NonUniformResourceIndex(index)];
    SamplerState s = SamplerDescriptorHeap[index];
    return t.SampleLevel(s, uv, 0);
}
TextureBuffer<Constants> textureConstants(uint index) { return ResourceDescriptorHeap[index]; }
AppendStructuredBuffer<float4> appendBuffer(uint index) { return ResourceDescriptorHeap[index]; }
ConsumeStructuredBuffer<float4> consumeBuffer(uint index) { return ResourceDescriptorHeap[index]; }
RaytracingAccelerationStructure acceleration(uint index) { return ResourceDescriptorHeap[index]; }
SamplerState existingHandle(uint index)
{
    return defaultGetDescriptorFromHandle(DescriptorHandle<SamplerState>(uint2(index, 0)));
}
