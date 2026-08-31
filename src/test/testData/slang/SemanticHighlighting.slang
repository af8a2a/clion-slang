// Stable semantic-token corpus. Keep contract lexemes unique when extending it.
#define CONTRACT_SCALE 2.0

namespace ContractCorpus
{
    enum ContractMode
    {
        ContractCool,
        ContractWarm,
    }; // Padding keeps stock 1.8's synthetic enum token range in bounds.

    class ContractReference
    {
        float contractClassValue;
    }

    struct ContractBox<TContractValue>
    {
        TContractValue contractBoxValue;
    }

    interface IContractSurface
    {
        float3 contractEvaluate(float3 contractNormal);
    }

    struct ContractSurface : IContractSurface
    {
        float3 contractTint;
        ContractMode contractMode;

        float3 contractEvaluate(float3 contractNormal)
        {
            float contractWeight = contractMode == ContractMode.ContractWarm
                ? CONTRACT_SCALE
                : 1.0;
            return contractTint * max(contractNormal.z, 0.0) * contractWeight;
        }
    };

    static const uint CONTRACT_THREAD_COUNT = 1;
}

[shader("compute")]
[numthreads(ContractCorpus::CONTRACT_THREAD_COUNT, 1, 1)]
void contractEntry(uint3 contractDispatchId : SV_DispatchThreadID)
{
    ContractCorpus::ContractSurface contractSurface;
    contractSurface.contractTint = float3(1.0, 0.5, 0.25);
    contractSurface.contractMode = ContractCorpus::ContractMode.ContractWarm;
    /* 😀 */ float3 contractUtf16Color = contractSurface.contractEvaluate(float3(contractDispatchId));
}
