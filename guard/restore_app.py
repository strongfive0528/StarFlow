#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
app 数据还原 —— 配合 guard/backup_app.py 使用。

⚠️ 前提：app **已经装着**（还原不负责安装；绝不卸载/安装 app）。

用法：
    python guard/restore_app.py guard/_backups/com.moe.starflow-20260930-013000 --confirm-overwrite
"""
import argparse
import hashlib
import json
import os
from pathlib import PurePosixPath
import re
import subprocess
import sys
import tarfile
import uuid

DEFAULT_ADB = os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)


def adb_path():
    return DEFAULT_ADB if os.path.exists(DEFAULT_ADB) else "adb"


def run(adb, args, serial=None, check=True):
    cmd = [adb] + (["-s", serial] if serial else []) + args
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    output = (r.stdout + r.stderr).strip()
    if check and r.returncode != 0:
        raise SystemExit(f"命令失败（退出码 {r.returncode}）：{output}")
    return output


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass
    ap = argparse.ArgumentParser()
    ap.add_argument("backup_dir")
    ap.add_argument("-s", "--serial")
    ap.add_argument("--skip-private", action="store_true", help="只还原外部数据")
    ap.add_argument("--skip-external", action="store_true", help="只还原私有数据")
    ap.add_argument("--confirm-overwrite", action="store_true", required=True,
                    help="明确确认覆盖目标设备上的现有 app 数据")
    a = ap.parse_args()

    mf = os.path.join(a.backup_dir, "manifest.json")
    if not os.path.isfile(mf):
        raise SystemExit(f"找不到清单: {mf}")
    m = json.load(open(mf, encoding="utf-8"))
    pkg = m["package"]
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", pkg):
        raise SystemExit("备份清单里的包名格式不合法")
    serial = a.serial or m.get("serial")
    adb = adb_path()

    if not a.skip_private:
        if m.get("privateComplete") is False:
            raise SystemExit("私有数据备份不完整；拒绝覆盖目标数据")
        if not m.get("privateTar"):
            raise SystemExit("备份不含完整私有数据；拒绝覆盖目标数据")
        if m["privateTar"] != os.path.basename(m["privateTar"]):
            raise SystemExit("备份清单的私有 tar 文件名不合法；拒绝还原")
        tar = os.path.join(a.backup_dir, m["privateTar"])
        if not os.path.isfile(tar):
            raise SystemExit(f"私有数据备份不存在: {tar}")
        expected = m.get("privateTarSha256")
        if expected and sha256_of(tar).lower() != expected.lower():
            raise SystemExit("私有数据备份 SHA-256 不匹配；拒绝还原")
        try:
            with tarfile.open(tar, mode="r:") as archive:
                members = archive.getmembers()
                for member in members:
                    member_path = PurePosixPath(member.name.replace("\\", "/"))
                    if member_path.is_absolute() or ".." in member_path.parts:
                        raise SystemExit("私有数据 tar 含越界路径；拒绝还原")
                    if member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
                        raise SystemExit("私有数据 tar 含链接或特殊文件；拒绝还原")
        except (OSError, tarfile.TarError) as error:
            raise SystemExit(f"私有数据 tar 校验失败；拒绝还原: {error}")
    if not a.skip_external:
        if m.get("externalComplete") is False:
            raise SystemExit("外部数据备份不完整；拒绝覆盖目标数据")
        ext = os.path.join(a.backup_dir, "external", pkg)
        if not os.path.isdir(ext):
            raise SystemExit(f"外部数据备份不存在: {ext}")

    print(f"备份   : {a.backup_dir}  ({m['createdAt']}, {m['versionName']})")
    print(f"目标   : {pkg} @ {serial}")

    if "package:" not in run(adb, ["shell", "pm", "path", pkg], serial, check=False):
        raise SystemExit(f"❌ 设备上没装 {pkg} —— 先装回 app 再还原")

    print("\n停止 app ...")
    print("  " + (run(adb, ["shell", f"am force-stop {pkg}"], serial) or "ok"))

    # 私有数据：tar 推到 app 自己的外部目录（app uid 可读），再 run-as 解包
    if not a.skip_private:
        tar = os.path.join(a.backup_dir, m["privateTar"])
        remote = f"/sdcard/Android/data/{pkg}/_restore-{uuid.uuid4().hex}.tar"
        print("\n[1/2] 还原私有数据 ...")
        print("  push : " + run(adb, ["push", tar, remote], serial).splitlines()[-1])
        out = run(adb, ["shell", f"run-as {pkg} tar -xf {remote} -C /data/data/{pkg}"], serial)
        print("  解包 : " + (out or "ok"))
        run(adb, ["shell", f"rm -f {remote}"], serial)

    # 外部数据：整体推回
    if not a.skip_external:
        ext = os.path.join(a.backup_dir, "external", pkg)
        if not os.path.isdir(ext):
            print(f"⚠️  缺少 {ext}，跳过外部数据")
        else:
            print("\n[2/2] 还原外部数据 ...")
            print("  " + run(adb, ["push", ext + os.sep + ".", f"/sdcard/Android/data/{pkg}/"], serial)
                  .splitlines()[-1])

    print("\n完成。启动 app 验证：")
    print(f"  {adb} -s {serial} shell am start -n {pkg}/.MainActivity")
    return 0


if __name__ == "__main__":
    sys.exit(main())
