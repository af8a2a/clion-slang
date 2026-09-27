"""Verify semantic builtin provenance against real Slang/HLSL hover responses."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def run(executable, dump=None):
    path = Path(__file__).resolve().parent.parent / "src/test/testData/slang/BuiltinDocumentation.slang"
    source = path.read_text(encoding="utf-8")
    samples = {}
    client = baseline.Client(executable)
    try:
        client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
        client.notify("initialized", {})
        for extension in ("slang", "hlsl"):
            document = path.with_suffix("." + extension)
            client.notify("textDocument/didOpen", {"textDocument": {
                "uri": document.as_uri(), "languageId": extension, "version": 1, "text": source}})
            for fragment, token, builtin in [
                ("float scalar", "abs", True), ("float scalar", "sin", True),
                ("float3 direction", "normalize", True), ("uint flags", "RAY_FLAG_NONE", True),
                ("uint flags", "WaveGetLaneIndex", True), ("float differentiable", "detach", True),
                ("uint bits", "asuint", True), ("float custom", "abs", False),
                ("uint customFlag", "RAY_FLAG_NONE", False), ("float userOverload", "abs", False),
                ("uint localVariable", "RAY_FLAG_NONE", False),
            ]:
                line = next(i for i, text in enumerate(source.splitlines()) if fragment in text)
                col = source.splitlines()[line].rindex(token)
                result = client.request("textDocument/hover", {"textDocument": {"uri": document.as_uri()},
                    "position": {"line": line, "character": col + 1}})
                baseline.check(result and result["contents"]["kind"] == "markdown", f"Missing hover: {fragment}")
                value = result["contents"]["value"]
                baseline.check((f"<!-- slang-builtin:core:{token} -->" in value) == builtin, value)
                baseline.check(result["range"]["start"] == {"line": line, "character": col}, str(result))
                samples[extension + ":" + fragment + ":" + token] = result
            client.notify("textDocument/didClose", {"textDocument": {"uri": document.as_uri()}})
        if dump:
            Path(dump).write_text(json.dumps(samples, indent=2) + "\n", encoding="utf-8")
        print("PASS: builtin functions/constants, generic overloads, namespace/global/local shadows, Slang/HLSL ranges")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--dump")
    args = parser.parse_args()
    run(args.slangd, args.dump)
