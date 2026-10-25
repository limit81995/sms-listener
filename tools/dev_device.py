#!/usr/bin/env python3
"""Watch Android sources, build a debug APK, and deploy to one explicit device."""

import argparse
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
APK = Path("app/build/outputs/apk/debug/app-debug.apk")
ACTIVITY = "io.github.smssentinel/.ui.MainActivity"
WATCH_FILES = (
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "local.properties", "gradlew", "app/build.gradle.kts",
)


def log(message):
    print(f"[真机预览] {message}", flush=True)


def snapshot():
    """Include additions/deletions, but never generated build outputs."""
    paths = [ROOT / name for name in WATCH_FILES]
    for directory in (ROOT / "app/src/main", ROOT / "gradle"):
        paths.extend(directory.rglob("*"))
    paths.extend((ROOT / "app").glob("proguard*"))
    state = {}
    for path in paths:
        relative = path.relative_to(ROOT)
        if any(part.startswith(".") or part == "__pycache__" for part in relative.parts):
            continue
        if path.name.endswith(("~", ".swp", ".tmp", ".bak")):
            continue
        try:
            if path.is_file():
                stat = path.stat()
                state[str(relative)] = (stat.st_mtime_ns, stat.st_size)
        except FileNotFoundError:
            # Editors may replace a file atomically while it is being scanned.
            continue
    return state


def find_adb(explicit):
    if explicit:
        return shutil.which(explicit)
    available = shutil.which("adb")
    if available:
        return available
    candidates = [Path.home() / "Android/Sdk/platform-tools/adb"]
    if os.environ.get("ANDROID_HOME"):
        candidates.insert(0, Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    return next((str(path) for path in candidates if os.access(path, os.X_OK)), None)


def device_ready(adb, serial):
    try:
        result = subprocess.run(
            [adb, "-s", serial, "get-state"], capture_output=True, text=True, timeout=10,
        )
        return result.returncode == 0 and result.stdout.strip() == "device"
    except subprocess.TimeoutExpired:
        return False


def commands(adb, serial):
    return [
        [str(ROOT / "gradlew"), "--console=plain", ":app:assembleDebug"],
        [adb, "-s", serial, "install", "-r", str(APK)],
        [adb, "-s", serial, "shell", "am", "start", "-S", "-W", "-n", ACTIVITY],
    ]


def deploy(adb, serial):
    steps = commands(adb, serial)
    started = time.monotonic()
    log("正在编译……")
    if subprocess.run(steps[0], cwd=ROOT).returncode:
        log("编译失败，未安装任何 APK。修正代码并保存后会重试。")
        return False
    if not (ROOT / APK).is_file():
        log(f"构建后未找到 {APK}，停止安装。")
        return False
    log(f"安装到 {serial}……")
    if subprocess.run(steps[1], cwd=ROOT).returncode:
        log("安装失败，未重启应用，也不会卸载或清除数据。修复后保存代码或重新运行命令。")
        return False
    # am start may print an Error even when the shell reports exit code zero.
    result = subprocess.run(steps[2], cwd=ROOT, capture_output=True, text=True)
    if result.stdout:
        print(result.stdout, end="", flush=True)
    if result.stderr:
        print(result.stderr, end="", file=sys.stderr, flush=True)
    output = result.stdout + result.stderr
    if result.returncode or "Error:" in output or "Exception" in output or "Status: timeout" in output:
        log("APK 已安装，但启动应用失败，请查看上面的错误。")
        return False
    log(f"已更新并重新打开应用，耗时 {time.monotonic() - started:.1f} 秒。")
    return True


def watch(adb, serial, debounce):
    state = snapshot()
    pending = True
    due = time.monotonic()  # Also deploy once at startup.
    waiting_for_device = False
    log("正在监听 app/src/main 和构建配置；保存后自动更新。按 Ctrl+C 停止。")
    while True:
        current = snapshot()
        now = time.monotonic()
        if current != state:
            state = current
            pending = True
            due = now + debounce
        if pending and now >= due:
            if not device_ready(adb, serial):
                if not waiting_for_device:
                    log(f"等待设备 {serial}。请检查 adb devices -l 和手机的无线调试端口。")
                    waiting_for_device = True
                due = time.monotonic() + 3
            else:
                waiting_for_device = False
                # Run synchronously: a second build can never overlap this one.
                # Changes made during this build remain detectable next iteration.
                deploy(adb, serial)
                pending = False
        time.sleep(0.25)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", "-s", required=True, help="adb devices 第一列的手机标识，例如 IP:连接端口")
    parser.add_argument("--adb", help="指定 Linux adb 路径；默认从 PATH 或 Android SDK 查找")
    parser.add_argument("--once", action="store_true", help="只编译、安装、启动一次")
    parser.add_argument("--dry-run", action="store_true", help="只显示命令，不连接手机、不编译、不安装")
    parser.add_argument("--debounce", type=float, default=0.8, help="文件停止变化多少秒后构建，默认 0.8")
    args = parser.parse_args(argv)
    if not args.serial.strip() or args.serial.startswith("-"):
        parser.error("请提供有效的设备标识")
    if not 0 <= args.debounce <= 60:
        parser.error("--debounce 必须在 0 到 60 秒之间")
    adb = find_adb(args.adb)
    if not adb:
        parser.error("未找到 adb，请加入 PATH 或使用 --adb 指定路径")
    if adb.lower().endswith(".exe"):
        parser.error("此命令使用 WSL 的 Linux adb，请通过 --adb 指定 Linux 版本")
    if args.dry_run:
        print(f"工作目录：{ROOT}")
        for command in commands(adb, args.serial):
            print(shlex.join(command))
        return 0
    log("每次更新会重启应用：已保存配置保留，未保存表单和当前报警会被中断。")
    try:
        if args.once:
            if not device_ready(adb, args.serial):
                log("目标设备未就绪，请先连接并授权；运行 adb devices -l 检查。")
                return 1
            return 0 if deploy(adb, args.serial) else 1
        watch(adb, args.serial, args.debounce)
    except KeyboardInterrupt:
        log("已停止监听。")
        return 130
    except OSError as error:
        log(f"无法执行开发命令：{error}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
