#define BASE params.history.data
#define ALIAS BASE
#define ADD(x, y) ((x) + (y))
#define CAT(a, b) a ## b
#define STR(x) #x
#define ZERO() 7
#define EMPTY
#define LOOP LOOP
struct History { int data; }
struct Params { History history; }
Params params;
int value;
int useAlias() { return ALIAS; }
int useAdd() { return ADD(2, ZERO()); }
int usePaste() { return CAT(val, ue); }
void useString() { STR(hello world); }
int useZero() { return ZERO(); }
EMPTY int emptyUse;
int useRecursive() { return LOOP; }
#define CHANGING 1
int oldValue = CHANGING;
#undef CHANGING
#define CHANGING 2
int newValue = CHANGING;
