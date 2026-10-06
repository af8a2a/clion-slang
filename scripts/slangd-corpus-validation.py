"""Read-only LSP corpus validation; emit raw diagnostics and per-file results."""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
from pathlib import Path
import queue
import time
from urllib.parse import unquote

spec = importlib.util.spec_from_file_location("smoke", Path(__file__).with_name("slangd-dynamic-resource-heap-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def validate(executable, root, path, configuration):
    started = time.monotonic()
    raw = path.read_bytes()
    source = raw.decode("utf-8-sig")
    result = {"file": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(raw).hexdigest()}
    client = smoke.baseline.Client(executable, configuration)
    client.messages = smoke.Messages()
    try:
        uri = path.as_uri()
        client.request("initialize", {"workspaceFolders": [{"uri": root.as_uri(), "name": root.name}], "capabilities": {}})
        client.notify("initialized", {})
        client.request("textDocument/documentSymbol", {"textDocument": {"uri": uri}})
        # Establish a known diagnostic, then require an explicit replacement/clear.
        # Only the server's unsaved buffer changes; source files are never written.
        seed = "float corpusValidationSeed() { return corpusUndefinedSeed; }\n"
        client.notify("textDocument/didOpen", {"textDocument": {"uri": uri, "languageId": path.suffix[1:], "version": 1, "text": seed}})
        client.request("textDocument/semanticTokens/full", {"textDocument": {"uri": uri}})
        smoke.baseline.check(smoke.diagnostics(client, uri), "Seed diagnostic missing")
        client.notify("textDocument/didChange", {"textDocument": {"uri": uri, "version": 2}, "contentChanges": [{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 1, "character": 0}}, "text": source}]})
        tokens = client.request("textDocument/semanticTokens/full", {"textDocument": {"uri": uri}})
        deadline = time.monotonic() + 20
        publications = []
        while True:
            publication = client.messages.diagnostics.get(timeout=max(0, deadline - time.monotonic()))
            publications.append(publication)
            if unquote(publication["uri"]).casefold() == unquote(uri).casefold():
                break
        # A clearing notification can precede errors in imported files. A reply
        # barrier ensures that the complete publication batch has been received.
        client.request("textDocument/documentSymbol", {"textDocument": {"uri": uri}})
        while True:
            try:
                publications.append(client.messages.diagnostics.get_nowait())
            except queue.Empty:
                break
        latest = {unquote(p["uri"]).casefold(): p for p in publications}
        result.update(status="completed", publications=list(latest.values()), token_count=len((tokens or {}).get("data", [])) // 5)
        result["errors"] = sum(d.get("severity") == 1 for p in latest.values() for d in p["diagnostics"])
        result["warnings"] = sum(d.get("severity") == 2 for p in latest.values() for d in p["diagnostics"])
    except Exception as error:
        result.update(status="failed", exception=f"{type(error).__name__}: {error}", exit_code=client.process.poll())
    finally:
        client.close()
        result["stderr"] = b"".join(client.stderr).decode("utf-8", errors="replace")
        result["seconds"] = round(time.monotonic() - started, 3)
        result["source_unchanged"] = path.read_bytes() == raw
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangd", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--match", default="")
    parser.add_argument("--configuration", type=Path)
    args = parser.parse_args()
    if args.workers < 1:
        parser.error("--workers must be positive")
    root = args.root.resolve()
    files = sorted(p for p in root.rglob("*") if p.suffix.lower() in (".slang", ".hlsl") and args.match in p.as_posix())
    if not files:
        parser.error("No matching .slang or .hlsl files")
    configuration = {"slang.additionalSearchPaths": [str(root), str(root / "Modules"), str(root / "Interop")], "slang.searchInAllWorkspaceDirectories": False}
    if args.configuration:
        configuration.update(json.loads(args.configuration.read_text(encoding="utf-8")))
    args.output.mkdir(parents=True, exist_ok=True)
    metadata = {"executable": str(Path(args.slangd).resolve()), "executable_sha256": hashlib.sha256(Path(args.slangd).read_bytes()).hexdigest(), "root": str(root), "configuration": configuration, "files": len(files)}
    (args.output / "configuration.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    started = time.monotonic()
    results = []
    with (args.output / "results.jsonl").open("w", encoding="utf-8") as output, concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(validate, args.slangd, root, p, configuration) for p in files]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            output.write(json.dumps(result, ensure_ascii=False) + "\n")
            output.flush()
            print(f"{len(results)}/{len(files)} {result['file']}: {result['status']} errors={result.get('errors', '?')} {result['seconds']}s", flush=True)
    summary = {**metadata, "seconds": round(time.monotonic() - started, 3), "completed": sum(r["status"] == "completed" for r in results), "clean": sum(r.get("errors") == 0 for r in results), "with_errors": sum(r.get("errors", 0) > 0 for r in results), "failed": [r for r in results if r["status"] != "completed"], "source_unchanged": all(r["source_unchanged"] for r in results)}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    if summary["failed"] or not summary["source_unchanged"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
