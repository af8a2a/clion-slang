#include "StructHoverTypes.slang"

uint inspectHit(UnifiedRT::Hit hit, uint geometryIndex) { return hit.instanceID; }
void modifyHit(inout UnifiedRT::Hit hit) {}
void outputHit(out UnifiedRT::Hit hit) {}
struct GPUDrivenPreviewParams { uint width; uint height; uint hzbMipOffset; }
uint hzbMipOffset(GPUDrivenPreviewParams params, uint mipLevel) { return params.width; }
struct Tail { float value; uint8_t tag; }
void inspectTail(Tail value) {}
struct Nested { Tail values[2]; uint8_t tag; }
void inspectNested(Nested value) {}
struct Repeated { UnifiedRT::Hit values[2]; }
void inspectRepeated(Repeated value) {}
struct WithStatic { static float4 cache; uint value; }
void inspectStatic(WithStatic value) {}
struct Wide { uint8_t tag; double value; }
void inspectWide(Wide value) {}
struct MatrixPayload { half2x3 value; uint8_t tag; }
void inspectMatrix(MatrixPayload value) {}
struct Box<T> { uint8_t tag; T value; }
void inspectFloatBox(Box<float> value) {}
void inspectDoubleBox(Box<double> value) {}
struct Derived<T> : Box<T> { uint8_t extra; }
void inspectDerived(Derived<double> value) {}
enum Code : uint16_t { A, B }
struct EnumPayload { uint8_t tag; Code value; }
void inspectEnum(EnumPayload value) {}
struct Empty {}
void inspectEmpty(Empty value) {}
struct ResourcePayload { Texture2D<float4> image; }
void inspectResource(ResourcePayload value) {}
struct PointerPayload { int* pointer; }
void inspectPointer(PointerPayload value) {}
struct UnsizedPayload { float values[]; }
void inspectUnsized(UnsizedPayload value) {}
namespace Outer
{
    namespace Inner
    {
        struct Hit { double value; }
    }
}
void inspectShadow(Outer::Inner::Hit value) {}
float identity(float value) { return value; }
