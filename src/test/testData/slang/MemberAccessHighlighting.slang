public interface IScatterSink { void writePixel(int2 pixel, float4 color); }
public struct ContextScatter<TSink : IScatterSink>
{
    public TSink sink;
    public float4 textColor;
    public int2 pxCursor;
    public int pxLeftX;
    public int scale;
    float readScale() { return scale; }
};
public void s2h_init<TSink : IScatterSink>(out ContextScatter<TSink> ui, TSink sink)
{
    ui.textColor = float4(1, 1, 1, 1);
    ui.pxCursor = int2(0, 0);
    ui.pxLeftX = int(ui.pxCursor.x);
    ui.scale = 1;
    ui.sink = sink;
    float scale = ui.readScale();
    ui.textColor.xy = float2(scale, scale);
    ui.sink.writePixel(ui.pxCursor, ui.textColor);
}
namespace Globals { static int scale; }
struct Counter { static int scale; };
int staticAccess() { return Counter.scale + Globals.scale; }
struct Plain { int field; }
struct Nested { Plain child; }
int chain(Nested value) { return value.child.field; }
float matrixComponent(float2x2 value) { return value._m00; }
float shadow(float scale) { return scale; }
