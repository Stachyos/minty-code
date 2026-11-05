"""Workspace coding tools, guarded multi-file patches and evidence-based verification."""
from __future__ import annotations

import difflib
import fnmatch
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import subprocess
import tempfile
import time
import uuid

IGNORED = {'node_modules', '__pycache__', 'target', 'dist', 'venv', 'coverage'}
MAX_FILE = 1_048_576
MAX_TOTAL = 25_000_000
TEST_IMAGE = 'minty-coding-tests:1'


def schema(name, description, properties=None, required=None):
    return {'name': name, 'description': description,
            'input_schema': {'type': 'object', 'properties': properties or {}, 'required': required or [], 'additionalProperties': False}}


TOOL_SCHEMAS = [
    schema('list_files', 'List visible workspace files. Dependencies, hidden/config files and build outputs are excluded.',
           {'pattern': {'type': 'string'}, 'offset': {'type': 'integer', 'minimum': 0}, 'limit': {'type': 'integer', 'minimum': 1, 'maximum': 200}}),
    schema('search_text', 'Find literal text in workspace files; returns paths, line numbers and neighboring lines.',
           {'query': {'type': 'string'}, 'pattern': {'type': 'string'}, 'case_sensitive': {'type': 'boolean'}, 'limit': {'type': 'integer', 'minimum': 1, 'maximum': 100}}, ['query']),
    schema('read_file', 'Read UTF-8 code by line range. Returns sha256; use it as expected_sha256 in apply_patch.',
           {'path': {'type': 'string'}, 'start_line': {'type': 'integer', 'minimum': 1}, 'end_line': {'type': 'integer', 'minimum': 1}, 'limit': {'type': 'integer', 'minimum': 1}}, ['path']),
    schema('write_file', 'Create or replace a visible workspace file. Prefer apply_patch with expected_sha256 for existing files.',
           {'path': {'type': 'string'}, 'content': {'type': 'string'}}, ['path', 'content']),
    schema('edit_file', 'Replace one unique exact text match. Rejects ambiguous matches.',
           {'path': {'type': 'string'}, 'old_text': {'type': 'string'}, 'new_text': {'type': 'string'}}, ['path', 'old_text', 'new_text']),
    schema('apply_patch', 'Apply guarded changes to multiple files. Read existing files first and supply their sha256. old_text must match exactly once. For new files old_text=null; to delete a file new_text=null and old_text must equal the whole file. All changes validate before writing.',
           {'changes': {'type': 'array', 'minItems': 1, 'maxItems': 30, 'items': {'type': 'object', 'properties': {
               'path': {'type': 'string'}, 'old_text': {'type': ['string', 'null']}, 'new_text': {'type': ['string', 'null']},
               'expected_sha256': {'type': 'string'}}, 'required': ['path', 'old_text', 'new_text'], 'additionalProperties': False}}}, ['changes']),
    schema('get_diff', 'Get all changes since this run began, including new/deleted files and unified diffs.'),
    schema('run_tests', 'Run tests in a disposable Linux Docker container with no network, API keys or host access outside a copy of the workspace. Runners: unittest, pytest, node, npm (requires package-lock), maven (Java 17). First use prepares images. npm/Maven dependency preparation gets only manifests with network access; test execution has no network. Reports exit code, counts, duration and source fingerprint; edits after a test invalidate it.',
           {'runner': {'type': 'string', 'enum': ['unittest', 'pytest', 'node', 'npm', 'maven']}, 'path': {'type': 'string'},
            'pattern': {'type': 'string'}, 'timeout_seconds': {'type': 'integer', 'minimum': 5, 'maximum': 180}}, ['runner']),
]


def visible(path):
    return not any(part.startswith('.') or part in IGNORED for part in Path(path).parts) and not str(path).endswith(('.pyc', '.pem', '.key', '.p12', '.pfx'))


def digest(data):
    return hashlib.sha256(data).hexdigest()


class LifecycleHooks:
    """Explicit extension points; hook errors stop the affected operation."""
    NAMES = {'input', 'before_tool', 'after_tool', 'stop'}

    def __init__(self):
        self.callbacks = {name: [] for name in self.NAMES}

    def on(self, name, callback):
        if name not in self.NAMES:
            raise ValueError('Unknown hook')
        self.callbacks[name].append(callback)

    def fire(self, name, **context):
        for callback in self.callbacks[name]:
            callback(context)


class CodingTools:
    def __init__(self, workspace, run_id=None, notify=None, capture_baseline=True):
        self.root = Path(workspace).resolve()
        self.run_id = run_id or str(uuid.uuid4())
        if not re.fullmatch(r'[a-zA-Z0-9-]{1,80}', self.run_id):
            raise ValueError('Invalid run id')
        self.notify = notify or (lambda *args, **kwargs: None)
        self.baseline = self.snapshot() if capture_baseline else {}
        self.last_test = None
        self.last_test_files = None
        self.test_history = []

    def path(self, value):
        if not isinstance(value, str) or not value.strip() or '\x00' in value:
            raise ValueError('Invalid path')
        relative = Path(value.replace('\\', '/'))
        if relative.is_absolute() or PureWindowsPath(value).drive or '..' in relative.parts or not visible(relative):
            raise ValueError('Only visible relative workspace paths are allowed')
        candidate = self.root / relative
        cursor = self.root
        for part in relative.parts:
            cursor /= part
            if cursor.is_symlink() or getattr(cursor, 'is_junction', lambda: False)():
                raise ValueError('Symbolic links and junctions are not allowed')
        result = candidate.resolve()
        if not result.is_relative_to(self.root) or result == self.root:
            raise ValueError('Path escapes workspace')
        return result

    def snapshot(self, strict=True):
        self.scan_truncated = False
        result = {}; size = 0
        for directory, dirs, names in os.walk(self.root, followlinks=False):
            dirs[:] = sorted(name for name in dirs if visible(name) and not (Path(directory) / name).is_symlink()
                             and not getattr(Path(directory) / name, 'is_junction', lambda: False)())
            for name in sorted(names):
                relative = (Path(directory) / name).relative_to(self.root).as_posix()
                if not visible(relative):
                    continue
                path = self.path(relative)
                if not path.is_file():
                    continue
                if path.stat().st_size > MAX_FILE:
                    if strict: raise ValueError(f'File exceeds 1 MB workspace tool limit: {relative}')
                    self.scan_truncated = True
                    continue
                data = path.read_bytes(); size += len(data)
                if size > MAX_TOTAL or len(result) >= 2000:
                    if strict: raise ValueError('Workspace exceeds the 25 MB / 2000 file tool limit')
                    self.scan_truncated = True
                    return result
                result[relative] = data
        return result

    @staticmethod
    def fingerprint(files):
        return digest(json.dumps([(path, digest(data)) for path, data in sorted(files.items())]).encode())

    def list_files(self, pattern='*', offset=0, limit=100):
        files = self.snapshot(strict=False)
        matches = [path for path in files if fnmatch.fnmatchcase(path, pattern)]
        offset = max(0, int(offset)); limit = max(1, min(int(limit), 200))
        return {'ok': True, 'total': len(matches), 'files': [{'path': path, 'bytes': len(files[path])} for path in matches[offset:offset+limit]],
                'truncated': self.scan_truncated or offset + limit < len(matches)}

    def search_text(self, query, pattern='*', case_sensitive=False, limit=50):
        if not query or len(query) > 2000:
            raise ValueError('Search query must have 1–2000 characters')
        hits = []; limit = max(1, min(int(limit), 100)); needle = query if case_sensitive else query.casefold()
        for path, data in self.snapshot(strict=False).items():
            if not fnmatch.fnmatchcase(path, pattern) or b'\0' in data:
                continue
            try: lines = data.decode('utf-8').splitlines()
            except UnicodeDecodeError: continue
            for index, line in enumerate(lines):
                if needle in (line if case_sensitive else line.casefold()):
                    hits.append({'path': path, 'line': index + 1, 'text': line[:1500],
                                 'context': [{'line': i + 1, 'text': lines[i][:1500]} for i in range(max(0, index-1), min(len(lines), index+2))]})
                    if len(hits) >= limit:
                        return {'ok': True, 'matches': hits, 'truncated': True}
        return {'ok': True, 'matches': hits, 'truncated': self.scan_truncated}

    def read_file(self, path, start_line=1, end_line=None, limit=None):
        data = self.path(path).read_bytes()
        if len(data) > MAX_FILE or b'\0' in data:
            raise ValueError('Only UTF-8 text files up to 1 MB can be read')
        text = data.decode('utf-8'); lines = text.splitlines()
        start = max(1, int(start_line)); end = min(len(lines), int(end_line) if end_line else start + min(int(limit or 300), 1000) - 1)
        content = '\n'.join(f'{i+1}: {lines[i]}' for i in range(start-1, max(start-1, end)))
        return {'ok': True, 'path': path, 'sha256': digest(data), 'startLine': start, 'endLine': end,
                'totalLines': len(lines), 'content': content[:30000], 'truncated': end < len(lines) or len(content) > 30000}

    @staticmethod
    def atomic_write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        handle, temporary = tempfile.mkstemp(prefix='.minty-write-', dir=path.parent)
        try:
            with os.fdopen(handle, 'wb') as output:
                output.write(data)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)

    def apply_patch(self, changes):
        if not isinstance(changes, list) or not 1 <= len(changes) <= 30:
            raise ValueError('A patch must contain 1–30 file changes')
        prepared = []; seen = set()
        for change in changes:
            path = self.path(change['path'])
            if path in seen: raise ValueError('Combine changes to the same file into one entry')
            seen.add(path)
            before = path.read_bytes() if path.exists() else None
            old = change['old_text']; new = change['new_text']
            if old is None:
                if before is not None or new is None: raise ValueError('New file already exists or content is missing')
                after = new.encode('utf-8')
            else:
                if before is None: raise ValueError('File does not exist')
                if not change.get('expected_sha256') or digest(before) != change['expected_sha256']:
                    raise ValueError('File changed or expected_sha256 missing; read the file again before patching')
                text = before.decode('utf-8')
                if new is None:
                    if text != old: raise ValueError('Deletion requires the full original content')
                    after = None
                else:
                    if not old or text.count(old) != 1: raise ValueError('old_text must match exactly once')
                    after = text.replace(old, new, 1).encode('utf-8')
            if after is not None and len(after) > MAX_FILE: raise ValueError('Patched file exceeds 1 MB')
            prepared.append((path, before, after))
        applied = []
        try:
            for path, before, after in prepared:
                # Recheck every file immediately before writing; never silently overwrite a detected conflict.
                if self.path(path.relative_to(self.root).as_posix()) != path or (path.read_bytes() if path.exists() else None) != before:
                    raise ValueError('File changed while applying patch')
                if after is None: path.unlink()
                else: self.atomic_write(path, after)
                applied.append((path, before, after))
        except Exception:
            for path, before, after in reversed(applied):
                if (path.read_bytes() if path.exists() else None) == after:
                    if before is None: path.unlink(missing_ok=True)
                    else: self.atomic_write(path, before)
            raise
        return {'ok': True, 'changedFiles': [path.relative_to(self.root).as_posix() for path, _, _ in prepared]}

    def write_file(self, path, content):
        target = self.path(path); before = target.read_bytes() if target.exists() else None
        if before is not None and before.decode('utf-8') == content:
            return {'ok': True, 'changedFiles': []}
        if before is None:
            return self.apply_patch([{'path': path, 'old_text': None, 'new_text': content}])
        # Creation/replacement compatibility still checks for concurrent changes.
        if not before:
            if len(content.encode('utf-8')) > MAX_FILE: raise ValueError('File exceeds 1 MB')
            self.atomic_write(target, content.encode('utf-8'))
            return {'ok': True, 'changedFiles': [path]}
        return self.apply_patch([{'path': path, 'old_text': before.decode('utf-8'), 'new_text': content, 'expected_sha256': digest(before)}])

    def edit_file(self, path, old_text, new_text):
        data = self.path(path).read_bytes()
        return self.apply_patch([{'path': path, 'old_text': old_text, 'new_text': new_text, 'expected_sha256': digest(data)}])

    def get_diff(self):
        current = self.snapshot(); changes = []
        for path in sorted(set(self.baseline) | set(current)):
            before, after = self.baseline.get(path), current.get(path)
            if before == after: continue
            try:
                lines = difflib.unified_diff((before or b'').decode('utf-8').splitlines(True), (after or b'').decode('utf-8').splitlines(True),
                                            fromfile=f'a/{path}' if before is not None else '/dev/null', tofile=f'b/{path}' if after is not None else '/dev/null')
                patch = ''.join(line if line.endswith('\n') else line + '\n\\ No newline at end of file\n' for line in lines)
            except UnicodeDecodeError: patch = '[Binary file changed]'
            changes.append({'path': path, 'kind': 'created' if before is None else 'deleted' if after is None else 'modified',
                            'beforeSha256': digest(before) if before is not None else None, 'afterSha256': digest(after) if after is not None else None,
                            'patch': patch[:20000], 'truncated': len(patch) > 20000})
        return {'ok': True, 'files': changes[:100], 'totalFiles': len(changes), 'truncated': len(changes) > 100,
                'fingerprint': self.fingerprint(current)}

    def run_tests(self, runner, path=None, pattern=None, timeout_seconds=60):
        if runner not in {'unittest', 'pytest', 'node', 'npm', 'maven'}: raise ValueError('Unsupported test runner')
        timeout_seconds = max(5, min(int(timeout_seconds), 180))
        path = path or ('tests' if runner in {'unittest','pytest'} and (self.root / 'tests').is_dir() else '.')
        if runner == 'maven' and path.endswith('pom.xml'):
            path = str(Path(path).parent).replace('\\','/')
        if path != '.': self.path(path)
        if pattern and (len(pattern) > 100 or '/' in pattern or '\\' in pattern or pattern.startswith('-')):
            raise ValueError('Invalid test filename pattern')
        files = self.snapshot(); fingerprint = self.fingerprint(files); started = time.monotonic(); execution_copy_changed = False
        result = {'runner': runner, 'path': path, 'pattern': pattern, 'fingerprint': fingerprint,
                  'status': 'unavailable', 'ok': False, 'counts': {}, 'exitCode': None}
        try:
            state = subprocess.run(['docker', 'info', '--format', '{{.OSType}}'], capture_output=True, text=True, timeout=12)
            if state.returncode or state.stdout.strip() != 'linux':
                raise RuntimeError('请启动 Docker Desktop 的 Linux 引擎；测试不会改为在宿主机运行')
            image = subprocess.run(['docker', 'image', 'inspect', TEST_IMAGE], capture_output=True, timeout=15)
            if image.returncode:
                self.notify('phase', phase='verification', phaseId='test-environment', status='running', label='准备测试容器')
                build = subprocess.run(['docker', 'build', '-f', str(Path(__file__).parent / 'infra/coding-tests.Dockerfile'),
                                        '-t', TEST_IMAGE, str(Path(__file__).parent / 'infra')], capture_output=True, text=True, errors='replace', timeout=600)
                if build.returncode: raise RuntimeError('测试镜像准备失败：' + (build.stdout + build.stderr)[-2500:])
                self.notify('phase', phase='verification', phaseId='test-environment', status='completed', label='测试容器已准备')
            name = f'minty-test-{self.run_id}-{uuid.uuid4().hex[:8]}'
            command = (['python', '-m', 'unittest', 'discover', '-s', path, '-p', pattern or 'test*.py', '-v'] if runner == 'unittest'
                       else ['python', '-m', 'pytest', path, '-q'] if runner == 'pytest'
                       else ['node', '--test', '--', *sorted(p for p in files if (path == '.' or p == path or p.startswith(path.rstrip('/') + '/')) and fnmatch.fnmatchcase(Path(p).name, pattern or '*.test.js'))])
            if runner == 'node' and len(command) == 3: raise ValueError('No matching Node test files')
            test_image = TEST_IMAGE
            if runner in {'npm','maven'}:
                prefix = '' if path == '.' else path.rstrip('/') + '/'
                if not re.fullmatch(r'[A-Za-z0-9_./-]*', prefix):
                    raise ValueError('Dependency preparation requires a simple relative directory name')
                manifests = {name:data for name,data in files.items() if name.endswith('pom.xml')} if runner == 'maven' else {
                    name:files[name] for name in [prefix+'package.json',prefix+'package-lock.json'] if name in files}
                required = prefix + ('pom.xml' if runner == 'maven' else 'package-lock.json')
                if required not in manifests: raise ValueError(f'Missing dependency manifest: {required}')
                image_id = subprocess.run(['docker','image','inspect','--format','{{.Id}}',TEST_IMAGE],capture_output=True,text=True,timeout=15).stdout
                key = digest((image_id+self.fingerprint(manifests)+runner+prefix+'dependency-policy-v2').encode())[:24]
                test_image = f'minty-coding-deps:{key}'
                if subprocess.run(['docker','image','inspect',test_image],capture_output=True,timeout=15).returncode:
                    with tempfile.TemporaryDirectory(prefix='minty-dependencies-') as preparation:
                        for relative,data in manifests.items():
                            target=Path(preparation)/relative; target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(data)
                        # No source files, settings.xml, .npmrc, .env or credentials enter this build context.
                        setup = (f'WORKDIR /opt/project-deps/{prefix}\nRUN npm ci --ignore-scripts --no-audit --no-fund\n' if runner == 'npm' else
                                 f'RUN mvn -B -f /opt/project-deps/{prefix}pom.xml -Dmaven.repo.local=/opt/maven-repo -DskipTests dependency:go-offline\n'
                                 f'RUN mkdir -p /opt/project-deps/{prefix}src/test/java && '
                                 f"printf '%s\\n' 'public class MintyDependencyProbeTest {{}}' > /opt/project-deps/{prefix}src/test/java/MintyDependencyProbeTest.java && "
                                 f'mvn -B -f /opt/project-deps/{prefix}pom.xml -Dmaven.repo.local=/opt/maven-repo '
                                 '-Dmaven.compiler.source=17 -Dmaven.compiler.target=17 -DfailIfNoTests=false test\n')
                        (Path(preparation)/'Dockerfile').write_text(f'FROM {TEST_IMAGE}\nWORKDIR /opt/project-deps\nCOPY . .\n'+setup,encoding='utf-8')
                        self.notify('phase',phase='verification',phaseId='test-dependencies',status='running',label='准备测试依赖（仅传入清单）')
                        build=subprocess.run(['docker','build','-t',test_image,preparation],capture_output=True,text=True,errors='replace',timeout=600)
                        if build.returncode: raise RuntimeError('测试依赖准备失败：'+(build.stdout+build.stderr)[-2500:])
                        self.notify('phase',phase='verification',phaseId='test-dependencies',status='completed',label='测试依赖已准备')
                if runner == 'npm':
                    import shlex
                    # Fixed shell template; only a validated/quoted directory is substituted.
                    directory = shlex.quote('/workspace/'+prefix)
                    dependency_dir = shlex.quote('/opt/project-deps/'+prefix+'node_modules')
                    command=['/bin/sh','-c',f'if [ -d {dependency_dir} ]; then cp -a {dependency_dir} {directory}; fi; cd {directory} && npm test']
                else:
                    import shlex
                    pom = shlex.quote(prefix+'pom.xml')
                    command=['/bin/sh','-c',f'cp -a /opt/maven-repo /workspace/.maven-repo && mvn -B -o -f {pom} -Dmaven.repo.local=/workspace/.maven-repo -Dstyle.color=never test']
            identity = subprocess.run(['docker','image','inspect','--format','{{.Id}}',test_image],capture_output=True,text=True,timeout=15)
            result.update(testImage=test_image, imageDigest=identity.stdout.strip(), command=command)
            with tempfile.TemporaryDirectory(prefix='minty-tests-') as folder:
                for relative, data in files.items():
                    target = Path(folder) / relative; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
                try:
                    tested = subprocess.run(['docker', 'run', '--rm', '--name', name, '--label', f'minty.run={self.run_id}',
                        '--network', 'none', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--read-only',
                        '--tmpfs', '/tmp:rw,nosuid,size=128m', '--memory', '2g', '--cpus', '2', '--pids-limit', '256',
                        '-v', f'{Path(folder).resolve()}:/workspace', '-w', '/workspace', test_image, *command],
                        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout_seconds)
                    output = tested.stdout + tested.stderr
                    counts = parse_test_output(runner, output)
                    status = 'passed' if tested.returncode == 0 and counts.get('passed', 0) > 0 and not counts.get('failed') else 'no_tests' if tested.returncode == 0 else 'failed' if counts.get('total', 0) > 0 else 'error'
                    result.update(status=status, ok=status == 'passed', exitCode=tested.returncode, counts=counts, output=output[-25000:])
                except subprocess.TimeoutExpired:
                    result.update(status='timeout', output=f'测试超过 {timeout_seconds} 秒，已停止')
                finally:
                    subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=20)
                for relative, original in files.items():
                    tested_path = Path(folder) / relative
                    if (tested_path.is_symlink() or not tested_path.resolve().is_relative_to(Path(folder).resolve())
                            or not tested_path.is_file() or tested_path.stat().st_size != len(original) or tested_path.read_bytes() != original):
                        execution_copy_changed = True
                        break
        except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as exc:
            result['output'] = str(exc)
        result['durationSeconds'] = round(time.monotonic() - started, 3)
        result['sourceChangedDuringTest'] = execution_copy_changed or fingerprint != self.fingerprint(self.snapshot())
        self.last_test = result; self.last_test_files = files; self.test_history.append(result)
        return result

    def assessment(self):
        return assess(self.get_diff(), self.last_test)

    def suggested_test(self):
        files = self.snapshot()
        changed = [entry['path'] for entry in self.get_diff()['files']]
        for name in changed:
            if name.endswith('.java'):
                for parent in Path(name).parents:
                    manifest = (parent/'pom.xml').as_posix()
                    if manifest in files: return {'runner':'maven','path':parent.as_posix()}
            if name.endswith(('.js','.ts','.vue','.css')):
                for parent in Path(name).parents:
                    manifest = (parent/'package.json').as_posix()
                    if manifest in files and (parent/'package-lock.json').as_posix() in files:
                        try:
                            if json.loads(files[manifest]).get('scripts',{}).get('test'):
                                return {'runner':'npm','path':parent.as_posix()}
                        except (ValueError,UnicodeDecodeError): pass
        if any(Path(path).name.startswith('test') and path.endswith('.py') for path in files):
            pytest_tests = any((b'import pytest' in data or b'from pytest' in data) for path,data in files.items() if path.endswith('.py') and is_test_path(path))
            return {'runner': 'pytest' if pytest_tests else 'unittest', 'path': 'tests' if (self.root / 'tests').is_dir() else '.'}
        if any(path.endswith('.test.js') for path in files):
            return {'runner': 'node', 'path': '.'}
        return None

    @property
    def handlers(self):
        return {name: getattr(self, name) for name in ('list_files', 'search_text', 'read_file', 'write_file', 'edit_file', 'apply_patch', 'get_diff', 'run_tests')}


def is_test_path(path):
    name = Path(path).name
    return any(part in {'tests', 'test', '__tests__'} for part in Path(path).parts) or name.startswith('test_') or '.test.' in name or '.spec.' in name or name in {'pytest.ini', 'conftest.py', 'package.json', 'pyproject.toml', 'setup.cfg', 'tox.ini', 'pom.xml', 'build.gradle', 'build.gradle.kts'}


def assess(diff, tests):
    current = bool(tests) and tests.get('fingerprint') == diff['fingerprint'] and not tests.get('sourceChangedDuringTest')
    changed_tests = [entry['path'] for entry in diff['files'] if is_test_path(entry['path'])]
    outcome = ('verified' if tests and tests['status'] == 'passed' and current and not changed_tests and not diff.get('truncated') else
               'validation_failed' if tests and tests['status'] in {'failed','error'} and current else
               'unverified' if diff['totalFiles'] or tests else 'no_changes')
    return {'outcome': outcome, 'changedFiles': diff['totalFiles'], 'tests': tests, 'testsCurrent': current,
            'modifiedTests': changed_tests, 'reason': '测试通过，且测试后代码未变化' if outcome == 'verified' else
            '代码已修改但未获得有效测试证据' if outcome == 'unverified' else '测试未通过' if outcome == 'validation_failed' else '本次未修改工作文件',
            'scope': '仅表示已运行测试的结果，不保证所有需求均满足', 'diff': diff}


def git_diff(root, base):
    if not re.fullmatch(r'[a-fA-F0-9]{40}', base): raise ValueError('Invalid base commit')
    def git(*args):
        result = subprocess.run(['git', '-c', 'core.quotepath=false', *args], cwd=root, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        if result.returncode: raise RuntimeError(result.stderr[-2000:])
        return result.stdout
    git('add', '-N', '.')
    full = git('diff', '--binary', base)
    names = git('diff', '--name-only', base).splitlines()
    files = [{'path': path, 'kind': 'modified', 'patch': git('diff', base, '--', path)[:20000]} for path in names[:100]]
    return {'ok': True, 'totalFiles': len(names), 'files': files, 'truncated': len(names) > 100,
            'fingerprint': digest((base + full).encode())}


def remote_main():
    """JSON bridge called only inside an existing benchmark isolation container."""
    import sys
    request = json.load(sys.stdin)
    root = Path('/testbed')
    try:
        operation = request['operation']; arguments = request.get('arguments', {})
        tools = CodingTools(root, capture_baseline=False)
        if operation == 'get_diff':
            result = git_diff(root, request['base'])
        elif operation == 'run_tests':
            runner = arguments['runner']; path = arguments.get('path') or '.'
            if path != '.': tools.path(path)
            pattern = arguments.get('pattern', 'test*.py')
            if '/' in pattern or '\\' in pattern or pattern.startswith('-'): raise ValueError('Invalid test pattern')
            if runner not in {'unittest', 'pytest', 'node'}: raise ValueError('Unknown runner')
            seconds = max(5, min(int(arguments.get('timeout_seconds', 90)), 180))
            command = (['python', '-m', 'unittest', 'discover', '-s', path, '-p', pattern, '-v'] if runner == 'unittest' else
                       ['python', '-m', 'pytest', path, '-q'] if runner == 'pytest' else ['node', '--test', path])
            before = git_diff(root, request['base'])['fingerprint']; started = time.monotonic()
            try:
                tested = subprocess.run(command, cwd=root, capture_output=True, text=True, errors='replace', timeout=seconds)
                output = tested.stdout + tested.stderr; counts = parse_test_output(runner, output)
                state = 'passed' if tested.returncode == 0 and counts['passed'] > 0 and not counts['failed'] else 'no_tests' if tested.returncode == 0 else 'failed' if counts['total'] else 'error'
                result = {'ok': state == 'passed', 'status': state, 'counts': counts, 'exitCode': tested.returncode, 'output': output[-25000:]}
            except subprocess.TimeoutExpired:
                result = {'ok': False, 'status': 'timeout', 'counts': {}, 'exitCode': None, 'output': '测试超时'}
            result.update(runner=runner, path=path, fingerprint=before, durationSeconds=round(time.monotonic()-started, 3),
                          sourceChangedDuringTest=before != git_diff(root, request['base'])['fingerprint'])
        elif operation in tools.handlers and operation != 'run_tests':
            result = tools.handlers[operation](**arguments)
        else: raise ValueError('Unknown operation')
        print(json.dumps(result, ensure_ascii=False))
    except Exception as exc:
        print(json.dumps({'ok': False, 'error': str(exc)}, ensure_ascii=False))


def parse_test_output(runner, output):
    if runner == 'unittest':
        matches = re.findall(r'Ran (\d+) tests?\b', output)
        total = int(matches[-1]) if matches else 0
        failed = sum(int(n) for n in re.findall(r'(?:failures|errors)=(\d+)', output))
        skipped = sum(int(n) for n in re.findall(r'skipped=(\d+)', output))
        return {'total': total, 'passed': max(0, total - failed - skipped), 'failed': failed, 'skipped': skipped}
    if runner in {'node','npm'}:
        values = {key: int(value) for key, value in re.findall(r'#\s*(tests|pass|fail|skipped)\s+(\d+)', output)}
        return {'total': values.get('tests', 0), 'passed': values.get('pass', 0), 'failed': values.get('fail', 0), 'skipped': values.get('skipped', 0)}
    if runner == 'maven':
        matches=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',output)
        total,failures,errors,skipped = map(int,matches[-1]) if matches else (0,0,0,0)
        return {'total':total,'passed':max(0,total-failures-errors-skipped),'failed':failures+errors,'skipped':skipped}
    summary = re.findall(r'(\d+) (passed|failed|error(?:s)?|skipped)', output)
    values = {key: int(value) for value, key in summary}
    return {'total': sum(values.values()), 'passed': values.get('passed', 0), 'failed': values.get('failed', 0) + values.get('error', values.get('errors', 0)), 'skipped': values.get('skipped', 0)}


if __name__ == '__main__':
    remote_main()
