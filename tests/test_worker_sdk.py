"""Exercise the real SDK/worker protocol against a local deterministic API fixture."""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


@unittest.skipUnless(importlib.util.find_spec("anthropic"), "Install requirements.txt to exercise SDK integration")
class SdkBridgeTest(unittest.TestCase):
    def test_real_sdk_stream_writes_workspace_file(self):
        self.exercise_sdk_stream()

    def test_brotli_response_stream_writes_workspace_file(self):
        self.exercise_sdk_stream(brotli_encoded=True)

    def test_plain_chat_uses_no_tools_workspace_or_project_context(self):
        self.exercise_sdk_stream(plain_chat=True)

    def test_linked_project_writes_to_source_and_keeps_runtime_state_separate(self):
        self.exercise_sdk_stream(linked_project=True)

    def test_failed_fallback_keeps_project_run_recoverable_with_http_status(self):
        self.exercise_sdk_stream(linked_project=True, truncated=True)

    def test_incomplete_stream_switches_to_complete_response_and_creates_directory_once(self):
        self.exercise_sdk_stream(linked_project=True, truncated=True, fallback_success=True)

    def test_transient_http_500_retries_before_dispatching_the_tool(self):
        self.exercise_sdk_stream(linked_project=True, server_failures=2)

    def exercise_sdk_stream(self, brotli_encoded=False, plain_chat=False, linked_project=False,
                            truncated=False, fallback_success=False, server_failures=0):
        class Handler(BaseHTTPRequestHandler):
            calls = 0
            accepted_encodings = []
            requests = []

            def log_message(self, *args):
                pass

            def do_POST(self):
                Handler.requests.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                Handler.calls += 1
                Handler.accepted_encodings.append(self.headers.get("Accept-Encoding", ""))
                if Handler.calls <= server_failures:
                    self.send_error(500, "fixture transient server failure")
                    return
                if truncated and not Handler.requests[-1].get("stream"):
                    if not fallback_success:
                        self.send_error(429, "fixture rate limit")
                        return
                    tool_result = Handler.calls == 2
                    body = json.dumps({"id": "msg_test", "type": "message", "role": "assistant", "model": "fixture",
                        "content": [{"type": "tool_use", "id": "t1", "name": "create_directory",
                                     "input": {"path": "喵喵喵"}}] if tool_result
                                   else [{"type": "text", "text": "文件夹已创建。"}],
                        "stop_reason": "tool_use" if tool_result else "end_turn", "stop_sequence": None,
                        "usage": {"input_tokens": 12, "output_tokens": 8}}).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                tool = Handler.calls == server_failures + 1 and not plain_chat
                chunks = []
                def event(kind, **data):
                    payload = json.dumps({"type": kind, **data}, ensure_ascii=False)
                    chunks.append(f"event: {kind}\ndata: {payload}\n\n".encode("utf-8"))
                event("message_start", message={"id": "msg_test", "type": "message", "role": "assistant",
                    "model": "fixture", "content": [], "stop_reason": None, "stop_sequence": None,
                    "usage": {"input_tokens": 12, "output_tokens": 0}})
                if tool:
                    event("content_block_start", index=0, content_block={"type": "tool_use", "id": "t1", "name": "write_file", "input": {}})
                    event("content_block_delta", index=0, delta={"type": "input_json_delta", "partial_json": json.dumps({"path": "hello.txt", "content": "你好，薄荷"})})
                else:
                    event("content_block_start", index=0, content_block={"type": "text", "text": ""})
                    event("content_block_delta", index=0, delta={"type": "text_delta", "text": "文件已创建。"})
                event("content_block_stop", index=0)
                event("message_delta", delta={"stop_reason": "tool_use" if tool else "end_turn", "stop_sequence": None}, usage={"output_tokens": 8})
                event("message_stop")
                body = b"".join(chunks)
                if brotli_encoded:
                    import importlib
                    import platform
                    codec = importlib.import_module("brotli" if platform.python_implementation() == "CPython" else "brotlicffi")
                    body = codec.compress(body)
                if truncated:
                    self.protocol_version = "HTTP/1.1"
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                if truncated:
                    self.send_header("Transfer-Encoding", "chunked")
                    self.end_headers()
                    self.wfile.write(f"{len(body):x}\r\n".encode() + body[:len(body) // 2])
                    self.close_connection = True
                    return
                if brotli_encoded:
                    self.send_header("Content-Encoding", "br")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                for offset in range(0, len(body), 23):
                    self.wfile.write(body[offset:offset + 23])
                    self.wfile.flush()

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder).resolve()
                workspace = root
                env = dict(os.environ, ANTHROPIC_API_KEY="fixture-only-not-a-real-key", PYTHONIOENCODING="utf-8",
                           ANTHROPIC_BASE_URL=f"http://127.0.0.1:{server.server_port}", AGENT_ALLOW_SHELL="false")
                command = [sys.executable, str(Path(__file__).resolve().parents[1] / "worker.py")]
                payload = {"prompt": "创建喵喵喵空目录" if fallback_success else "创建文件", "model": "fixture"}
                if plain_chat:
                    payload = {"prompt": "你好", "model": "fixture", "mode": "chat", "projectResources": ["scope sentinel"]}
                else:
                    if linked_project:
                        workspace = root / "local source"
                        workspace.mkdir()
                        (workspace / "logo.png").write_bytes(b"\0" * 1_100_000)
                        (workspace / "runtime").mkdir()
                        (workspace / "runtime" / "database").write_bytes(b"\0" * 1_100_000)
                        metadata = root / "metadata"
                        metadata.mkdir()
                        payload.update(mode="project", metadataDirectory=str(metadata),
                                       excludedDirectory=str(workspace / "runtime"),
                                       checkpointDirectory=str(root / "checkpoint"))
                    command += ["--workspace", str(workspace)]
                process = subprocess.run(command, cwd=workspace, input=json.dumps(payload), text=True, encoding="utf-8",
                    capture_output=True, env=env, timeout=30)
                if truncated and not fallback_success:
                    self.assertEqual(1, process.returncode, process.stdout + process.stderr)
                    events = [json.loads(line) for line in process.stdout.splitlines()]
                    self.assertEqual("interrupted", events[-1]["type"], events[-1])
                    self.assertIn("HTTP 429", events[-1]["content"])
                    self.assertEqual(2, Handler.calls)
                    self.assertTrue((root / "checkpoint" / "state.json").is_file())
                    self.assertFalse((workspace / "hello.txt").exists())
                    self.assertFalse(any(event["type"] == "tool_start" for event in events))
                    return
                self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                events = [json.loads(line) for line in process.stdout.splitlines()]
                self.assertEqual("done", events[-1]["type"])
                if server_failures:
                    self.assertEqual(server_failures + 2, Handler.calls)
                    self.assertEqual(1, sum(event["type"] == "tool_start" for event in events))
                    failures = [json.loads(event["content"]) for event in events if event["type"] == "model_end"]
                    self.assertEqual(["same"] * server_failures, [event["retryMode"] for event in failures[:server_failures]])
                if fallback_success:
                    self.assertEqual([True, False, False], [bool(item.get("stream")) for item in Handler.requests])
                    self.assertEqual(1, sum(event["type"] == "tool_start" for event in events))
                    failures = [json.loads(event["content"]) for event in events if event["type"] == "model_end"]
                    self.assertTrue(failures[0]["retrying"])
                    self.assertEqual([], list((workspace / "喵喵喵").iterdir()))
                    self.assertEqual("文件夹已创建。", "".join(event["content"] for event in events if event["type"] == "text"))
                    return
                if plain_chat:
                    self.assertEqual(1, Handler.calls)
                    self.assertNotIn("tools", Handler.requests[0])
                    self.assertNotIn("scope sentinel", json.dumps(Handler.requests[0]))
                    self.assertEqual([], list(Path(folder).iterdir()))
                    return
                self.assertEqual("你好，薄荷", (workspace / "hello.txt").read_text(encoding="utf-8"))
                if linked_project:
                    self.assertTrue((metadata / ".tasks").is_dir())
                    self.assertFalse((workspace / ".tasks").exists())
                    self.assertFalse((workspace / ".team").exists())
                    checkpoints = [json.loads(e["content"]) for e in events if e["type"] == "checkpoint"]
                    self.assertEqual(24, checkpoints[-1]["inputTokens"])
                else:
                    self.assertEqual(24, sum(e.get("inputTokens", 0) for e in events))
                self.assertEqual("文件已创建。", "".join(e["content"] for e in events if e["type"] == "text"))
                if brotli_encoded:
                    self.assertEqual(2, Handler.calls)
                    self.assertTrue(all("br" in value.split(", ") for value in Handler.accepted_encodings))
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
