"""One entry point for every edge-server test suite.

Run:  python tests/run_all.py

The suites mix two styles (standalone check() scripts and unittest), but every file
already runs as "python tests/test_X.py", so this runner just launches each one in a
subprocess and fails if any exits non-zero. CI calls this single command.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    files = sorted(f for f in os.listdir(HERE)
                   if f.startswith("test_") and f.endswith(".py"))
    failed = []
    for name in files:
        print("\n=== %s ===" % name, flush=True)
        r = subprocess.run([sys.executable, os.path.join(HERE, name)], cwd=os.path.dirname(HERE))
        if r.returncode != 0:
            failed.append(name)
    print("\n%d suites, %d failed" % (len(files), len(failed)))
    for name in failed:
        print("  FAILED: %s" % name)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())