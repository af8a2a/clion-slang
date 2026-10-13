"""Verify enclosing module using scopes when slangd opens an implementing file."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location("corpus", Path(__file__).with_name("slangd-corpus-validation.py"))
corpus = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus)

DEPENDENCY = """module Dependency;
namespace Library {
public struct Record { public uint value; }
public interface IRecord {}
public bool resolveVisibilityRecordIndex(uint index, out uint result) { result = index; return true; }
public struct SharedName { public uint outerValue; }
namespace Nested { public uint nestedValue() { return 2; } }
}
namespace Local {
public struct SharedName { public uint value; }
}
namespace SiblingOnly { public uint siblingOnly() { return 4; } }
namespace PrivateUsing { public uint privateUsing() { return 5; } }
using PrivateUsing;
"""
OWNER = """module Owner;
import Dependency;
using Library;
__include "Part.slang";
__include "Sibling.slang";
"""
PART = """implementing Owner;
using Nested;
using Local;
extension Library.Record { uint getValue() { return value; } }
public T identity<T : IRecord>(T value) { return value; }
public uint useRecord(Record record) {
    uint result;
    resolveVisibilityRecordIndex(record.value, result);
    SharedName local = SharedName(3);
    return result + nestedValue() + local.value;
}
"""


def run(executable):
    with tempfile.TemporaryDirectory(prefix="slang-implementing-scope-") as directory:
        root = Path(directory)
        sources = {"Dependency.slang": DEPENDENCY, "Owner.slang": OWNER,
                   "Part.slang": PART, "Sibling.slang": "implementing Owner;\nusing SiblingOnly;\n"}
        for name, source in sources.items():
            (root / name).write_text(source, encoding="utf-8")
        configuration = {"slang.additionalSearchPaths": [str(root)],
                         "slang.searchInAllWorkspaceDirectories": False}

        def validate(name, source, expected=()):
            path = root / name
            path.write_text(source, encoding="utf-8")
            result = corpus.validate(executable, root, path, configuration)
            corpus.smoke.baseline.check(result["status"] == "completed", str(result))
            errors = [d for p in result["publications"] for d in p["diagnostics"] if d.get("severity") == 1]
            messages = json.dumps(errors)
            if expected:
                for identifier in expected:
                    corpus.smoke.baseline.check("undefined identifier '" + identifier + "'" in messages,
                                               f"Expected real undefined name {identifier}: {messages}")
            else:
                corpus.smoke.baseline.check(not errors, f"{name}: {messages}")
            corpus.smoke.baseline.check(result["source_unchanged"], "LSP changed a source file")

        # Validate the real primary module too: both entry routes must resolve the same names.
        validate("Owner.slang", OWNER)
        validate("Part.slang", PART)
        validate("Part.slang", PART.replace("extension Library.Record", "extension Record"))
        validate("Part.slang", PART + "uint missing() { return trulyUndefined + siblingOnly() + privateUsing(); }\n",
                 ("trulyUndefined", "siblingOnly", "privateUsing"))
        validate("Part.slang", PART.replace("\n", "\r\n"))
        # Removing the owner's using must restore genuine diagnostics, not mask them.
        (root / "Owner.slang").write_text(OWNER.replace("using Library;\n", ""), encoding="utf-8")
        validate("Part.slang", "implementing Owner;\nuint useRecord() { uint index; resolveVisibilityRecordIndex(0, index); return index; }\n",
                 ("resolveVisibilityRecordIndex",))
    print("PASS: implementing owner usings, nested usings, extension/generic headers, local shadowing, sibling/import isolation, missing names and CRLF diagnostics")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
