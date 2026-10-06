"""Check HLSL SM 6.6 heap hover, colors, shadowing and legacy clients against real slangd."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("tokens", Path(__file__).with_name("slangd-structured-buffer-smoke.py"))
tokens = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tokens)
check = tokens.check
URL = "https://microsoft.github.io/DirectX-Specs/d3d/HLSL_SM_6_6_DynamicResources.html#resourcedescriptorheap-and-samplerdescriptorheap"

SAMPLES = {}

def verify(client, uri, source, legend, enabled):
    params = {"textDocument": {"uri": uri}}
    result = client.request("textDocument/semanticTokens/full", params)
    check(result == client.request("textDocument/semanticTokens/full", params), "Unstable tokens")
    modifiers = result["data"][4::5]
    stripped = {"data": list(result["data"])}
    stripped["data"][4::5] = [0] * len(modifiers)
    decoded = tokens.decode(stripped, legend, source)
    lines = source.splitlines()
    for marker in ["builtin-sampler", "builtin-resource", "builtin-comparison",
                   "local-declaration", "local-use", "parameter-declaration", "parameter-use",
                   "global-declaration", "global-use", "field-declaration", "field-use", "qualified-use"]:
        line = next(i for i, text in enumerate(lines) if "// " + marker in text)
        name = "ResourceDescriptorHeap" if "ResourceDescriptorHeap" in lines[line] else "SamplerDescriptorHeap"
        column = len(lines[line][:lines[line].index(name)].encode("utf-16-le")) // 2
        matches = [(t, m) for t, m in zip(decoded, modifiers) if t[:3] == (line, column, name)]
        check(len(matches) == 1, f"Missing/duplicate heap token: {marker}: {matches}")
        builtin = marker.startswith("builtin-")
        check(matches[0][1] == (1 if builtin and enabled else 0), f"Wrong builtin flag: {marker}: {matches}")
        if builtin:
            check(matches[0][0][3] == "variable", str(matches))
        hover = client.request("textDocument/hover", {**params, "position": {"line": line, "character": column + 1}})
        check(hover, f"No hover: {marker}")
        SAMPLES[marker] = hover
        markdown = hover["contents"]["value"]
        check((URL in markdown) == builtin, f"Wrong reference: {marker}: {markdown}")
        check(hover["range"] == {"start": {"line": line, "character": column},
                                "end": {"line": line, "character": column + len(name)}}, str(hover))
    for marker in ["macro-alias", "macro-override"]:
        line = next(i for i, text in enumerate(lines) if marker in text)
        check(all(m == 0 for t, m in zip(decoded, modifiers) if t[0] == line), "Macro marked builtin")
    for marker in ["comment-only", "string-only", "inactive"]:
        line = next(i for i, text in enumerate(lines) if marker in text)
        check(not any(t[0] == line and t[2] in ("ResourceDescriptorHeap", "SamplerDescriptorHeap")
                      for t in decoded), f"Spurious heap token: {marker}")


def run(executable):
    source = (Path(__file__).parent.parent / "src/test/testData/slang/DescriptorHeapBuiltins.slang").read_text(encoding="utf-8")
    for language in ["slang", "hlsl"]:
        for enabled in [False, True]:
            client = tokens.baseline.Client(executable)
            try:
                with tempfile.TemporaryDirectory(prefix="slang-descriptor-heaps-") as directory:
                    path = Path(directory) / ("Heaps." + language)
                    path.write_text(source, encoding="utf-8")
                    init = client.request("initialize", {"workspaceFolders": [], "capabilities": {"textDocument": {
                        "semanticTokens": {"requests": {"full": True}, "formats": ["relative"],
                         "tokenTypes": tokens.STOCK + tokens.ROLES,
                         "tokenModifiers": ["readonly", "defaultLibrary"] if enabled else []}}}})
                    legend = init["capabilities"]["semanticTokensProvider"]["legend"]
                    check(legend.get("tokenModifiers", []) == (["defaultLibrary"] if enabled else []), str(legend))
                    client.notify("initialized", {})
                    client.notify("textDocument/didOpen", {"textDocument": {"uri": path.as_uri(),
                                  "languageId": language, "version": 1, "text": source}})
                    verify(client, path.as_uri(), source, legend["tokenTypes"], enabled)
                    changed = source.replace("return ", "/* 😀 */ return ").replace("\n", "\r\n")
                    client.notify("textDocument/didChange", {"textDocument": {"uri": path.as_uri(), "version": 2},
                                  "contentChanges": [{"range": {"start": {"line": 0, "character": 0},
                                  "end": {"line": source.count("\n"), "character": 0}}, "text": changed}]})
                    verify(client, path.as_uri(), changed, legend["tokenTypes"], enabled)
            finally:
                client.close()
    print("PASS: SM6.6 heap colors/reference links, local/parameter/global/field shadows, comments/strings/inactive code, UTF-16/CRLF edits, Slang/HLSL and legacy clients")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--dump")
    args = parser.parse_args()
    run(args.slangd)
    if args.dump:
        Path(args.dump).write_text(json.dumps(SAMPLES, indent=2) + "\n", encoding="utf-8")
