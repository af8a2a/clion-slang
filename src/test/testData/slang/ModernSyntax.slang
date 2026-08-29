module modern_syntax;

interface IContainer
{
    associatedtype Element : __BuiltinArithmeticType;
    Element get(int index);
}

extension<T : IContainer> T
{
    T.Element first() { return this.get(0); }
}

__generic<typename T, let N : int>
vector<T, N> identity(vector<T, N> value)
{
    return value;
}
