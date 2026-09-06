"""Exercise the M4a wire contract against a real slangd (Python 3 standard library)."""

import argparse
import json
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time


class Client:
    def __init__(self, executable, configuration=None):
        self.configuration = configuration or {}
        self.process = subprocess.Popen(
            [executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self.messages = queue.Queue()
        self.stderr = []
        self.next_id = 0
        threading.Thread(target=self.read, daemon=True).start()
        threading.Thread(target=lambda: self.stderr.append(self.process.stderr.read()), daemon=True).start()

    def read(self):
        try:
            while True:
                length = None
                while True:
                    line = self.process.stdout.readline()
                    if not line:
                        raise EOFError("slangd closed stdout")
                    if line == b"\r\n":
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":", 1)[1])
                if length is None or not 0 <= length <= 16 * 1024 * 1024:
                    raise ValueError("Invalid Content-Length")
                self.messages.put(json.loads(self.process.stdout.read(length)))
        except Exception as error:
            self.messages.put(error)

    def send(self, message):
        data = json.dumps({"jsonrpc": "2.0", **message}, ensure_ascii=False).encode("utf-8")
        self.process.stdin.write(f"Content-Length: {len(data)}\r\n\r\n".encode() + data)
        self.process.stdin.flush()

    def notify(self, method, params):
        self.send({"method": method, "params": params})

    def request(self, method, params):
        self.next_id += 1
        request_id = self.next_id
        self.send({"id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + 45
        while True:
            message = self.messages.get(timeout=max(0, deadline - time.monotonic()))
            if isinstance(message, Exception):
                raise message
            if "method" in message:
                if "id" in message:
                    result = None
                    if message["method"] == "workspace/configuration":
                        # Null preserves each server default.
                        result = [self.configuration.get(item.get("section")) for item in message["params"]["items"]]
                    self.send({"id": message["id"], "result": result})
                continue
            if message.get("id") == request_id:
                if "error" in message:
                    raise AssertionError(message["error"])
                return message.get("result")

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=10)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def trace(client, uri, version):
    result = client.request("slang/textDocument/preprocessorTrace", {"textDocument": {"uri": uri}})
    check(result is not None, "Missing trace")
    check(result["uri"] == uri and result["version"] == version, "URI/version mismatch")
    return result


def open_trace(client, path, source, version=1):
    path.write_bytes(source.encode("utf-8"))
    client.notify("textDocument/didOpen", {"textDocument": {
        "uri": path.as_uri(), "languageId": "slang", "version": version, "text": source,
    }})
    return trace(client, path.as_uri(), version)


def span(start_line, end_line, end_character=0):
    return {"start": {"line": start_line, "character": 0},
            "end": {"line": end_line, "character": end_character}}


def run(executable):
    client = Client(executable)
    try:
        with tempfile.TemporaryDirectory(prefix="slang-m4a-") as directory:
            root = Path(directory)
            capabilities = client.request("initialize", {
                "workspaceFolders": [{"uri": root.as_uri(), "name": "m4a"}],
                "capabilities": {},
            })["capabilities"]
            check(capabilities.get("experimental", {}).get("preprocessorTrace") == 1,
                  "M4a capability was not advertised")
            client.notify("initialized", {})
            path = root / "Trace.slang"
            source = (
                "#define ENABLED 1\n"
                "#if ENABLED\nint activeValue;\n"
                "#elif 1\nint laterValue;\n"
                "#else\nint elseValue;\n#endif\n"
                "#if 0\n#if UNDEFINED\nint nestedValue;\n#endif\n"
                "#elif defined(ENABLED)\nint fallbackValue;\n#endif\n"
                "#ifdef ENABLED\nint definedValue;\n#endif\n"
                "#ifndef MISSING\nint missingValue;\n#endif\n"
            )
            path.write_text(source, encoding="utf-8")
            uri = path.as_uri()
            client.notify("textDocument/didOpen", {"textDocument": {
                "uri": uri, "languageId": "slang", "version": 7, "text": source,
            }})
            result = trace(client, uri, 7)
            directives = result["directives"]
            check([d["kind"] for d in directives] == [
                "if", "elif", "else", "endif", "if", "if", "endif", "elif", "endif",
                "ifdef", "endif", "ifndef", "endif",
            ], f"Unexpected directives: {directives}")
            check(directives[0]["evaluated"] and directives[0]["value"] and directives[0]["active"],
                  "Active #if must be evaluated true")
            check(not directives[1]["evaluated"] and not directives[1]["active"],
                  "Already-selected #elif must not be evaluated")
            check(not directives[5]["evaluated"] and directives[5]["parentDirective"] == 4
                  and directives[5]["depth"] == 1, "Nested inactive condition must be skipped")
            check(directives[7]["evaluated"] and directives[7]["active"]
                  and directives[7]["matchingIfDirective"] == 4
                  and directives[7]["previousBranchDirective"] == 4, "#elif linkage is wrong")
            check(directives[9]["value"] and directives[11]["value"], "ifdef/ifndef failed")
            check({r["controllingDirective"] for r in result["inactiveRegions"]} >= {1, 2, 5},
                  "Missing inactive regions")
            check(trace(client, uri, 7) == result, "Repeated trace is unstable")

            client.notify("textDocument/didChange", {
                "textDocument": {"uri": uri, "version": 8},
                "contentChanges": [{"range": {"start": {"line": 0, "character": 16},
                                              "end": {"line": 0, "character": 17}}, "text": "0"}],
            })
            updated = trace(client, uri, 8)
            check(not updated["directives"][0]["active"] and updated["directives"][1]["active"],
                  "didChange did not switch branches")

            (root / "Config.slangh").write_text("#if 1\n#define INCLUDED 1\n#endif\n", encoding="utf-8")
            lines = [
                '#include "Config.slangh"', '#if INCLUDED && \\', '    1 // 😀', 'int yes;',
                '#else', '// 🌈 comment-only body', '', '#define HIDDEN 1',
                '#include "Missing.slangh"', '    #if 1', '/* nested */', '    #endif', '#endif',
                '#line 500 "virtual.slang"', '#if 0', '// 😎', '#endif',
            ]
            for separator, name in [("\n", "RangesLF"), ("\r\n", "RangesCRLF")]:
                ranges = open_trace(client, root / f"{name}.slang", separator.join(lines))
                ds = ranges["directives"]
                check([d["range"]["start"]["line"] for d in ds] == [1, 4, 9, 11, 12, 14, 16],
                      f"Included-file directives or #line leaked into trace: {ds}")
                check(ds[0]["active"], "Included macro did not affect the root condition")
                check(ds[0]["range"] == span(1, 2, len(lines[2].encode("utf-16-le")) // 2),
                      f"Multiline/UTF-16 range is wrong: {ds[0]}")
                check(ds[2]["keywordRange"] == {
                    "start": {"line": 9, "character": 5}, "end": {"line": 9, "character": 7}},
                    "Indented keyword range is wrong")
                check([r["range"] for r in ranges["inactiveRegions"]] == [span(5, 9), span(10, 11), span(15, 16)],
                      f"Comments/blank lines/skipped directives must be fully covered: {ranges['inactiveRegions']}")
                check([r["controllingDirective"] for r in ranges["inactiveRegions"]] == [1, 2, 5],
                      "Inactive region links are wrong")
            empty = open_trace(client, root / "Plain.slang", "int plain;")
            check(empty["directives"] == [] and empty["inactiveRegions"] == [], "Empty trace is wrong")
            check(trace(client, uri, 8) == updated, "Other documents contaminated the trace")
            client.notify("textDocument/didClose", {"textDocument": {"uri": uri}})
            check(client.request("slang/textDocument/preprocessorTrace", {
                "textDocument": {"uri": uri}}) is None, "Closed document must return null")
            client.request("shutdown", None)
            client.notify("exit", None)
            print("PASS: M4a capability, conditional tree, evaluated/active states, complete inactive "
                  "regions, LF/CRLF, UTF-16, multiline conditions, includes, #line, repeat requests, "
                  "document isolation, incremental edits, document versions and close")
    finally:
        client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    run(parser.parse_args().slangd)
