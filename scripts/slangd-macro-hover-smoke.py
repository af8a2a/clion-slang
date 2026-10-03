"""Validate compiler-backed macro expansion hovers using the real language server."""
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
    path = Path(__file__).resolve().parent.parent / "src/test/testData/slang/MacroHover.slang"
    source = path.read_text(encoding="utf-8")
    samples = {}
    client = baseline.Client(executable, {"slang.predefinedMacros": ["CONFIG_VALUE=9"]})
    try:
        client.request("initialize", {"workspaceFolders": [], "capabilities": {"workspace": {"configuration": True}}})
        client.notify("initialized", {})

        def open_doc(document, text):
            client.notify("textDocument/didOpen", {"textDocument": {"uri": document.as_uri(),
                "languageId": "slang", "version": 1, "text": text}})

        def hover(fragment, token, expected, document=path, text=source):
            line = next(i for i, row in enumerate(text.splitlines()) if fragment in row)
            row = text.splitlines()[line]
            col = len(row[:row.index(token)].encode("utf-16-le")) // 2
            result = client.request("textDocument/hover", {"textDocument": {"uri": document.as_uri()},
                "position": {"line": line, "character": col + 1}})
            baseline.check(result and result["contents"]["kind"] == "markdown", f"Missing hover: {fragment}")
            value = result["contents"]["value"]
            baseline.check("<!-- slang-macro-hover:1 -->" in value, value)
            baseline.check(result["range"]["start"] == {"line": line, "character": col}, str(result))
            if expected is not None:
                match = re.search(r"\*\*Expansion preview\*\*\n\n```slang\n(.*?)\n```", value, re.S)
                baseline.check(match is not None and match.group(1) == expected, value)
            samples[fragment] = result
            return value

        open_doc(path, source)
        for fragment, token, expected in [
            ("useAlias", "ALIAS", "params . history . data"),
            ("useAdd", "ADD", "( ( 2 ) + ( 7 ) )"),
            ("usePaste", "CAT", "value"), ("useString", "STR", '\"hello world\"'),
            ("useZero", "ZERO", "7"), ("emptyUse", "EMPTY", ""),
            ("useRecursive", "LOOP", "LOOP"), ("oldValue", "CHANGING", "1"),
            ("newValue", "CHANGING", "2"),
        ]:
            value = hover(fragment, token, expected)
            if fragment == "useZero": baseline.check("#define ZERO()" in value, value)
            if fragment == "oldValue": baseline.check("#define CHANGING 1" in value, value)
        value = hover("#define ADD", "ADD", None)
        baseline.check("Expansion preview unavailable at this location." in value, value)
        updated = source.replace("#define CHANGING 2", "#define CHANGING 3")
        client.notify("textDocument/didChange", {"textDocument": {"uri": path.as_uri(), "version": 2},
            "contentChanges": [{"text": updated}]})
        hover("newValue", "CHANGING", "3", text=updated)
        with tempfile.TemporaryDirectory(prefix="slang-macro-hover-") as temp:
            root = Path(temp)
            (root / "Defs.slang").write_text("#define INCLUDED 12\n", encoding="utf-8")
            document = root / "Main.slang"
            text = '#include "Defs.slang"\r\n/* 中文 😀 */ int includedValue = INCLUDED;\r\nint configured = CONFIG_VALUE;\r\n#define SUM(...) ADD(__VA_ARGS__)\r\n#define ADD(x,y) ((x)+(y))\r\nint variadic = SUM(2,3);\r\n'
            open_doc(document, text)
            hover("includedValue", "INCLUDED", "12", document, text)
            hover("configured", "CONFIG_VALUE", "9", document, text)
            hover("variadic", "SUM", "( ( 2 ) + ( 3 ) )", document, text)
            big = "#define BIG " + ", ".join(["1"] * 600) + "\nint bigValue[] = {BIG};\n"
            document = root / "Big.slang"; open_doc(document, big)
            value = hover("bigValue", "BIG", None, document, big)
            baseline.check("Expansion preview truncated after 512 tokens." in value, value)
        if dump: Path(dump).write_text(json.dumps(samples, indent=2) + "\n", encoding="utf-8")
        print("PASS: actual macro expansion, nested/parameter/variadic/empty/recursive macros, paste/stringize, redefinitions, includes, configuration, UTF-16/CRLF, unsaved edits and preview limit")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--dump")
    args = parser.parse_args()
    run(args.slangd, args.dump)
