# Slang module syntax highlighting (0.8.3)

The lexer recognizes the module syntax used in the refactored Metallic shaders:

```slang
#language slang 2026
module Core;
__include "Core/ViewConstants.slang";
```

Implementation files and consumers also receive highlighting:

```slang
implementing GPUDriven;
public import Core;
using Metallic.GPUDriven;
namespace Metallic.GPUDriven
{
    public struct Parameters { public uint lightCount; }
}
```

These examples represent separate compilation units. Module/import/implementing names use
**Color Scheme | Slang | Modules | Module names** (`SLANG.MODULE`), defaulting to namespace
colors. `__include` and quoted import paths use **Include paths**. Dotted import components,
`__import`, `__exported import`, namespace declarations and namespace `using` paths are also
recognized. Namespace paths share the existing semantic Namespace setting, including `::` spelling.

`module`, `implementing`, `import`, visibility modifiers and `__include` use Keywords;
`#language slang 2026` uses Preprocessor directives. Light / IntelliJ Light defaults map module
names to the existing Rider namespace purple. Other schemes use theme fallbacks; explicit
module overrides remain independent. No global theme, scheme, C++ color or font is selected.

Highlighting is local and available before slangd connects, with bundled or external servers.
Language-server semantic tokens still own resolved types, fields, functions and ordinary
references; the lexer only colors names in these explicit declaration/import contexts.
Comments, line breaks and lexer restarts retain context, while semicolons and braces end it.
Malformed declarations recover on the next declaration keyword or built-in type.

Slang module paths have a separate token from textual `#include` paths, despite sharing a
color. This preserves string quote handling and prevents module imports from being mistaken
for preprocessor compilation-root include edges. Module resolution, diagnostics and navigation
remain provided by slangd; no compiler patch or runtime change is needed for this release.

Tests cover dotted names, comments, LF/CRLF/CR, UTF-16 edits, restart suffix equivalence,
recovery, color settings and preprocessor include isolation. The module grammar was checked
against the bundled Slang parser, and the lexical corpus probe reads the user's shaders
without changing any shader source.

## 中文

升级插件即可生效，自带版与外部公版 slangd 均可使用。新增“Modules → Module names”配色项；
模块名默认沿用命名空间颜色，模块文件路径沿用“Include paths”。本次不修改 Shader 或模块结构。
