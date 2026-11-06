"""Run workbench commands against a disposable snapshot in Docker."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import uuid

from coding_tools import TEST_IMAGE, schema


COMMAND_TOOLS = [
    schema('start_background', 'Start a command in an offline Linux container. Returns immediately. Writes are disposable; use file tools for edits.',
           {'command': {'type': 'string'}, 'timeout_seconds': {'type': 'integer', 'minimum': 5, 'maximum': 300}}, ['command']),
    schema('background_status', 'Check command progress, recent output and exit code. Optionally wait briefly for completion.',
           {'job_id': {'type': 'string'}, 'wait_seconds': {'type': 'integer', 'minimum': 0, 'maximum': 15}}, ['job_id']),
    schema('stop_background', 'Stop a background command owned by this task.', {'job_id': {'type': 'string'}}, ['job_id']),
]


class WorkspaceCommands:
    def __init__(self, coding, directory, notify):
        self.coding = coding
        self.directory = directory.resolve()
        self.notify = notify
        self.jobs = {}
        self.lock = threading.Lock()

    @staticmethod
    def docker(*args, timeout=15, check=True):
        result = subprocess.run(['docker', *args], capture_output=True, text=True, encoding='utf-8',
                                errors='replace', timeout=timeout)
        if check and result.returncode:
            raise RuntimeError((result.stderr or result.stdout).strip()[-1000:])
        return result

    def ensure_image(self):
        try:
            if self.docker('info', '--format', '{{.OSType}}').stdout.strip() != 'linux':
                raise RuntimeError('Docker Linux engine required')
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
            raise RuntimeError('命令需要 Docker Desktop 的 Linux 引擎') from exc
        if self.docker('image', 'inspect', TEST_IMAGE, check=False).returncode:
            infra = Path(__file__).resolve().parent / 'infra'
            self.docker('build', '-t', TEST_IMAGE, '-f', str(infra / 'coding-tests.Dockerfile'), str(infra), timeout=300)

    def remove_snapshot(self, snapshot):
        if snapshot.is_symlink() or getattr(snapshot, 'is_junction', lambda: False)():
            return
        if snapshot.is_dir() and snapshot.resolve().parent == self.directory:
            shutil.rmtree(snapshot)

    def start(self, command, timeout_seconds=120):
        if not isinstance(command, str) or not command.strip() or len(command) > 12000 or '\x00' in command:
            raise ValueError('命令内容无效或过长')
        if len(self.jobs) >= 3:
            raise ValueError('每个任务最多保留 3 个命令，请先查看并清理已有命令')
        seconds = max(5, min(int(timeout_seconds), 300))
        files = self.coding.snapshot()
        self.ensure_image()
        job_id = uuid.uuid4().hex
        name = f'minty-job-{self.coding.run_id}-{job_id}'
        snapshot = self.directory / f'command-{job_id}'
        snapshot.mkdir()
        try:
            for path, data in files.items():
                target = snapshot / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            self.docker('run', '-d', '--name', name, '--label', f'minty.run={self.coding.run_id}',
                        '--network', 'none', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                        '--read-only', '--memory', '1g', '--cpus', '1', '--pids-limit', '128',
                        '--log-opt', 'max-size=256k', '--log-opt', 'max-file=1',
                        '--tmpfs', '/tmp:rw,nosuid,nodev,size=64m',
                        '--tmpfs', '/workspace:rw,nosuid,nodev,mode=1777,size=128m',
                        '--mount', f'type=bind,source={snapshot},target=/snapshot,readonly',
                        '--env', 'HOME=/tmp', '--user', '1000:1000', '-w', '/workspace', TEST_IMAGE,
                        '/usr/bin/timeout', '-k', '3s', f'{seconds}s', '/bin/sh', '-c',
                        'cp -R /snapshot/. /workspace && exec /bin/sh -c "$1"', 'minty', command)
        except Exception:
            self.docker('rm', '-f', name, check=False)
            self.remove_snapshot(snapshot)
            raise
        self.jobs[job_id] = (name, snapshot)
        self.notify('background', jobId=job_id, status='running', command=command[:300])
        threading.Thread(target=self.wait_for_exit, args=(job_id, name, seconds), daemon=True).start()
        return {'ok': True, 'jobId': job_id, 'status': 'running', 'timeoutSeconds': seconds}

    def wait_for_exit(self, job_id, name, seconds):
        try:
            result = self.docker('wait', name, timeout=seconds + 30)
            code = int(result.stdout.strip())
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired):
            return
        with self.lock:
            if job_id in self.jobs:
                self.notify('background', jobId=job_id, status='completed' if code == 0 else 'failed', exitCode=code)

    def status(self, job_id, wait_seconds=0):
        if job_id not in self.jobs:
            raise ValueError('此命令不存在于当前执行实例')
        name, _ = self.jobs[job_id]
        deadline = time.monotonic() + max(0, min(int(wait_seconds), 15))
        while True:
            state = json.loads(self.docker('inspect', '--format', '{{json .State}}', name).stdout)
            if not state['Running'] or time.monotonic() >= deadline:
                break
            time.sleep(0.25)
        status = 'running' if state['Running'] else 'completed' if state['ExitCode'] == 0 else 'failed'
        logs = self.docker('logs', '--tail', '80', name, check=False)
        result = {'ok': status != 'failed', 'jobId': job_id, 'status': status,
                  'exitCode': None if state['Running'] else state['ExitCode'],
                  'output': (logs.stdout + logs.stderr)[-20000:]}
        self.notify('background', jobId=job_id, status=status, exitCode=result['exitCode'])
        return result

    def stop(self, job_id):
        if job_id not in self.jobs:
            raise ValueError('此命令不存在于当前执行实例')
        name, snapshot = self.jobs[job_id]
        with self.lock:
            inspection = self.docker('inspect', '--format', '{{json .State}}', name)
            state = json.loads(inspection.stdout)
            self.docker('rm', '-f', name)
            del self.jobs[job_id]
            if state['Running']:
                self.notify('background', jobId=job_id, status='stopped')
            else:
                code = state['ExitCode']
                self.notify('background', jobId=job_id, status='completed' if code == 0 else 'failed', exitCode=code)
        self.remove_snapshot(snapshot)
        return {'ok': True, 'jobId': job_id, 'status': 'stopped'}

    def foreground(self, command):
        job_id = self.start(command, 30)['jobId']
        try:
            while True:
                result = self.status(job_id, 15)
                if result['status'] != 'running':
                    return result
        finally:
            self.stop(job_id)

    def clean_snapshots(self):
        # The backend checks and removes prior containers by run label before resuming.
        for path in self.directory.iterdir():
            if re.fullmatch(r'command-[0-9a-f]{32}', path.name):
                self.remove_snapshot(path)

    def close(self):
        for job_id in list(self.jobs):
            try:
                self.stop(job_id)
            except (OSError, RuntimeError, subprocess.TimeoutExpired):
                continue  # The backend will also clean up by exact run label.
