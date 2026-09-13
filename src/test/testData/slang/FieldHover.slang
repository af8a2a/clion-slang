namespace UnifiedRT {
public struct Hit {
    /// Instance identifier documentation.
    public uint instanceID;
    uint primitiveID;
    float2 uv;
    float hitT;
    bool isFrontFace;
    bool IsValid() { return instanceID != 0; }
};
}
struct BuildReGIRPush {
    uint lightCount;
    uint gridSize;
    uint lightsPerCell;
    uint buildSeed;
    uint frameIndex;
    uint padding0;
    uint lightSlotCount;
    uint padding1;
    float4 sceneCenterRadius;
    float2 samplingJitter;
    uint padding2;
    uint padding3;
    uint padding4;
};
struct Box<T> { uint8_t tag; T payload; }
struct Derived : Box<double> { uint8_t extra; }
struct Tail { uint value; uint8_t tag; }
struct ArrayPayload { uint8_t prefix; Tail tails[2]; }
struct MatrixPayload { uint8_t prefix; half2x3 matrix; }
enum Code : uint16_t { A, B }
struct EnumPayload { uint8_t prefix; Code code; }
struct StaticPayload { static float4 cache; uint value; static const uint count = 3; }
struct ResourcePayload { Texture2D<float4> resource; uint value; }
struct ResourceTail { uint value; Texture2D<float4> resource; }
struct PrivatePayload { private uint secret; internal uint moduleValue; }
uint readHit(UnifiedRT::Hit hit) { return hit.instanceID; }
float readFloat(Box<float> box) { return box.payload; }
double readDouble(Box<double> box) { return box.payload; }
double readInherited(Derived box) { return box.payload; }
uint readArray(ArrayPayload box) { return box.tails[1].value; }
uint readStatic(StaticPayload box) { return box.value; }
uint readUnknown(ResourcePayload box) { return box.value; }
uint localFunction(uint value) { uint local = value; return local; }
