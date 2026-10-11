#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Install an APK without ever uninstalling or clearing app data.

Usage:
    python guard/install_safe.py app/build/outputs/apk/debug/app-debug.apk --confirm
    python guard/install_safe.py path\to\app.apk -s DEVICE_SERIAL --confirm

For an existing package this tool checks package name, version and signing
certificate first, then runs only ``adb install -r``. A mismatch aborts. There
is deliberately no uninstall, clear-data, downgrade, or recovery fallback.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


DEFAULT_PACKAGE = "com.moe.starflow"


class SafetyError(RuntimeError):
    pass


def configure_output():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass


def sdk_root():
    candidates = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"),
    ]
    for candidate in candidates:
        if candidate and os.path.isdir(candidate):
            return Path(candidate)
    raise SafetyError("找不到 Android SDK；请设置 ANDROID_SDK_ROOT")


def build_tools_dir():
    root = sdk_root() / "build-tools"
    candidates = [p for p in root.iterdir() if p.is_dir()] if root.is_dir() else []
    if not candidates:
        raise SafetyError("Android SDK 缺少 build-tools")

    def version_key(path):
        return tuple(int(x) for x in re.findall(r"\d+", path.name))

    return max(candidates, key=version_key)


def adb_executable(root):
    candidates = [
        os.environ.get("ADB"),
        str(root / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")),
        shutil.which("adb"),
    ]
    for candidate in candidates:
        if candidate and (os.path.isfile(candidate) or shutil.which(candidate)):
            return candidate
    raise SafetyError("找不到 adb")


def run(command, check=True):
    result = subprocess.run(
        [str(x) for x in command],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    output = (result.stdout + result.stderr).strip()
    if check and result.returncode != 0:
        raise SafetyError(f"命令失败（退出码 {result.returncode}）：{output}")
    return result.returncode, output


def connected_devices(adb):
    _, output = run([adb, "devices"])
    devices = []
    for line in output.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    return devices


def choose_device(adb, requested):
    devices = connected_devices(adb)
    if requested:
        if requested not in devices:
            raise SafetyError(f"指定设备不在线：{requested}；在线设备：{devices or '无'}")
        return requested
    if not devices:
        raise SafetyError("没有在线设备")
    if len(devices) != 1:
        raise SafetyError("检测到多个设备，请用 -s 指定目标：" + ", ".join(devices))
    return devices[0]


def apk_metadata(aapt, apk):
    _, output = run([aapt, "dump", "badging", str(apk)])
    match = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
        output,
        re.MULTILINE,
    )
    if not match:
        raise SafetyError("无法读取 APK 的包名和版本信息")
    try:
        version_code = int(match.group(2))
    except ValueError:
        raise SafetyError("APK 的 versionCode 不是整数")
    return match.group(1), version_code, match.group(3)


def signer_digests(java, signer_jar, apk):
    _, output = run([java, "-jar", signer_jar, "verify", "--print-certs", str(apk)])
    digests = {
        value.replace(":", "").lower()
        for value in re.findall(
            r"certificate SHA-256 digest:\s*([0-9a-f:]+)", output, re.IGNORECASE
        )
    }
    if not digests:
        raise SafetyError(f"无法读取 APK 签名证书：{apk}")
    return digests


def installed_apk_paths(adb, serial, package):
    code, output = run([adb, "-s", serial, "shell", "pm", "path", package], check=False)
    if code != 0:
        raise SafetyError(f"无法查询手机上的包路径：{output}")
    paths = [line.partition(":")[2].strip() for line in output.splitlines()
             if line.startswith("package:")]
    return paths


def installed_version_code(adb, serial, package):
    _, output = run([adb, "-s", serial, "shell", "dumpsys", "package", package])
    match = re.search(r"\bversionCode=(\d+)", output)
    if not match:
        raise SafetyError("无法读取手机上已安装应用的 versionCode")
    return int(match.group(1))


def external_stats(adb, serial, package):
    path = f"/sdcard/Android/data/{package}"
    code, exists = run(
        [adb, "-s", serial, "shell", "sh", "-c", f"test -d {path} && echo yes || echo no"],
        check=False,
    )
    if code != 0:
        raise SafetyError(f"无法核对外部数据目录：{exists}")
    if exists.strip().splitlines()[-1:] != ["yes"]:
        return (False, 0, 0)
    list_code, listing = run([adb, "-s", serial, "shell", "ls", "-1", path], check=False)
    size_code, size = run([adb, "-s", serial, "shell", "du", "-sk", path], check=False)
    if list_code != 0 or size_code != 0:
        raise SafetyError("无法读取外部数据目录统计；停止安装")
    size_match = re.search(r"^(\d+)\s", size, re.MULTILINE)
    entries = len([line for line in listing.splitlines() if line.strip()])
    return (True, entries, int(size_match.group(1)) if size_match else -1)


def main():
    configure_output()
    parser = argparse.ArgumentParser(description="签名预检后安全覆盖安装，不卸载、不清数据")
    parser.add_argument("apk", help="待安装 APK 路径")
    parser.add_argument("-s", "--serial", help="目标设备序列号；多设备时必填")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="期望包名（默认 com.moe.starflow）")
    parser.add_argument("--confirm", action="store_true", help="确认在设备上执行覆盖安装")
    args = parser.parse_args()

    try:
        if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", args.package):
            raise SafetyError("包名格式不合法")
        apk = Path(args.apk).expanduser().resolve()
        if not apk.is_file():
            raise SafetyError(f"APK 不存在：{apk}")

        sdk = sdk_root()
        tools_dir = build_tools_dir()
        aapt = tools_dir / ("aapt.exe" if os.name == "nt" else "aapt")
        signer_jar = tools_dir / "lib" / "apksigner.jar"
        java = shutil.which("java")
        if not java and os.environ.get("JAVA_HOME"):
            java_name = "java.exe" if os.name == "nt" else "java"
            candidate = Path(os.environ["JAVA_HOME"]) / "bin" / java_name
            if candidate.is_file():
                java = str(candidate)
        if not aapt.is_file() or not signer_jar.is_file() or not java:
            raise SafetyError("缺少 aapt、apksigner.jar 或 Java；停止安装")

        package, new_code, version_name = apk_metadata(aapt, apk)
        if package != args.package:
            raise SafetyError(f"APK 包名是 {package}，期望 {args.package}")
        new_signers = signer_digests(java, signer_jar, apk)

        adb = adb_executable(sdk)
        serial = choose_device(adb, args.serial)
        current_paths = installed_apk_paths(adb, serial, package)
        installed = bool(current_paths)
        before_external = external_stats(adb, serial, package) if installed else None

        if installed:
            old_code = installed_version_code(adb, serial, package)
            if new_code < old_code:
                raise SafetyError(
                    f"APK versionCode {new_code} 低于手机上的 {old_code}；不会降级或卸载"
                )
            base_apk = next((p for p in current_paths if p.endswith("/base.apk")), current_paths[0])
            with tempfile.TemporaryDirectory(prefix="starflow-signature-") as temp_dir:
                pulled = Path(temp_dir) / "installed.apk"
                run([adb, "-s", serial, "pull", base_apk, str(pulled)])
                old_signers = signer_digests(java, signer_jar, pulled)
            if old_signers != new_signers:
                old_text = ", ".join(sorted(old_signers))
                new_text = ", ".join(sorted(new_signers))
                raise SafetyError(
                    "签名与手机现有版本不一致；已停止，未安装、未卸载、未清数据。\n"
                    f"  手机：{old_text}\n  APK： {new_text}\n"
                    "请使用签署现有版本的 keystore 重新构建；若怀疑是旧产物，先强制重打包后再检查。"
                )

        print(f"设备：{serial}")
        print(f"包名：{package}  版本：{version_name} ({new_code})")
        print(f"SHA-256 签名：{next(iter(sorted(new_signers)))}")
        if installed:
            print("预检：与手机现有签名一致；只会执行 adb install -r，保留应用数据。")
        else:
            print("预检：手机上尚未安装此包；只会执行首次安装。")

        if not args.confirm:
            print("未执行安装。确认后在命令末尾添加 --confirm。")
            return 0

        command = [adb, "-s", serial, "install"]
        if installed:
            command.append("-r")
        command.append(str(apk))
        _, output = run(command)
        print(output or "安装成功")

        after_paths = installed_apk_paths(adb, serial, package)
        if not after_paths:
            raise SafetyError("安装后未找到应用包；工具没有执行卸载或清数据，请停止后续操作")
        actual_code = installed_version_code(adb, serial, package)
        if actual_code != new_code:
            raise SafetyError(
                f"安装后 versionCode 是 {actual_code}，预期 {new_code}；包仍已安装，不要卸载"
            )
        with tempfile.TemporaryDirectory(prefix="starflow-signature-check-") as temp_dir:
            installed_apk = Path(temp_dir) / "installed.apk"
            after_base = next((p for p in after_paths if p.endswith("/base.apk")), after_paths[0])
            run([adb, "-s", serial, "pull", after_base, str(installed_apk)])
            actual_signers = signer_digests(java, signer_jar, installed_apk)
        if actual_signers != new_signers:
            raise SafetyError("安装后签名核对失败；应用未被卸载，勿尝试卸载重装")
        if installed:
            after_external = external_stats(adb, serial, package)
            if before_external != after_external:
                raise SafetyError(
                    f"安装后外部数据统计发生变化：{before_external} → {after_external}；不要卸载，先检查数据"
                )
            print(f"数据核对：外部目录统计未变 {after_external}。")
        print("完成：包仍已安装，签名核对通过；本工具从未调用卸载或清数据命令。")
        return 0
    except SafetyError as error:
        print(f"停止：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
