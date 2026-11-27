"""Smoke-tests the plugin jar on a real Paper server from the console.

    python3 -I scripts/smoke/run.py <server-dir> <scenario-file> [java]

<server-dir> holds paper.jar, eula.txt and plugins/Hawkeye.jar. Each scenario line is a console command,
optionally followed by " => <text>": the console output until the next command must contain that text. The next
command follows after three seconds, or once the text has come when it takes longer (a rollback, a batch of
generated data), for which the runner waits up to a minute.
The server is stopped after the last command; the exit status is 0 when every expectation held and Hawkeye
logged no ERROR. The full console log is written to <server-dir>/smoke.log; Hawkeye's stack traces are in
plugins/Hawkeye/errors and crash.
"""
import glob
import os
import re
import subprocess
import sys
import threading
import time

COMMAND_WAIT_SECONDS = 3
# How long an expected reply may take before it counts as missing.
EXPECT_TIMEOUT_SECONDS = 60
# Paper colours console output on a terminal that supports it (Windows does): matched text drops the codes.
ANSI = re.compile(r"\x1b\[[0-9;]*m")
START_TIMEOUT_SECONDS = 300


def main():
    server_dir, scenario_file = sys.argv[1], sys.argv[2]
    java = sys.argv[3] if len(sys.argv) > 3 else (glob.glob(
        os.path.expanduser("~/.gradle/jdks/eclipse_adoptium-25*/bin/java")) or ["java"])[0]
    steps = []
    for line in open(scenario_file, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        command, _, expected = line.partition(" => ")
        steps.append((command.strip(), expected.strip()))

    # Java writes a pipe in the platform encoding (cp1252 on Windows) unless told otherwise, and a reader that
    # dies on an undecodable byte leaves the pipe full and the server's logging blocked: ask for UTF-8 and
    # replace whatever still does not decode.
    process = subprocess.Popen([java, "-Xmx2G", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar",
                                "paper.jar", "--nogui"], cwd=server_dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    lines = []
    started = threading.Event()

    def read():
        for output in process.stdout:
            lines.append(output)
            if "Done (" in output:
                started.set()

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    failures = []
    if not started.wait(START_TIMEOUT_SECONDS):
        failures.append("the server did not start")
    else:
        time.sleep(8)
        for command, expected in steps:
            mark = len(lines)
            lines.append(">>> " + command + "\n")
            process.stdin.write(command + "\n")
            process.stdin.flush()
            time.sleep(COMMAND_WAIT_SECONDS)
            reply = ANSI.sub("", "".join(lines[mark + 1:]))
            waited = COMMAND_WAIT_SECONDS
            while expected and expected not in reply and waited < EXPECT_TIMEOUT_SECONDS:
                time.sleep(1)
                waited += 1
                reply = ANSI.sub("", "".join(lines[mark + 1:]))
            if expected and expected not in reply:
                failures.append(f"{command!r}: expected {expected!r}")
        process.stdin.write("stop\n")
        process.stdin.flush()
    try:
        process.wait(120)
    except subprocess.TimeoutExpired:
        process.kill()
        failures.append("the server did not stop")
    reader.join(5)
    with open(os.path.join(server_dir, "smoke.log"), "w", encoding="utf-8") as out:
        out.writelines(lines)
    # Hawkeye logs under the plugin's name and under its class loggers; the error log prints each problem once
    # as one line naming its file under plugins/Hawkeye/errors or crash.
    errors = [line for line in lines if ("[Hawkeye]" in line or "[gg.moonrise.hawkeye" in line)
              and ("ERROR" in line or "Exception" in line)]
    failures.extend("console: " + line.strip() for line in errors)
    for failure in failures:
        print("FAIL", failure)
    print("ok" if not failures else f"{len(failures)} failure(s)", f"({len(steps)} command(s))")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
