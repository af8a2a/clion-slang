"""Real-server regression for struct hover, including function parameter type references."""
import argparse
import importlib.util
import json
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    "baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def run(executable, dump=None):
    path = Path(__file__).resolve().parent.parent / "src/test/testData/slang/StructHover.slang"
    source = path.read_text(encoding="utf-8")
    client = baseline.Client(executable)
    samples = {}
    try:
        client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
        client.notify("initialized", {})

        def open_document(path, source, version=1):
            client.notify("textDocument/didOpen", {"textDocument": {
                "uri": path.as_uri(), "languageId": "slang", "version": version, "text": source}})

        def replace_document(document, old, new):
            lines = old.split("\n")
            client.notify("textDocument/didChange", {"textDocument": {
                "uri": document.as_uri(), "version": 2}, "contentChanges": [{
                "range": {"start": {"line": 0, "character": 0}, "end": {
                    "line": len(lines) - 1, "character": len(lines[-1].encode("utf-16-le")) // 2}},
                "text": new}]})

        def hover(fragment, token, document=path, text=source):
            line = next(i for i, value in enumerate(text.splitlines()) if fragment in value)
            match = re.search(r"\b" + re.escape(token) + r"\b", text.splitlines()[line])
            baseline.check(match is not None, f"Missing token: {fragment}: {token}")
            col = match.start()
            if "::" in token:
                col += token.rfind("::") + 2
                token = token.split("::")[-1]
            col = len(text.splitlines()[line][:col].encode("utf-16-le")) // 2
            result = client.request("textDocument/hover", {"textDocument": {"uri": document.as_uri()},
                "position": {"line": line, "character": col + 1}})
            baseline.check(result and result["contents"]["kind"] == "markdown", f"Missing hover: {fragment}")
            baseline.check(result["range"] == {
                "start": {"line": line, "character": col},
                "end": {"line": line, "character": col + len(token.encode("utf-16-le")) // 2}}, f"Wrong range: {fragment}: {result}")
            key = f"{fragment} [{token}]"
            samples[key] = result["contents"]["value"]
            return samples[key]

        def check_layout(text, size, alignment, padding, stride=None):
            for part in ["```slang\nstruct ", "Natural layout (bytes)", f"Size: `{size}`",
                         f"Alignment: `{alignment}`", f"Padding: `{padding}`"]:
                baseline.check(part in text, f"Missing {part}: {text}")
            baseline.check(("Array stride:" in text) == (stride is not None), text)
            if stride is not None:
                baseline.check(f"Array stride: `{stride}`" in text, text)

        open_document(path, source)
        for fragment in ["inspectHit(", "modifyHit(", "outputHit("]:
            text = hover(fragment, "UnifiedRT::Hit")
            check_layout(text, 24, 4, 3)
            baseline.check(text.startswith("```slang\nstruct Hit\n```"), text)
            baseline.check("(namespace `UnifiedRT`)" in text and "Ray intersection payload" in text, text)
            baseline.check("[StructHoverTypes.slang:4](<file:///" in text and "#L4>)" in text, text)
            baseline.check("Defined in" not in text, text)

        for fragment, token, size, alignment, padding, stride in [
            ("hzbMipOffset(", "GPUDrivenPreviewParams", 12, 4, 0, None),
            ("inspectTail(", "Tail", 5, 4, 0, 8),
            ("inspectNested(", "Nested", 14, 4, 3, 16),
            ("inspectRepeated(", "Repeated", 48, 4, 6, None),
            ("inspectStatic(", "WithStatic", 4, 4, 0, None),
            ("inspectWide(", "Wide", 16, 8, 7, None),
            ("inspectMatrix(", "MatrixPayload", 13, 2, 0, 14),
            ("inspectFloatBox(", "Box", 8, 4, 3, None),
            ("inspectDoubleBox(", "Box", 16, 8, 7, None),
            ("inspectDerived(", "Derived", 17, 8, 7, 24),
            ("inspectEnum(", "EnumPayload", 4, 2, 1, None),
            ("inspectEmpty(", "Empty", 0, 1, 0, None),
        ]:
            check_layout(hover(fragment, token), size, alignment, padding, stride)
        shadow = hover("inspectShadow(", "Outer::Inner::Hit")
        check_layout(shadow, 8, 8, 0)
        baseline.check("(namespace `Outer::Inner`)" in shadow, shadow)
        for fragment, token in [("struct Box<T>", "Box"), ("inspectResource(", "ResourcePayload"),
                                ("inspectPointer(", "PointerPayload"), ("inspectUnsized(", "UnsizedPayload")]:
            text = hover(fragment, token)
            baseline.check("Layout unavailable" in text and "Size:" not in text, text)

        check_layout(hover("struct Tail", "Tail"), 5, 4, 0, 8)
        for fragment, token in [("hzbMipOffset(", "params"), ("float identity(", "identity")]:
            text = hover(fragment, token)
            baseline.check("Natural layout" not in text and "Defined in" in text, text)

        # Refresh must reflect the new layout and preserve UTF-16 token positions.
        updated = source.replace("uint hzbMipOffset(", "uint changedOffset(").replace(
            "uint hzbMipOffset;", "double hzbMipOffset;")
        replace_document(path, source, updated)
        check_layout(hover("changedOffset(", "GPUDrivenPreviewParams", text=updated), 16, 8, 0)

        # URI escaping and recursive/incomplete source must not damage subsequent requests.
        with tempfile.TemporaryDirectory(prefix="slang-hover-") as directory:
            extra = Path(directory) / "Hover [类型] (copy).slang"
            extra_source = "struct Local { uint value; }\n/* 😀 */ void use(Local value) {}\n"
            extra.write_text(extra_source, encoding="utf-8")
            open_document(extra, extra_source)
            text = hover("void use(", "Local", document=extra, text=extra_source)
            check_layout(text, 4, 4, 0)
            baseline.check("Hover \\[类型\\] (copy).slang:1" in text and "%20" in text, text)
            link = re.search(r"\]\(<(file://.*?)#L1>\)", text)
            baseline.check(link and unquote(link.group(1)) == unquote(extra.as_uri()), text)
            broken = "struct Recursive { Recursive value; }\nvoid use(Recursive value) {}\n"
            replace_document(extra, extra_source, broken)
            text = hover("void use(", "Recursive", document=extra, text=broken)
            baseline.check("Layout unavailable" in text and "Size:" not in text, text)
            baseline.check("identity" in hover("float identity(", "identity", text=updated), "Server stopped responding")
        print("PASS: struct parameters, namespaces, layout/padding/stride, generics, static fields, "
              "nested arrays, fallback, source links, ranges, and edits")
    finally:
        client.close()
        if dump:
            Path(dump).write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--dump", help="Save actual hover Markdown for inspection")
    args = parser.parse_args()
    run(args.slangd, args.dump)
