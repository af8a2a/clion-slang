interface IMaterial
{
    float3 eval(float3 normal);
}

struct Lambert : IMaterial
{
    float3 albedo;

    float3 eval(float3 normal)
    {
        return albedo * max(normal.z, 0.0);
    }
}
