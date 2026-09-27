float scalar(float x) { return abs(x) + sin(x); }
float3 direction(float3 x) { return normalize(x); }
uint flags() { return RAY_FLAG_NONE + WaveGetLaneIndex(); }
float differentiable(float x) { return detach(x); }
uint bits(float x) { return asuint(x); }
namespace User {
    float abs(float x) { return x; }
    static const uint RAY_FLAG_NONE = 42;
}
float custom(float x) { return User::abs(x); }
uint customFlag() { return User::RAY_FLAG_NONE; }
struct Payload { float value; }
float abs(Payload p) { return p.value; }
float userOverload(Payload p) { return abs(p); }
uint localVariable() { uint RAY_FLAG_NONE = 7; return RAY_FLAG_NONE; }
