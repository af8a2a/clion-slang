struct Item { float field; static int count; };
float globalValue;
float f(float parameter)
{
    float localValue = parameter;
    Item item;
    item.field = localValue;
    return sin(item.field) + globalValue;
}
float generic<T>(T genericParameter) { float genericLocal = 0; return genericLocal; }
// ghostIdentifier should not become a link.
