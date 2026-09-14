#language slang 2026
module Core;
__include "Core/ViewConstants.slang";
implementing GPUDriven;
public import Lighting;
__exported import Nested.Utility;
__import "Lighting/Shared.slang";
using Metallic.Rendering;
using Legacy::Rendering;
namespace Metallic.Rendering
{
    public struct Parameters
    {
        public uint lightCount;
        internal float3 position;
    }
}
