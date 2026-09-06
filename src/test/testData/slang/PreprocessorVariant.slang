// No source edit is needed: choose the Blue/Green variants in the context popup.
#if MODE == 1
static const float3 variantColor = float3(0, 0, 1);
#elif MODE == 2
static const float3 variantColor = float3(0, 1, 0);
#else
static const float3 variantColor = float3(0.5, 0.5, 0.5);
#endif

[shader("compute")]
[numthreads(1, 1, 1)]
void variantMain(uint3 threadId : SV_DispatchThreadID) {}
