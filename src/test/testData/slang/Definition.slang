// A stable LSP definition target and call site.
float twice(float value)
{
    return value * 2.0;
}

float useTwice(float value)
{
    return twice(value);
}
