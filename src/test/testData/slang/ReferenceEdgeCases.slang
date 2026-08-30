groupshared int addressTarget;

void useAddressTarget()
{
    let pointer = __getAddress(addressTarget);
}

struct NonDiffBox
{
    float value;
};

[Differentiable]
float useDetachedTarget(float detachedTarget)
{
    NonDiffBox box = { detachedTarget };
    return 0.0;
}

float useCompileTimeTarget(float compileTimeTarget)
{
    float result = 0.0;
    $for (index in Range(0, 2))
    {
        result += compileTimeTarget + index;
    }
    return result;
}
