"""Exercise compiler-backed field declaration/access hovers using a real slangd."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys
import tempfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def run(executable, dump=None):
    path = Path(__file__).resolve().parent.parent / "src/test/testData/slang/FieldHover.slang"
    source = path.read_text(encoding="utf-8")
    samples = {}
    client = baseline.Client(executable)
    try:
        client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
        client.notify("initialized", {})

        def open_doc(document, text):
            client.notify("textDocument/didOpen", {"textDocument": {"uri": document.as_uri(),
                "languageId": "slang", "version": 1, "text": text}})

        def hover(fragment, token, document=path, text=source):
            line = next(i for i, value in enumerate(text.splitlines()) if fragment in value)
            match = list(re.finditer(r"\b" + re.escape(token) + r"\b", text.splitlines()[line]))[-1]
            col = len(text.splitlines()[line][:match.start()].encode("utf-16-le")) // 2
            result = client.request("textDocument/hover", {"textDocument": {"uri": document.as_uri()},
                "position": {"line": line, "character": col}})
            baseline.check(result and result["contents"]["kind"] == "markdown", f"No hover: {fragment}")
            baseline.check(result["range"] == {"start": {"line": line, "character": col},
                "end": {"line": line, "character": col + len(token.encode('utf-16-le')) // 2}}, f"Wrong range: {result}")
            value = result["contents"]["value"]
            samples[f"{fragment} [{token}]"] = value
            return value

        def check(fragment, token, size, alignment, offset, **kwargs):
            value = hover(fragment, token, **kwargs)
            for label, number in [("Size", size), ("Alignment", alignment), ("Offset", offset)]:
                expected = f"`{number}`" if isinstance(number, int) else number
                baseline.check(f"{label}: {expected}" in value, f"Missing {label}: {expected}: {value}")
            baseline.check("field\n" in value and "(in struct `" in value and "#L" in value, value)
            baseline.check("(field)" not in value and "Defined in" not in value, value)
            return value

        open_doc(path, source)
        value = check("public uint instanceID;", "instanceID", 4, 4, 0)
        baseline.check("```slang\npublic field\nuint instanceID\n```" in value, value)
        baseline.check("Instance identifier documentation" in value, value)
        baseline.check("[FieldHover.slang:4]" in value, value)
        check("readHit(", "instanceID", 4, 4, 0)
        for fragment, token, size, alignment, offset in [
            ("uint primitiveID;", "primitiveID", 4, 4, 4),
            ("float2 uv;", "uv", 8, 4, 8),
            ("float hitT;", "hitT", 4, 4, 16),
            ("bool isFrontFace;", "isFrontFace", 1, 1, 20),
            ("uint lightCount;", "lightCount", 4, 4, 0),
            ("uint gridSize;", "gridSize", 4, 4, 4),
            ("float4 sceneCenterRadius;", "sceneCenterRadius", 16, 4, 32),
            ("uint padding4;", "padding4", 4, 4, 64),
            ("readFloat(", "payload", 4, 4, 4),
            ("readDouble(", "payload", 8, 8, 8),
            ("readInherited(", "payload", 8, 8, 8),
            ("struct Derived", "extra", 1, 1, 16),
            ("struct ArrayPayload", "tails", 13, 4, 4),
            ("struct MatrixPayload", "matrix", 12, 2, 2),
            ("struct EnumPayload", "code", 2, 2, 2),
            ("readStatic(", "value", 4, 4, 0),
            ("struct StaticPayload", "cache", 16, 4, "not applicable (static field)"),
            ("struct StaticPayload", "count", 4, 4, "not applicable (static field)"),
            ("struct Box<T>", "payload", "unavailable", "unavailable", "unavailable"),
            ("struct ResourcePayload", "resource", "unavailable", "unavailable", "unavailable"),
            ("readUnknown(", "value", 4, 4, "unavailable"),
            ("struct ResourceTail", "value", 4, 4, 0),
        ]:
            check(fragment, token, size, alignment, offset)
        baseline.check("Box<double>" in hover("readDouble(", "payload"), "Lost generic owner substitution")
        baseline.check("private field\n" in hover("struct PrivatePayload", "secret"), "Lost private access")
        baseline.check("internal field\n" in hover("struct PrivatePayload", "moduleValue"), "Lost internal access")
        baseline.check("static field\nconst uint count = 3" in hover("struct StaticPayload", "count"), "Lost static/const")
        for fragment, token in [("localFunction(", "local"), ("bool IsValid()", "IsValid")]:
            baseline.check("Natural field layout" not in hover(fragment, token), "Changed non-field hover")

        with tempfile.TemporaryDirectory(prefix="slang-field-") as directory:
            document = Path(directory) / "Field [类型] & copy.slang"
            old = "// 😀 字段\r\nstruct Mutable { uint first; uint second; };\r\nuint read(Mutable p) { return p.second; }\r\n"
            document.write_bytes(old.encode("utf-8"))
            open_doc(document, old)
            check("uint read(", "second", 4, 4, 4, document=document, text=old)
            new = old.replace("uint first;", "double first;")
            client.notify("textDocument/didChange", {"textDocument": {"uri": document.as_uri(), "version": 2},
                "contentChanges": [{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 3, "character": 0}}, "text": new}]})
            value = check("uint read(", "second", 4, 4, 8, document=document, text=new)
            baseline.check("%5B%E7%B1%BB%E5%9E%8B%5D" in value, "Missing encoded source link")
            client.notify("textDocument/didClose", {"textDocument": {"uri": document.as_uri()}})
        if dump:
            Path(dump).write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")
        print("PASS: field declarations/accesses, visibility, size/alignment/offset, arrays/matrices, generic substitution, inheritance, static/unknown layouts, documentation, UTF-16/CRLF links and edits")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--dump")
    args = parser.parse_args()
    run(args.slangd, args.dump)
