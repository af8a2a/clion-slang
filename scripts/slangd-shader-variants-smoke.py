"""M4e isolated build-context regression tests against a real slangd."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import time

spec = importlib.util.spec_from_file_location("trace_smoke", Path(__file__).with_name("slangd-preprocessor-trace-smoke.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
check = baseline.check


def run(executable):
    client = baseline.Client(executable, {"slang.predefinedMacros": ["GLOBAL=1", "MODE=99", "REMOVED=1"]})
    try:
        with tempfile.TemporaryDirectory(prefix="slang-m4e-") as directory:
            root = Path(directory)
            for name, value in [("one", 1), ("two", 2)]:
                (root / name).mkdir()
                (root / name / "choice.slangh").write_text(f"#define INCLUDE_CHOICE {value}\n", encoding="utf-8")
            entry = root / "Entry.slang"
            entry.write_text('#include <choice.slangh>\n#include "Shared.slangh"\nfloat value;\n', encoding="utf-8")
            header = root / "Shared.slangh"
            source = ('#if MODE == 1\n// mode one 😀\n#elif MODE == 2\n// mode two\n#else\n// baseline\n#endif\n'
                      '#if INCLUDE_CHOICE == 1\n// include one\n#else\n// include two\n#endif\n'
                      '#ifdef GLOBAL\n// inherited global\n#endif\n'
                      '#ifdef REMOVED\n// removed global\n#endif\n'
                      '#ifdef EMPTY\n// empty macro is defined, not assigned 1\n#endif\n')
            caps = client.request("initialize", {"workspaceFolders": [{"uri": root.as_uri(), "name": "variants"}], "capabilities": {}})["capabilities"]
            check(caps.get("experimental", {}).get("preprocessorVariants") == 1, "Missing M4e capability")
            client.notify("initialized", {})
            baseline.open_trace(client, header, source, 5)

            def request(options):
                result = client.request("slang/textDocument/preprocessorTrace", {
                    "textDocument": {"uri": header.as_uri()}, "contextUri": entry.as_uri(), "buildContext": options,
                })
                check(result is not None and result["version"] == 5 and result["contextVersion"] == -1, "Trace identity lost")
                check(result["contextFingerprint"] == options["fingerprint"], "Variant fingerprint not echoed")
                return result

            first = {"version": 1, "fingerprint": hashlib.sha256(b"first").hexdigest(), "inheritWorkspace": False,
                     "defines": [{"name": "MODE", "value": "1"}, {"name": "EMPTY", "value": ""}], "undefines": [],
                     "includePaths": [str(root / "one")], "target": "spirv", "profile": "spirv_1_5"}
            one = request(first)
            check(one["status"] == "ok", f"First variant unavailable: {one}")
            directives = one["directives"]
            check(directives[0]["active"] and not directives[1]["active"], "MODE=1 not applied")
            check(directives[4]["active"], "Variant include path not used")
            check(not directives[7]["active"] and not directives[9]["active"], "Workspace macros leaked into isolated variant")
            check(directives[11]["active"], "Empty macro value was dropped")

            second = copy.deepcopy(first)
            second.update(fingerprint=hashlib.sha256(b"second").hexdigest(), inheritWorkspace=True,
                          defines=[{"name": "MODE", "value": "2"}], undefines=["REMOVED"],
                          includePaths=[str(root / "two")], target="dxil", profile="sm_6_6")
            two = request(second)
            check(two["status"] == "ok", f"Second variant unavailable: {two}")
            directives = two["directives"]
            check(not directives[0]["active"] and directives[1]["active"], "Variant failed to override workspace MODE")
            check(not directives[4]["active"], "Second variant reused first include path")
            check(directives[7]["active"] and not directives[9]["active"], "Inheritance/undefines not applied")
            check(not directives[11]["active"], "Macro from previous variant leaked")
            check(request(first) == one, "Switching variants polluted cached compiler state")
            normal = baseline.trace(client, header.as_uri(), 5)
            check(normal["directives"][7]["active"] and normal["directives"][9]["active"], "Variant modified ordinary workspace macros")

            for replacement in [{"version": 0}, {"version": 2}, {"target": "invalid"}, {"profile": "invalid_profile"},
                                {"includePaths": ["relative/path"]}, {"defines": [{"name": "BAD-NAME", "value": "1"}]},
                                {"undefines": ["MODE"]}, {"fingerprint": "bad"},
                                {"defines": [{"name": "A", "value": "a\nb"}]},
                                {"defines": [{"name": "A", "value": "1"}] * 257}]:
                bad = {**first, **replacement}
                result = request(bad)
                check(result["status"] == "invalidContext" and result["contextError"]
                      and result["directives"] == [] and result["inactiveRegions"] == [], f"Invalid context accepted: {replacement}")
            # The source preprocessor remains authoritative over injected macros.
            entry.write_text('#undef MODE\n#define MODE 2\n#include <choice.slangh>\n#include "Shared.slangh"\nfloat value;\n', encoding="utf-8")
            check(request(first)["directives"][1]["active"], "Source macro directives lost authority")
            client.request("shutdown", None)
            client.notify("exit", None)
            print("PASS: M4e capability, macro variants, empty values, include-path variants, workspace "
                  "inheritance/override/undefine, target/profile validation, fingerprints, invalid contexts, "
                  "source macro authority, variant switching and ordinary-session isolation")
    finally:
        client.close()


def check_metallic(executable, project):
    """Optional read-only real-project check; source files and the project are never written."""
    project = Path(project).resolve()
    template = json.loads(Path(__file__).parent.parent.joinpath("docs/metallic-slang-variants.example.json").read_text(encoding="utf-8"))
    context = template["contexts"][0]
    source_path = project / "Shaders/ScenePathTrace.slang"
    source = source_path.read_text(encoding="utf-8")
    client = baseline.Client(executable)
    try:
        # Do not recursively index the application's external/vendor trees for this trace-only check.
        client.request("initialize", {"workspaceFolders": [], "capabilities": {}})
        client.notify("initialized", {})
        client.notify("textDocument/didOpen", {"textDocument": {
            "uri": source_path.as_uri(), "languageId": "slang", "version": 1, "text": source,
        }})
        for variant in context["variants"]:
            definitions = {**context["defines"], **variant.get("defines", {})}
            options = {"version": 1, "fingerprint": hashlib.sha256(variant["id"].encode()).hexdigest(), "inheritWorkspace": False,
                       "defines": [{"name": k, "value": v} for k, v in definitions.items()], "undefines": [],
                       "includePaths": [str(project / "Shaders")], "target": context["target"], "profile": context["profile"]}
            started = time.monotonic()
            trace = client.request("slang/textDocument/preprocessorTrace", {
                "textDocument": {"uri": source_path.as_uri()}, "contextUri": source_path.as_uri(), "buildContext": options,
            })
            check(trace is not None and trace["status"] == "ok", f"metallic {variant['id']}: missing trace")
            lines = source.splitlines()
            sharc = next(d for d in trace["directives"] if lines[d["range"]["start"]["line"]].strip() == "#if defined(SHARC_UPDATE) || defined(SHARC_QUERY)")
            nrc = next(d for d in trace["directives"] if lines[d["range"]["start"]["line"]].strip() == "#if defined(NRC_UPDATE) || defined(NRC_QUERY)")
            check(sharc["active"] == variant["id"].startswith("sharc-"), "metallic SHaRC branch mismatch")
            check(nrc["active"] == variant["id"].startswith("nrc-"), "metallic NRC branch mismatch")
            print(f"PASS: metallic {variant['id']}, {len(trace['directives'])} directives, {time.monotonic() - started:.2f}s")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--metallic", help="Optional read-only check of the metallic example against this project path")
    args = parser.parse_args()
    run(args.slangd)
    if args.metallic:
        check_metallic(args.slangd, args.metallic)
