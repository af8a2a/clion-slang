"""Real-server regression for semantic type-alias hover (standard LSP, no custom capability)."""
import argparse
import importlib.util
import sys
from pathlib import Path

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def run(executable):
    path = Path(__file__).resolve().parent.parent / "src/test/testData/slang/TypeHover.slang"
    source = path.read_text(encoding="utf-8")
    client = baseline.Client(executable)
    try:
        client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
        client.notify("initialized", {})
        client.notify("textDocument/didOpen", {"textDocument": {
            "uri": path.as_uri(), "languageId": "slang", "version": 1, "text": source}})

        def hover(fragment, token):
            line = next(i for i, text in enumerate(source.splitlines()) if fragment in text)
            col = source.splitlines()[line].index(token)
            result = client.request("textDocument/hover", {"textDocument": {"uri": path.as_uri()},
                "position": {"line": line, "character": col + 1}})
            baseline.check(result and result["contents"]["kind"] == "markdown", f"Missing hover: {fragment}")
            baseline.check(result["range"]["start"]["line"] == line, f"Wrong range: {fragment}")
            return result["contents"]["value"]

        for fragment, token, expanded, details in [
            ("uint2 pixel", "uint2", "vector<uint, 2> uint2", ["Components: `2`"]),
            ("float4 color", "float4", "vector<float, 4> float4", ["Components: `4`"]),
            ("float3x4 transform", "float3x4", "matrix<float, 3, 4> float3x4", ["Rows: `3`", "Columns: `4`"]),
            ("half1x1 small", "half1x1", "matrix<half, 1, 1> half1x1", ["Rows: `1`", "Columns: `1`"]),
            ("double2 precise", "double2", "vector<double, 2> double2", ["Components: `2`"]),
        ]:
            text = hover(fragment, token)
            baseline.check(all(part in text for part in [expanded, "Built-in type alias", "Element type:", *details]), text)
        custom = hover("Pixel customPixel", "Pixel")
        baseline.check("vector<uint, 2> Pixel" in custom and "Built-in" not in custom
                       and "Application pixel coordinates" in custom and "Defined in" in custom, custom)
        declaration = hover("typedef uint2 Pixel", "Pixel")
        baseline.check("vector<uint, 2> Pixel" in declaration and "Built-in" not in declaration, declaration)
        shadow = hover("UserTypes::uint2 shadowed", "uint2")
        baseline.check("float uint2" in shadow and "Built-in" not in shadow and "Components:" not in shadow, shadow)
        payload = hover("PayloadAlias payload", "PayloadAlias")
        baseline.check("Payload PayloadAlias" in payload and "Built-in" not in payload, payload)
        function = hover("float identity", "identity")
        baseline.check("identity" in function and "Type alias" not in function, function)
        variable = hover("uint2 pixel", "pixel")
        baseline.check("pixel" in variable and "Components:" not in variable, variable)
        print("PASS: built-in vectors/matrices, user aliases, shadowing, documentation, ranges, function fallback")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
