struct HitEntry
{
    uint index;
    float3 normal;
};

struct Box<TElement>
{
    TElement value;
};

namespace Payloads
{
    struct Hit { uint index; };
}

StructuredBuffer<HitEntry> g_GBuffer;
RWStructuredBuffer<uint> g_CompactedGBuffer;
RWStructuredBuffer<uint> g_CompactedGBufferLength;
AppendStructuredBuffer<HitEntry> g_Append;
ConsumeStructuredBuffer<HitEntry> g_Consume;
RasterizerOrderedStructuredBuffer<HitEntry> g_Ordered;
StructuredBuffer<Box<HitEntry>> g_Nested;
StructuredBuffer<Payloads::Hit> g_Qualified;

T readElement<T>(StructuredBuffer<T> source, uint index)
{
    return source[index];
}

bool compareValues(uint left, uint right)
{
    return left < right && right > 0;
}

typedef StructuredBuffer<HitEntry> HitBuffer;
HitBuffer g_Alias;

struct Sized<T, let Count : int> { T values[Count]; };
static const int ELEMENT_COUNT = 4;
StructuredBuffer<Sized<HitEntry, ELEMENT_COUNT>> g_Sized;
StructuredBuffer<vector<float, 4>> g_Vector;

namespace UserTypes
{
    struct StructuredBuffer<T> { T value; };
}
UserTypes::StructuredBuffer<HitEntry> g_Shadow;
