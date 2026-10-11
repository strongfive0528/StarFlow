# 手机安装与数据保护

> 范围：说明本项目安全安装、签名校验与设备数据备份流程。

目标只有两条：**不执行卸载/清数据；签名或版本不匹配时停止安装。**

## 安装或更新

唯一安装入口：

```powershell
python guard/install_safe.py app/build/outputs/apk/debug/app-debug.apk --confirm
```

多个手机连接时加 `-s <序列号>`。省略 `--confirm` 只做预检，不安装。

安装器会检查 APK 包名、版本、签名和目标设备。已有应用时，仅执行 `adb install -r`；签名不符、降级或预检工具缺失都会直接停止。**没有卸载、清数据或自动重试安装的代码路径。** 安装后会再次核对签名和外部数据目录统计。

不要用 `adb uninstall`、`pm clear`、Gradle `connected*` 测试或 `gradlew installDebug` 替代此入口。机器级 Gradle guard 会无条件拦截卸载任务和 connected 测试，没有绕过环境变量。

首次安装且手机上还没有此包时，不需要签名匹配；安装器执行普通 `adb install`。已有安装则必须与其签名完全一致。

## 签名怎么保持一致

可互相覆盖安装的 APK 必须使用同一份私有 keystore。环境变量只告诉 Gradle 到哪里读取它，不会把密钥同步到其他构建机。

签名身份由 keystore 中的密钥决定，与 `debug`/`release` 构建类型名称无关。本项目已经分发版本所用的签名身份必须原样保留；即使它最初来自 Android Studio 自动生成的 debug keystore，也不能在新电脑上用自动生成的另一份密钥替代。需要新构建机时，安全复制并校验同一份原始 keystore。

每台构建机都要从密码管理器或加密密钥库安全取得同一份 keystore，并通过环境变量、本机 Gradle 用户级属性或 CI 凭据流程提供 `STARFLOW_SIGNING_STORE_FILE`、`STARFLOW_SIGNING_STORE_PASSWORD`、`STARFLOW_SIGNING_KEY_ALIAS` 和 `STARFLOW_SIGNING_KEY_PASSWORD`。不要把这些值放进仓库的 Gradle 配置文件，也不要提交或发送 keystore 和密码。

配置完整后，所有可安装 APK/AAB（debug 和 release）都使用这份共享 keystore；未配置时打包会失败，避免每台机器悄悄使用不同的本地 debug key。

共享 keystore 必须是已经用于分发版本的原始密钥。换成新密钥会阻止已有安装直接更新；如果原始私钥找不到，先从安全备份恢复。公开证书不能恢复私钥，也不要为了安装而卸载用户版本。

## 备份与恢复

```powershell
python guard/backup_app.py
python guard/restore_app.py guard/_backups/<备份目录> --confirm-overwrite
```

备份目录不会复用或覆盖。备份脚本若不能导出私有数据或外部文件，会标为部分备份并返回失败；release 包通常不允许 `run-as`。恢复会覆盖现有数据，必须明确添加 `--confirm-overwrite`，且不接受缺失/校验失败的私有备份。

私有备份含设置和 API Key，只保存在可信本机目录；不要提交或转发备份文件。

`device_guard.py` 仅把明确的只读命令列为 SAFE；未知或拼接命令返回 CAUTION。日常安装不需要手动跑分类、快照、备份三套命令。`snapshot_state.py` 仅用于单独查看设备状态；多个设备时必须用 `--serial`。
