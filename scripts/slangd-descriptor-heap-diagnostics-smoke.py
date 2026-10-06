"""Check real diagnostics for typed HLSL SM 6.6 descriptor heap expressions."""
import argparse
import importlib.util
from pathlib import Path
import queue
import tempfile
from urllib.parse import unquote

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)

class Messages(queue.Queue):
    def __init__(self):
        super().__init__()
        self.diagnostics = queue.Queue()
    def put(self, message, *args, **kwargs):
        if isinstance(message, dict) and message.get("method") == "textDocument/publishDiagnostics":
            self.diagnostics.put(message["params"])
        super().put(message, *args, **kwargs)

def diagnostics(client, uri):
    while True:
        params = client.messages.diagnostics.get(timeout=20)
        if unquote(params["uri"]).lower() == unquote(uri).lower():
            return [d for d in params["diagnostics"] if d.get("severity") == 1]

def run(executable):
    valid = (Path(__file__).parent.parent / "src/test/testData/slang/DescriptorHeapDiagnostics.slang").read_text(encoding="utf-8")
    invalid = valid + """
float badScalar() { return ResourceDescriptorHeap[0]; }
SamplerState wrongSamplerHeap() { return ResourceDescriptorHeap[0]; }
Texture2D<float4> wrongResourceHeap() { return SamplerDescriptorHeap[0]; }
SamplerState wrongIndex() { return SamplerDescriptorHeap[float2(0, 1)]; }
SamplerState missingIndex() { return SamplerDescriptorHeap; }
ConstantBuffer<Constants> wrongCbvHeap() { return SamplerDescriptorHeap[0]; }
float unknownName() { return trulyUndefined; }
float badShadow(float SamplerDescriptorHeap) { return SamplerDescriptorHeap[0]; }
"""
    for language in ["slang", "hlsl"]:
        client = baseline.Client(executable)
        client.messages = Messages()
        try:
            with tempfile.TemporaryDirectory(prefix="slang-heap-diagnostics-") as directory:
                path = Path(directory) / ("Heaps." + language)
                source = "float seed() { return undefinedSeed; }\n"
                path.write_text(source, encoding="utf-8")
                uri = path.as_uri()
                client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
                client.notify("initialized", {})
                # Drain initialization/configuration requests before opening the document.
                # A late config response otherwise invalidates the first diagnostic snapshot.
                client.request("textDocument/documentSymbol", {"textDocument": {"uri": uri}})
                client.notify("textDocument/didOpen", {"textDocument": {"uri": uri,
                              "languageId": language, "version": 1, "text": source}})
                client.request("textDocument/semanticTokens/full", {"textDocument": {"uri": uri}})
                baseline.check(diagnostics(client, uri), "Seed error was not published")
                for version, changed in enumerate([valid, invalid, valid.replace("return ", "/* 😀 */ return ").replace("\n", "\r\n")], 2):
                    client.notify("textDocument/didChange", {"textDocument": {"uri": uri, "version": version},
                                  "contentChanges": [{"range": {"start": {"line": 0, "character": 0},
                                  "end": {"line": source.count("\n"), "character": 0}}, "text": changed}]})
                    errors = diagnostics(client, uri)
                    if version == 3:
                        lines = {d["range"]["start"]["line"] for d in errors}
                        for diagnostic in errors:
                            for related in diagnostic.get("relatedInformation", []):
                                if unquote(related["location"]["uri"]).lower() == unquote(uri).lower():
                                    lines.add(related["location"]["range"]["start"]["line"])
                        for name in ["badScalar", "wrongSamplerHeap", "wrongResourceHeap", "wrongIndex", "missingIndex", "wrongCbvHeap", "unknownName", "badShadow"]:
                            line = next(i for i, text in enumerate(changed.splitlines()) if name in text)
                            baseline.check(line in lines, f"Missing type error for {name}: {errors}")
                    else:
                        baseline.check(not errors, f"Valid descriptor heap code has errors: {errors}")
                    source = changed
        finally:
            client.close()
    print("PASS: published diagnostics clear for screenshot functions, sampler/comparison/texture/buffer/CBV types; reject wrong heap kind/index/scalar/missing index and invalid user symbols; Slang/HLSL and unsaved UTF-16/CRLF edits")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
