namespace UnifiedRT
{
    /// Ray intersection payload.
    struct Hit
    {
        uint instanceID;
        uint8_t flags;
        float3 position;
        uint geometryIndex;
    }
}
