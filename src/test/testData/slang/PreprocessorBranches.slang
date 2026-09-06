// M4b manual fixture: change these values to switch the compiler-selected branch.
#define BLUE 0
#define GREEN 1
#define RED 0

#if BLUE
float3 branchColor = float3(0, 0, 1);
#elif GREEN
float3 branchColor = float3(0, 1, 0);
#elif RED
float3 branchColor = float3(1, 0, 0);
#else
float3 branchColor = float3(1, 1, 1);
#endif

#if 0
// Comments, empty lines and disabled directives should all be dimmed.

#define DISABLED_MACRO 1
#include "IntentionallyMissing.slangh"
#if UNKNOWN_NESTED_MACRO
float nested;
#endif
#endif
