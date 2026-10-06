"""Check exact variable declaration and reference targets with the real server."""
import argparse
import importlib.util
from pathlib import Path
import tempfile
from urllib.parse import unquote

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def verify(client, path, source):
    def position(name, last=False):
        offset = source.rindex(name) if last else source.index(name)
        prefix = source[:offset]
        line = prefix.count("\n")
        column = len(prefix.rsplit("\n", 1)[-1].encode("utf-16-le")) // 2
        return {"line": line, "character": column}
    def definition(pos):
        return client.request("textDocument/definition", {"textDocument": {"uri": path.as_uri()}, "position": pos})
    for name in ["field", "count", "globalValue", "parameter", ("renamedLocal" if "renamedLocal" in source else "localValue"), "item", "genericParameter", "genericLocal"]:
        expected = position(name)
        end = dict(expected, character=expected["character"] + len(name))
        for delta in [0, 1, len(name) - 1]:
            result = definition(dict(expected, character=expected["character"] + delta))
            baseline.check(isinstance(result, list) and len(result) == 1, f"Missing declaration target: {name}: {result}")
            baseline.check(unquote(result[0]["uri"]).lower() == unquote(path.as_uri()).lower(), f"Wrong declaration file: {name}")
            baseline.check(result[0]["range"] == {"start": expected, "end": end}, f"Wrong declaration range: {name}: {result}")
        if name not in ["count", "genericParameter"]:
            result = definition(position(name, last=True))
            baseline.check(result and result[0]["range"] == {"start": expected, "end": end}, f"Reference regressed: {name}")
    result = definition(position("sin"))
    baseline.check(result and result[0]["uri"].startswith("slang-synth://core/"), "Builtin navigation regressed")
    baseline.check(not definition(position("ghostIdentifier")), "Comment acquired a false target")


def run(executable):
    source = (Path(__file__).parent.parent / "src/test/testData/slang/DeclarationNavigation.slang").read_text(encoding="utf-8")
    client = baseline.Client(executable)
    try:
        with tempfile.TemporaryDirectory(prefix="slang-declaration-navigation-") as directory:
            path = Path(directory) / "Declarations.slang"
            path.write_text(source, encoding="utf-8")
            client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
            client.notify("initialized", {})
            client.notify("textDocument/didOpen", {"textDocument": {"uri": path.as_uri(), "languageId": "slang", "version": 1, "text": source}})
            verify(client, path, source)
            changed = source.replace("float localValue", "/* 😀 */ float localValue").replace("localValue", "renamedLocal").replace("\n", "\r\n")
            client.notify("textDocument/didChange", {"textDocument": {"uri": path.as_uri(), "version": 2}, "contentChanges": [{
                "range": {"start": {"line": 0, "character": 0}, "end": {"line": source.count("\n"), "character": 0}}, "text": changed}]})
            verify(client, path, changed)
            client.request("shutdown", None)
            client.notify("exit", None)
    finally:
        client.close()
    print("PASS: declaration name bounds, global/local/field/static/parameter/generic targets, references, builtin links, comments and unsaved Unicode/CRLF edits")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
