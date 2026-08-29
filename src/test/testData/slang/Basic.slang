// A compact smoke shader used by lexer and slangc validation.
struct VertexInput
{
    float3 position : POSITION;
    float3 normal : NORMAL;
};

[shader("vertex")]
float4 vertexMain(VertexInput input) : SV_Position
{
    return float4(input.position, 1.0);
}
