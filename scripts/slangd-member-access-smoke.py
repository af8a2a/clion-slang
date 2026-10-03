"""Verify field access and vector/matrix selector colors against real slangd."""
import argparse
import importlib.util
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("tokens", Path(__file__).with_name("slangd-structured-buffer-smoke.py"))
tokens = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tokens)


def verify(decoded, source):
    lines = source.replace("/* 😀 */ ", "").splitlines()
    def expect(fragment, name, role):
        line = next(i for i, text in enumerate(lines) if fragment in text)
        found = [t for t in decoded if t[0] == line and t[2] == name]
        tokens.check(found and all(t[3] == role for t in found), f"Expected {role} {name} in {fragment}: {found}")
    for name in ["textColor", "pxCursor", "pxLeftX", "scale", "sink"]:
        expect("ui." + name + " =", "ui", "parameter")
        if name != "sink":
            expect("ui." + name + " =", name, "property")
    expect("ui.sink = sink", "ui", "parameter")
    line = next(i for i, text in enumerate(lines) if "ui.sink = sink" in text)
    tokens.check([t[3] for t in decoded if t[0] == line and t[2] == "sink"] == ["property", "parameter"], "Member/argument shadowing lost")
    expect("public float4 textColor", "textColor", "property")
    expect("return scale; }", "scale", "property")
    expect("int(ui.pxCursor.x)", "pxCursor", "property")
    expect("int(ui.pxCursor.x)", "x", "property")
    expect("textColor.xy", "xy", "property")
    expect("textColor.xy", "scale", "variable")
    expect("ui.readScale()", "readScale", "function")
    expect("ui.readScale()", "scale", "variable")
    expect("ui.sink.writePixel", "sink", "property")
    expect("ui.sink.writePixel", "writePixel", "function")
    expect("static int scale; };", "scale", "property")
    line = next(i for i, text in enumerate(lines) if "return Counter.scale" in text)
    tokens.check([t[3] for t in decoded if t[0] == line and t[2] == "scale"] == ["property", "variable"], "Namespace global mistaken for field")
    expect("return Counter.scale", "Counter", "type")
    expect("return Counter.scale", "Globals", "namespace")
    expect("value.child.field", "value", "parameter")
    expect("value.child.field", "child", "property")
    expect("value.child.field", "field", "property")
    expect("value._m00", "_m00", "property")
    expect("float shadow", "scale", "parameter")


def run(executable):
    source = (Path(__file__).parent.parent / "src/test/testData/slang/MemberAccessHighlighting.slang").read_text(encoding="utf-8")
    for roles in [tokens.STOCK, tokens.STOCK + tokens.ROLES]:
        client = tokens.baseline.Client(executable)
        try:
            with tempfile.TemporaryDirectory(prefix="slang-member-colors-") as directory:
                path = Path(directory) / "Members.slang"
                path.write_text(source, encoding="utf-8")
                init = client.request("initialize", {"workspaceFolders": [], "capabilities": {"textDocument": {
                    "semanticTokens": {"requests": {"full": True}, "formats": ["relative"], "tokenTypes": roles, "tokenModifiers": []}}}})
                legend = init["capabilities"]["semanticTokensProvider"]["legend"]["tokenTypes"]
                tokens.check(legend == roles, "Semantic legend changed")
                client.notify("initialized", {})
                client.notify("textDocument/didOpen", {"textDocument": {"uri": path.as_uri(), "languageId": "slang", "version": 1, "text": source}})
                params = {"textDocument": {"uri": path.as_uri()}}
                result = client.request("textDocument/semanticTokens/full", params)
                verify(tokens.decode(result, legend, source), source)
                tokens.check(client.request("textDocument/semanticTokens/full", params) == result, "Unstable tokens")
                changed = source.replace("ui.", "/* 😀 */ ui.").replace("\n", "\r\n")
                client.notify("textDocument/didChange", {"textDocument": {"uri": path.as_uri(), "version": 2}, "contentChanges": [{
                    "range": {"start": {"line": 0, "character": 0}, "end": {"line": source.count("\n"), "character": 0}}, "text": changed}]})
                verify(tokens.decode(client.request("textDocument/semanticTokens/full", params), legend, changed), changed)
                client.request("shutdown", None)
                client.notify("exit", None)
        finally:
            client.close()
    print("PASS: member declarations/access, generic/implicit/static/chained fields, namespace globals, methods, vector/matrix selectors, shadowing, UTF-16/CRLF edits, stock/extended clients")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
