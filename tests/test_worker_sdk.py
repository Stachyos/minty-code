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

    def exercise_sdk_stream(self, brotli_encoded=False):
        class Handler(BaseHTTPRequestHandler):
            calls = 0
            accepted_encodings = []

            def log_message(self, *args):
                pass

            def do_POST(self):
                json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                Handler.calls += 1
                Handler.accepted_encodings.append(self.headers.get("Accept-Encoding", ""))
                tool = Handler.calls == 1
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
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
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
                env = dict(os.environ, ANTHROPIC_API_KEY="fixture-only-not-a-real-key",
                           ANTHROPIC_BASE_URL=f"http://127.0.0.1:{server.server_port}", AGENT_ALLOW_SHELL="false")
                process = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "worker.py"), "--workspace", folder],
                    input=json.dumps({"prompt": "创建文件", "model": "fixture"}), text=True, encoding="utf-8",
                    capture_output=True, env=env, timeout=30)
                self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                events = [json.loads(line) for line in process.stdout.splitlines()]
                self.assertEqual("done", events[-1]["type"])
                self.assertEqual("你好，薄荷", (Path(folder) / "hello.txt").read_text(encoding="utf-8"))
                self.assertEqual("文件已创建。", "".join(e["content"] for e in events if e["type"] == "text"))
                self.assertEqual(24, sum(e.get("inputTokens", 0) for e in events))
                if brotli_encoded:
                    self.assertEqual(2, Handler.calls)
                    self.assertTrue(all("br" in value.split(", ") for value in Handler.accepted_encodings))
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
