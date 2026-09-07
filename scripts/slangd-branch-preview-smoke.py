"""M4d temporary initial-macro overrides, isolation and restoration (real slangd)."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
check = baseline.check


def preview(defines=None, undefines=None):
    return {"version": 1, "fingerprint": hashlib.sha256(repr((defines, undefines)).encode()).hexdigest(),
            "defines": [{"name": k, "value": v} for k, v in (defines or {}).items()], "undefines": undefines or []}


def run(executable):
    client = baseline.Client(executable, {"slang.predefinedMacros": ["MODE=1", "GLOBAL=1", "REMOVED=1"]})
    try:
        with tempfile.TemporaryDirectory(prefix="slang-m4d-") as directory:
            root = Path(directory)
            entry, header = root / "Entry.slang", root / "Shared.slangh"
            source = Path(__file__).parent.parent.joinpath("src/test/testData/slang/PreprocessorPreview.slangh").read_text(encoding="utf-8")
            source = source.replace("\n", "\r\n")
            entry_source = '#ifndef OMIT\n#include "Shared.slangh"\n#endif\n#ifdef REPEAT\n#include "Shared.slangh"\n#endif\nfloat value;\n'
            entry.write_text(entry_source, encoding="utf-8")
            caps = client.request("initialize", {"workspaceFolders": [{"uri": root.as_uri(), "name": "preview"}], "capabilities": {}})["capabilities"]
            check(caps.get("experimental", {}).get("preprocessorPreview") == 1, "M4d capability not advertised")
            client.notify("initialized", {})
            baseline.open_trace(client, header, source, 7)

            def request(overrides=None, build=None, contextual=True):
                params = {"textDocument": {"uri": header.as_uri()}}
                if contextual:
                    params["contextUri"] = entry.as_uri()
                if build is not None:
                    params["buildContext"] = build
                if overrides is not None:
                    params["preview"] = overrides
                result = client.request("slang/textDocument/preprocessorTrace", params)
                check(result is not None and result["uri"] == header.as_uri() and result["version"] == 7, "Target identity changed")
                check(result["previewFingerprint"] == (overrides["fingerprint"] if overrides else ""), "Preview identity missing")
                check(result["contextFingerprint"] == (build["fingerprint"] if build else ""), "Build identity changed")
                return result

            def active(result, directive):
                return next(d["active"] for d in result["directives"]
                            if source.splitlines()[d["range"]["start"]["line"]] == directive)

            normal = request()
            check(normal["status"] == "ok" and normal["contextVersion"] == -1, "Closed root not traced")
            check(active(normal, "#if MODE == 1") and active(normal, "#ifdef GLOBAL"), "Workspace baseline missing")
            # initialized/configuration is asynchronous; capture the ordinary cache baseline only
            # after a root trace has confirmed that the workspace configuration was applied.
            ordinary = baseline.trace(client, header.as_uri(), 7)
            check(active(ordinary, "#if MODE == 1"), "Ordinary workspace configuration not ready")
            temporary = preview({"MODE": "2", "EMPTY": "", "ADDED": "1"}, ["GLOBAL", "REMOVED"])
            result = request(temporary)
            check(active(result, "#elif MODE == 2") and not active(result, "#if MODE == 1"), "Define did not replace workspace macro")
            check(not active(result, "#ifdef GLOBAL") and not active(result, "#ifdef REMOVED"), "Workspace undefine failed")
            check(active(result, "#ifdef EMPTY") and active(result, "#ifdef ADDED"), "Empty/new preview macros lost")
            check(request() == normal, "Stopping root preview did not restore exact baseline")
            check(request(temporary) == result, "Repeated preview not deterministic")
            restored_ordinary = baseline.trace(client, header.as_uri(), 7)
            check(restored_ordinary == ordinary, f"Ordinary cached trace polluted: before={ordinary}, after={restored_ordinary}")
            root_only = request(preview({"MODE": "2"}), contextual=False)
            check(active(root_only, "#elif MODE == 2"), "Preview without contextUri failed")
            check(baseline.trace(client, header.as_uri(), 7) == ordinary, "Preview-only request mutated ordinary session")

            for inherit in [False, True]:
                build = {"version": 1, "fingerprint": hashlib.sha256(str(inherit).encode()).hexdigest(),
                         "inheritWorkspace": inherit, "defines": [{"name": "MODE", "value": "1"}, {"name": "REMOVED", "value": "1"}],
                         "undefines": ["ADDED"], "includePaths": [str(root)], "target": "spirv", "profile": "spirv_1_5"}
                original_variant = request(build=build)
                changed = request(temporary, build)
                check(changed["status"] == "ok" and active(changed, "#elif MODE == 2"), "Preview failed over Variant")
                check(not active(changed, "#ifdef REMOVED") and active(changed, "#ifdef ADDED"), "Preview precedence over Variant wrong")
                undefined = request(preview(undefines=["MODE"]), build)
                check(active(undefined, "#else"), "Undefine did not remove Variant macro")
                check(request(build=build) == original_variant, "Stopping preview changed original Variant")
                check(request() == normal, "Switching back to root leaked preview or Variant")

            for replacement in [{"version": 0}, {"version": 2}, {"version": 0, "fingerprint": "", "defines": [], "undefines": []},
                                {"fingerprint": "BAD"}, {"defines": [{"name": "BAD-NAME", "value": "1"}]},
                                {"defines": [{"name": "F(x)", "value": "x"}]}, {"defines": [{"name": "A", "value": "x" * 4097}]},
                                {"defines": [{"name": "A", "value": "😀" * 1025}]},
                                {"defines": [{"name": "A", "value": "x\ny"}]}, {"defines": [{"name": "A", "value": "\0"}]},
                                {"defines": [{"name": "A", "value": "1"}] * 2}, {"undefines": ["A", "A"]},
                                {"undefines": ["MODE"]}, {"undefines": [f"X{i}" for i in range(257)]}]:
                bad = {**temporary, **replacement}
                rejected = request(bad)
                check(rejected["status"] == "invalidContext" and rejected["contextError"]
                      and not rejected["directives"] and not rejected["inactiveRegions"], f"Invalid preview accepted: {str(replacement)[:100]}")
                check(request() == normal, "Invalid preview damaged baseline")

            for malformed in [{}, {"version": 1}, {**preview(), "includePaths": [str(root)]}]:
                try:
                    client.request("slang/textDocument/preprocessorTrace", {"textDocument": {"uri": header.as_uri()}, "preview": malformed})
                except AssertionError:
                    pass
                else:
                    raise AssertionError(f"Malformed preview accepted: {malformed}")

            # Upstream Slang JSON marshalling normalizes null lists to empty lists.
            empty = {**preview(), "defines": None, "undefines": None}
            check(request(empty)["directives"] == normal["directives"], "Empty preview changed baseline")
            null_preview = client.request("slang/textDocument/preprocessorTrace", {
                "textDocument": {"uri": header.as_uri()}, "contextUri": entry.as_uri(), "preview": None})
            check(null_preview == normal, "Null preview was not treated as absent")

            for overrides, status in [(preview({"OMIT": "1"}), "notIncluded"), (preview({"REPEAT": "1"}), "ambiguous")]:
                rejected = request(overrides)
                check(rejected["status"] == status and not rejected["directives"] and not rejected["inactiveRegions"], "Non-unique preview didn't fail closed")
                check(request() == normal, "Failed contextual preview polluted baseline")

            # The opened root's unsaved source remains authoritative. No source is rewritten.
            client.notify("textDocument/didOpen", {"textDocument": {"uri": entry.as_uri(), "languageId": "slang", "version": 42,
                          "text": '#undef MODE\n#define MODE 1\n#define GLOBAL 1\n' + entry_source}})
            authoritative = request(temporary)
            check(authoritative["contextVersion"] == 42 and active(authoritative, "#if MODE == 1")
                  and active(authoritative, "#ifdef GLOBAL"), "Unsaved source directives lost authority")
            client.notify("textDocument/didClose", {"textDocument": {"uri": entry.as_uri()}})
            check(request() == normal, "Closing root did not restore disk baseline")
            client.notify("textDocument/didClose", {"textDocument": {"uri": header.as_uri()}})
            client.notify("textDocument/didOpen", {"textDocument": {"uri": header.as_uri(), "languageId": "slang", "version": 7, "text": source}})
            check(request() == normal, "Reopening target retained temporary server state")
            check(header.read_bytes() == source.encode("utf-8") and entry.read_text(encoding="utf-8") == entry_source, "Source was modified")
            client.request("shutdown", None)
            client.notify("exit", None)
            print("PASS: M4d root/Variant preview, define/undefine precedence, empty values, fingerprint echo, "
                  "invalid/malformed inputs, CRLF/UTF-16/#line, notIncluded/ambiguous, unsaved source authority, "
                  "ordinary-session isolation and exact stop/switch/close restoration")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
