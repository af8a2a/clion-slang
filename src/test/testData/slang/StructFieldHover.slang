struct LightShapeSample
{
    float3 lightVector;
    float3 lightDirection;
    float weight;
    float distance;
    float2 uv;
    int materialIndex;
};

void inspectFieldLayout()
{
    LightShapeSample sample;
    sample.lightVector = float3(0.0);
    sample.materialIndex = 1;
}
