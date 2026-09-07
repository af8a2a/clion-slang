"""Check negotiated StructuredBuffer and generic type-argument highlighting against real slangd."""
import argparse
import importlib.util
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
check = baseline.check

STOCK = ["type", "enumMember", "variable", "parameter", "function", "property", "namespace", "keyword", "macro", "string"]
ROLES = ["typeParameter", "slangTypeArgument", "slangStructuredBuffer"]
BUFFERS = ["StructuredBuffer", "RWStructuredBuffer", "AppendStructuredBuffer", "ConsumeStructuredBuffer", "RasterizerOrderedStructuredBuffer"]


def decode(result, legend, source):
    data = result["data"]
    check(len(data) % 5 == 0, "Malformed token array")
    lines = source.splitlines()
    line = column = 0
    tokens = []
    previous_end = (-1, -1)
    for i in range(0, len(data), 5):
        dl, dc, length, role, modifiers = data[i:i + 5]
        line += dl
        column = dc if dl else column + dc
        check(length > 0 and 0 <= line < len(lines) and role < len(legend) and modifiers == 0, "Invalid tuple")
        encoded = lines[line].encode("utf-16-le")
        check((column + length) * 2 <= len(encoded) and (line, column) >= previous_end, "Overlapping/out-of-range token")
        text = encoded[column * 2:(column + length) * 2].decode("utf-16-le")
        tokens.append((line, column, text, legend[role]))
        previous_end = (line, column + length)
    return tokens


def run(executable, dump=False):
    fixture = Path(__file__).parent.parent / "src/test/testData/slang/StructuredBufferHighlighting.slang"
    source = fixture.read_text(encoding="utf-8")
    for requested in [[], ["typeParameter"], ROLES[:-1], ROLES]:
        client = baseline.Client(executable)
        try:
            with tempfile.TemporaryDirectory(prefix="slang-buffer-colors-") as directory:
                path = Path(directory) / "Buffers.slang"
                path.write_text(source, encoding="utf-8")
                init = client.request("initialize", {"workspaceFolders": [], "capabilities": {"textDocument": {
                    "semanticTokens": {"requests": {"full": True}, "formats": ["relative"],
                                       "tokenTypes": STOCK + requested, "tokenModifiers": []}}}})
                legend = init["capabilities"]["semanticTokensProvider"]["legend"]["tokenTypes"]
                extended = requested == ROLES
                check(legend == STOCK + (ROLES if extended else []), f"Negotiated legend changed: {legend}")
                client.notify("initialized", {})
                client.notify("textDocument/didOpen", {"textDocument": {"uri": path.as_uri(), "languageId": "slang", "version": 1, "text": source}})
                params = {"textDocument": {"uri": path.as_uri()}}
                result = client.request("textDocument/semanticTokens/full", params)
                tokens = decode(result, legend, source)
                if dump and extended:
                    for token in tokens:
                        print(token)
                for name in BUFFERS:
                    found = [t for t in tokens if t[2] == name and "g_Shadow;" not in source.splitlines()[t[0]]
                             and "struct StructuredBuffer<" not in source.splitlines()[t[0]]]
                    check(found and all(t[3] == ("slangStructuredBuffer" if extended else "type") for t in found), f"Wrong resource role: {name}: {found}")
                if not extended:
                    check(not any(t[3] in ROLES for t in tokens), "New roles leaked to an older client")
                    continue
                def expect(text, role, line_fragment):
                    lines = source.splitlines()
                    line = next(i for i, content in enumerate(lines) if line_fragment in content)
                    check(any(t[0] == line and t[2] == text and t[3] == role for t in tokens), f"Missing {role} for {text} in {line_fragment}")
                expect("HitEntry", "slangTypeArgument", "g_GBuffer;")
                expect("uint", "slangTypeArgument", "g_CompactedGBuffer;")
                expect("uint", "slangTypeArgument", "g_CompactedGBufferLength;")
                expect("Box", "slangTypeArgument", "g_Nested;")
                expect("HitEntry", "slangTypeArgument", "g_Nested;")
                expect("Payloads", "namespace", "g_Qualified;")
                expect("Hit", "slangTypeArgument", "g_Qualified;")
                expect("TElement", "typeParameter", "struct Box<")
                expect("TElement", "typeParameter", "TElement value;")
                expect("T", "slangTypeArgument", "T readElement<T>")
                expect("g_GBuffer", "variable", "g_GBuffer;")
                expect("HitEntry", "type", "struct HitEntry")
                expect("left", "parameter", "return left < right")
                expect("right", "parameter", "return left < right")
                expect("HitBuffer", "type", "g_Alias;")
                expect("StructuredBuffer", "type", "g_Shadow;")
                expect("HitEntry", "slangTypeArgument", "g_Shadow;")
                expect("Sized", "slangTypeArgument", "g_Sized;")
                expect("HitEntry", "slangTypeArgument", "g_Sized;")
                expect("ELEMENT_COUNT", "variable", "g_Sized;")
                expect("vector", "slangTypeArgument", "g_Vector;")
                expect("float", "slangTypeArgument", "g_Vector;")
                check(client.request("textDocument/semanticTokens/full", params) == result, "Repeated request changed classifications")
                # Full unsaved replacement exercises UTF-16, CRLF, comments and stale compiler caches.
                changed = source.replace("RWStructuredBuffer<uint>", "/* 😀 */ RWStructuredBuffer<float4>").replace("\n", "\r\n")
                client.notify("textDocument/didChange", {"textDocument": {"uri": path.as_uri(), "version": 2}, "contentChanges": [{
                    "range": {"start": {"line": 0, "character": 0}, "end": {"line": source.count("\n"), "character": 0}},
                    "text": changed}]})
                updated = decode(client.request("textDocument/semanticTokens/full", params), legend, changed)
                check(sum(t[2:] == ("float4", "slangTypeArgument") for t in updated) == 2, "Edited type arguments did not update")
                check(not any(t[2:] == ("uint", "slangTypeArgument") for t in updated), "Old type argument tokens survived edit")
                client.request("shutdown", None)
                client.notify("exit", None)
        finally:
            client.close()
    print("PASS: StructuredBuffer family, primitive/user/nested/qualified/type-parameter arguments, "
          "aliases/shadowing, declarations vs references, value/comparison roles, UTF-16/CRLF edits and old/partial-client negotiation")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--dump", action="store_true")
    args = parser.parse_args()
    run(args.slangd, args.dump)
