"""M4c compiler context regression suite; Python standard library only."""
import argparse
import importlib.util
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("trace_smoke", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
check = baseline.check


def run(executable):
    client = baseline.Client(executable)
    try:
        with tempfile.TemporaryDirectory(prefix="slang-m4c-") as directory:
            root = Path(directory)
            caps = client.request("initialize", {
                "workspaceFolders": [{"uri": root.as_uri(), "name": "contexts"}], "capabilities": {},
            })["capabilities"]
            check(caps.get("experimental", {}).get("preprocessorContexts") == 1, "Missing M4c capability")
            client.notify("initialized", {})
            header = root / "Header.slangh"
            source = '#line 300 "logical.h"\r\n/*😀*/ #if FLAG\r\n// active\r\n#else\r\n// inactive\r\n#endif\r\n'
            baseline.open_trace(client, header, source, 7)

            def context(path):
                result = client.request("slang/textDocument/preprocessorTrace", {
                    "textDocument": {"uri": header.as_uri()}, "contextUri": path.as_uri(),
                })
                check(result is not None and result["uri"] == header.as_uri() and result["version"] == 7,
                      "Target identity lost")
                check(result["contextUri"] == path.as_uri(), "Context identity lost")
                return result

            a, b = root / "A.slang", root / "B.slang"
            a.write_text('#define FLAG 1\n#if FLAG\n#include "Bridge.slangh"\n#endif\nfloat a;\n', encoding="utf-8")
            b.write_text('#define FLAG 0\n#include "Header.slangh"\nfloat b;\n', encoding="utf-8")
            (root / "Bridge.slangh").write_text('#include "Header.slangh"\n', encoding="utf-8")
            first = context(a)
            check(first["status"] == "ok" and first["occurrenceCount"] == 1 and first["contextVersion"] == -1,
                  "Saved root context metadata")
            check(first["directives"][0]["active"], "Transitive include lost root macro")
            check(first["directives"][0]["keywordRange"]["start"] == {"line": 1, "character": 8},
                  "Physical UTF-16 location was corrupted by #line or emoji")
            check(first["directives"][0]["parentDirective"] == -1, "Root conditional index leaked into header")
            check(first["inactiveRegions"][0]["range"] == baseline.span(4, 5), "Header inactive body range")
            check(not context(b)["directives"][0]["active"], "Root B macro did not switch")
            check(context(a) == first, "Contexts contaminated each other")

            # Disk edits are visible to an isolated context version, without a document notification.
            b.write_text('#define FLAG 1\n#include "Header.slangh"\nfloat b;\n', encoding="utf-8")
            check(context(b)["directives"][0]["active"], "Saved root was cached")
            baseline.open_trace(client, b, '#define FLAG 0\n#include "Header.slangh"\nfloat b;\n', 9)
            client.notify("textDocument/didChange", {"textDocument": {"uri": b.as_uri(), "version": 10},
                "contentChanges": [{"range": {"start": {"line": 0, "character": 13},
                                               "end": {"line": 0, "character": 14}}, "text": "1"}]})
            edited = context(b)
            check(edited["contextVersion"] == 10 and edited["directives"][0]["active"], "Unsaved root edit ignored")

            skipped = root / "Skipped.slang"
            skipped.write_text('#if 0\n#include "Header.slangh"\n#endif\nfloat x;\n', encoding="utf-8")
            check(context(skipped)["status"] == "notIncluded", "Skipped include falsely treated as active context")
            imported = root / "Imported.slang"
            (root / "Module.slang").write_text('#include "Header.slangh"\n', encoding="utf-8")
            imported.write_text('import Module;\nfloat x;\n', encoding="utf-8")
            check(context(imported)["status"] == "notIncluded", "Import falsely inherited root preprocessing context")

            repeated = root / "Repeated.slang"
            repeated.write_text('#define FLAG 1\n#include "Header.slangh"\n#undef FLAG\n#define FLAG 0\n#include "Header.slangh"\nfloat x;\n', encoding="utf-8")
            duplicate = context(repeated)
            check(duplicate["status"] == "ambiguous" and duplicate["occurrenceCount"] == 2
                  and duplicate["directives"] == [] and duplicate["inactiveRegions"] == [],
                  "Repeated include instances were merged")
            # pragma once eliminates a second *executed* file instance.
            client.notify("textDocument/didClose", {"textDocument": {"uri": header.as_uri()}})
            baseline.open_trace(client, header, "#pragma once\n" + source, 7)
            check(context(repeated)["occurrenceCount"] == 1, "pragma once was ignored")
            client.request("shutdown", None)
            client.notify("exit", None)
            print("PASS: M4c capability, saved/unsaved roots, direct/transitive includes, root switching, "
                  "disk edits, UTF-16/CRLF/#line, local branch links, inactive include, import isolation, "
                  "repeated instances, pragma once and cache isolation")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
