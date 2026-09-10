uint2 pixel;
float4 color;
float3x4 transform;
half1x1 smallMatrix;
double2 precise;
/// Application pixel coordinates.
typedef uint2 Pixel;
Pixel customPixel;
namespace UserTypes
{
    typedef float uint2;
}
UserTypes::uint2 shadowed;
struct Payload { float value; }
typedef Payload PayloadAlias;
PayloadAlias payload;
float identity(float value) { return value; }
