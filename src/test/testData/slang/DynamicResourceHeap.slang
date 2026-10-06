#language slang 2026


// Vulkan acceleration-structure handles carry device addresses in both modes.
// Slang's native uint64 ResourceHeapEXT load reads incorrect values on GB203.
// See Documentation/AsHandleInvestigation.md for the independent GPU repro.
// Use this resolver for Vulkan AS handles, including handles in typed params.
// Other descriptors retain Slang's selected mapped/native heap behavior.
namespace Metallic
{
[ForceInline]
public T resolveDescriptor<T : IOpaqueDescriptor>(DescriptorHandle<T> handle)
{
    __target_switch
    {
    case spirv:
        if (T.kind == DescriptorKind.AccelerationStructure) {
            return getDescriptorFromDynamicResourceHeap<T>(handle, BindlessDescriptorOptions.VkMutable);
        }
        return defaultGetDescriptorFromHandle(handle);
    default:
        return defaultGetDescriptorFromHandle(handle);
    }
}
} // namespace Metallic

RaytracingAccelerationStructure resolveAS(DescriptorHandle<RaytracingAccelerationStructure> handle)
{ return getDescriptorFromDynamicResourceHeap<RaytracingAccelerationStructure>(handle, BindlessDescriptorOptions.VkMutable); }
SamplerState resolveSampler(DescriptorHandle<SamplerState> handle)
{ return getDescriptorFromDynamicResourceHeap(handle, BindlessDescriptorOptions.None); }
Texture2D<float4> resolveTexture(DescriptorHandle<Texture2D<float4>> handle)
{ return getDescriptorFromDynamicResourceHeap(handle, BindlessDescriptorOptions.VkMutable); }
