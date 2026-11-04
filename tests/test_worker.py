"""Bridge contract tests; no real model calls or credentials required."""
import contextlib
import io
import json
import os
import sys
from pathlib import Path
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import patch

import worker


class Block(NS):
    def model_dump(self, **kwargs):
        return vars(self)


class Stream:
    def __init__(self, response, text=()):
        self.response = response
        self.text_stream = iter(text)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def get_final_message(self):
        return self.response


def response(blocks, reason="end_turn"):
    return NS(content=blocks, stop_reason=reason, usage=NS(input_tokens=9, output_tokens=4))


class WorkerTest(unittest.TestCase):
    def core(self, workspace, streams, writer):
        def safe_path(value):
            resolved = (workspace / value).resolve()
            if not resolved.is_relative_to(workspace):
                raise ValueError("Path escapes workspace")
            return resolved
        return NS(TOOLS=[{"name": "write_file"}], TOOL_HANDLERS={"write_file": writer},
                  SKILLS=NS(descriptions=lambda: "none"), safe_path=safe_path,
                  microcompact=lambda messages: None, estimate_tokens=lambda messages: 1,
                  client=NS(messages=NS(stream=lambda **kw: next(streams))))

    def test_stream_and_tool_protocol(self):
        with tempfile.TemporaryDirectory() as folder:
            workspace = Path(folder).resolve()
            call = Block(type="tool_use", id="tool1", name="write_file",
                         input={"path": "hello.txt", "content": "你好"})
            streams = iter([Stream(response([call], "tool_use")),
                            Stream(response([Block(type="text", text="完成")]), ["完", "成"])])
            def write(path, content):
                (workspace / path).write_text(content, encoding="utf-8")
                return "Wrote file"
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                worker.execute({"prompt": "写一个文件"}, self.core(workspace, streams, write), workspace)
            events = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual("你好", (workspace / "hello.txt").read_text(encoding="utf-8"))
            self.assertEqual("完成", "".join(e["content"] for e in events if e["type"] == "text"))
            self.assertEqual("done", events[-1]["type"])
            self.assertEqual(2, sum(e["type"] == "usage" for e in events))
            self.assertEqual("write_file", json.loads(next(e["content"] for e in events if e["type"] == "tool_start"))["name"])
            starts = [json.loads(e["content"]) for e in events if e["type"] == "model_start"]
            ends = [json.loads(e["content"]) for e in events if e["type"] == "model_end"]
            self.assertEqual([1, 2], [e["round"] for e in starts])
            self.assertEqual([1, 2], [e["round"] for e in ends])
            tool_start = json.loads(next(e["content"] for e in events if e["type"] == "tool_start"))
            tool_result = json.loads(next(e["content"] for e in events if e["type"] == "tool_result"))
            self.assertEqual("tool1", tool_start["toolUseId"])
            self.assertEqual(tool_start["toolUseId"], tool_result["toolUseId"])
            self.assertEqual("completed", tool_result["status"])
            self.assertEqual(1, tool_result["round"])
            phases = [json.loads(e["content"]) for e in events if e["type"] == "phase"]
            completed = [p for p in phases if p["status"] == "completed"]
            self.assertEqual([1, 2], [p["round"] for p in completed if p["phase"] == "context"])
            decisions = [p for p in completed if p["phase"] == "decision"]
            self.assertEqual(["tools", "output"], [p["route"] for p in decisions])
            self.assertEqual([1, 0], [p["toolCalls"] for p in decisions])
            feedback = next(p for p in completed if p["phase"] == "feedback")
            self.assertEqual(1, feedback["toolCalls"])
            for stage in ("context", "decision", "feedback"):
                for phase in (p for p in completed if p["phase"] == stage):
                    self.assertEqual(["running", "completed"], [p["status"] for p in phases if p["phaseId"] == phase["phaseId"]])

    def test_path_escape_and_hidden_files_never_reach_tool(self):
        for path in ("../escape.txt", ".env", "nested/.secret"):
            with self.subTest(path=path), tempfile.TemporaryDirectory() as folder:
                workspace = Path(folder).resolve()
                call = Block(type="tool_use", id="tool1", name="write_file", input={"path": path, "content": "x"})
                streams = iter([Stream(response([call], "tool_use")), Stream(response([]))])
                def forbidden(**kwargs):
                    self.fail("Unsafe path reached tool")
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    worker.execute({"prompt": "test"}, self.core(workspace, streams, forbidden), workspace)
                events = [json.loads(line) for line in output.getvalue().splitlines()]
                result = next(e for e in events if e["type"] == "tool_result")
                self.assertTrue(json.loads(result["content"])["output"].startswith("Error:"))
                self.assertEqual("failed", json.loads(result["content"])["status"])

    def test_shell_requires_explicit_opt_in(self):
        with patch.dict(os.environ, {"AGENT_ALLOW_SHELL": "false"}):
            self.assertIn("disabled", worker.shell_command(Path.cwd(), "echo hello"))

    def test_benchmark_shell_handler_never_falls_back_to_host_shell(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"AGENT_ALLOW_SHELL": "true"}):
            workspace = Path(folder).resolve()
            call = Block(type="tool_use", id="isolated-shell", name="bash", input={"command": "echo isolated"})
            streams = iter([Stream(response([call], "tool_use")), Stream(response([]))])
            core = self.core(workspace, streams, lambda **kw: "unused")
            core.TOOLS = [{"name": "bash"}]
            commands = []
            def isolated(command):
                commands.append(command)
                return "isolated result"
            with patch.object(worker, "shell_command", side_effect=AssertionError("Host shell must not execute")), contextlib.redirect_stdout(io.StringIO()):
                worker.execute({"prompt": "test"}, core, workspace, shell_handler=isolated, system_override="test")
            self.assertEqual(["echo isolated"], commands)

    def test_shell_nonzero_exit_reports_tool_failure(self):
        with patch.dict(os.environ, {"AGENT_ALLOW_SHELL": "true"}):
            result = worker.shell_command(Path.cwd(), f'"{sys.executable}" -c "import sys; sys.exit(7)"')
            self.assertTrue(result.startswith("Error:"))
            self.assertIn("7", result)

    def test_failed_model_keeps_start_event_without_false_completion(self):
        with tempfile.TemporaryDirectory() as folder:
            workspace = Path(folder).resolve()
            core = self.core(workspace, iter([]), lambda **kw: "ok")
            def unavailable(**kwargs):
                raise RuntimeError("provider unavailable")
            core.client.messages.stream = unavailable
            output = io.StringIO()
            with contextlib.redirect_stdout(output), self.assertRaises(RuntimeError):
                worker.execute({"prompt": "test"}, core, workspace)
            events = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual("model_start", events[-1]["type"])
            self.assertNotIn("model_end", [e["type"] for e in events])
            self.assertNotIn("done", [e["type"] for e in events])

    def test_context_compaction_reports_one_matched_phase(self):
        core = NS(client=NS(messages=NS(create=lambda **kw: response([Block(type="text", text="summary")]))))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            compacted = worker.compact(core, [{"role": "user", "content": "context"}], "fixture", "compact-before-2")
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        phases = [json.loads(e["content"]) for e in events if e["type"] == "phase"]
        self.assertEqual(["running", "completed"], [phase["status"] for phase in phases])
        self.assertEqual(["compact-before-2"] * 2, [phase["phaseId"] for phase in phases])
        self.assertIn("summary", compacted[0]["content"])
        self.assertEqual(1, sum(e["type"] == "usage" for e in events))

    def test_step_limit_is_a_failure_not_fake_success(self):
        with tempfile.TemporaryDirectory() as folder:
            workspace = Path(folder).resolve()
            call = Block(type="tool_use", id="tool1", name="write_file", input={"path": "x", "content": "x"})
            streams = iter([Stream(response([call], "tool_use"))])
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
                worker.execute({"prompt": "test", "maxSteps": 1}, self.core(workspace, streams, lambda **kw: "ok"), workspace)


if __name__ == "__main__":
    unittest.main()
