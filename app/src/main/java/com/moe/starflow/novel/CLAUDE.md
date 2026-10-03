# 小说模块（`novel/`） — 项目约定

> 本文件是仓库根目录 `CLAUDE.md` 的**模块分册**：跨模块的东西（构建命令、架构总览、高频踩坑、
> UI / 主题 / 日志 / 框选坐标系等硬约束）都在根文件里，**动这个模块前先保证读过根文件**。
> 拆出来的原因很实在：根文件曾经是一个几十万字符的单文件、每次会话都要吃进去，
> 而这些细节只有动这个目录时才需要。内容从根文件**逐字搬来**，没有改写。

## 小说模块（`novel/`，feature/novel-text-import）

**格式判定先看内容再信扩展名**（`NovelFormatDetector`）：`.txt` 里塞 HTML 的、无扩展名的都要认出来；编码（UTF-8/GBK/Big5）同理按字节判。

### ⚠️ 分页与绘制必须共用同一份 `StaticLayout` 几何（改这一区之前必读）

分页（`NovelPaginator`）与绘制（`NovelPageView`）**读同一批数**：`PageSegment` 存的是**行区间**
（`lineStart..lineEnd`），高度一律取 `getLineBottom(i) - getLineTop(i)`，段高 = 各行之和。
两边各算一套的话底部文字就会被裁掉 —— 三条都踩过：

- 分页用**单字探针**量出「单一」行高再乘行数（估算）→ 某行更高（换字体/表情/全角标点落到别的字体）时每页多塞一行
- 绘制把 segment 的 substring 取出来**重新排版** → 重新断行可能多出一行
- 段间距按 `round(段距/行高)` 折算成**整行** → 不足一行被兜成一行，一页少放一段、底部一大片空白

⚠️ `NovelPageView` 里的 `clipRect` 只是**最后兜底**、不是修复手段；真被切到时打 `NovelDbg: PAGE_OVERFLOW`（含越界像素数）。

### ⚠️ 这一区的单测证明不了什么

Robolectric 的文本引擎是**桩**：`Paint.breakText` 不按宽度换行、行高恒为 `字号 × 行距倍率`。
所以分页刻意做成**纯核心 + Android 外壳**：`paginateByLines` 只收「行起点 + 每行高度」（测试自己合成度量），
`paginate` 才去建 `StaticLayout`。**「真机行高 ≈ 字号 × 1.15~1.5」这一半只能在设备上看日志确认**，
别用单测绿来宣称排版正确。长样本守卫见 `NovelPaginationRealismTest`（样本每章必须能分出多页 —— 早期样本
每章只有几百字节，一屏一页，"翻不动"其实是样本太短）。

### 其他踩过的坑

- **并发加载按序号丢弃**（`loadToken`）：`loadChapter` 被 onCreate/译文到达/改排版/尺寸变化多处调用，不丢弃过期结果会把章节号与页码一起拽回去（现场表现是「翻页卡在第一页、点目录也没用」）
- **阅读模式取值迁移**（`novel_reader_mode_version`）：旧 `1`=滚动，新 `1`=上下翻页、滚动挪到 `2`
- **两个阅读器共用一套约定**：背景/翻页动画/旋转的 prefs 键与漫画共用（`manga_reader`），字号与排版是 `novel_*` 独立；`CurlPageView`、四个 transformer、`ReaderProgressBar` 直接复用 `mangaimport/reader`
- **面板是打开那一刻的快照**（`NovelPanelState` 全是 `val`），宿主改了值必须显式回推，两个方向都只留**一个出口**：
  - 面板内部派生 UI → `refreshDerivedUi`；排版参数 → `bindTypography`（初始填充与「恢复默认」后的回读共用）
  - 宿主 → 面板 → `NovelPanelSheet.notifyHostState(当前章 / 目录标签 / 章状态)`，挂在 `updateChapterTocLabel()` 上（换章、翻页、译文到达、重算统计都会走到）。
    ⚠️ **按字段开推送就是这类 bug 的成因**：早先只有 `notifyTranslateChanged(stats)`，于是「在面板里切章」不刷新；新增宿主字段时必须加进 `notifyHostState` 这一篇，否则漏掉是默认结果
  - 同理别在面板里留第二份数据：`updateSummary()` 曾读快照里的 `state.chapterStats`，而推送改的是 adapter 里那一份 —— 推了也白推

### ⚠️ 段落切分：TXT 正文「一行一段」（`SPLIT_VERSION` 的来历）

`NovelParagraphSplitter.linesToParagraphs`（`TxtParser` / `FolderNovelParser` 调）：按 `\n` 切开、
逐行 trim、空行丢弃、再用空行重组 —— **一行就是一段**。

- **不做这一步，TXT 会被整章切成一个段落**：网文/公版 txt 几乎都是"一行一段、行间不留空行"，
  而 `split` 只认空行分段。整本公版《三国演义》实测：只按空行切 **240 块**（其中 120 块 >500 字、
  最大一块 7295 字），一行一段是 **1703 段**。不修的后果是一串连锁 —— 阅读器里是一堵墙、
  翻译按整章发一个请求（远超模型上下文）、章行分母恒为 1、长按多选只能选到整章、锚点全落同一段
  （用户报的「明明有很多段落却只有 2 段」就是这个）
- ⚠️ **只对 TXT / 文件夹里的 txt 用**：HTML/EPUB 那边单个换行是**段内**换行（`<br>`），
  `HtmlTextExtractor` 已经按空行分好段了，再归一化会把一整段按 `<br>` 切碎
- ⚠️ 代价：**硬换行**的 txt（一行几十字、句子被折断）会被切碎。中文小说几乎不这么排，
  而"一行一段"是压倒性的常态 —— 取舍明确选了它

### ⚠️ `SPLIT_VERSION` +1 之前，先确认清理路径还在

段落切分规则一变，旧 `paraIndex` 就整体错位。⚠️ 译文表主键是
`(novelId, novelKey, chapterIndex, paraIndex)`、**不含 `splitVersion`** —— 旧版本的行占着同一批主键，
新版本的行 `insertIgnore` **静默写不进去**（"补齐分母"与"标记翻译中"全废，且不报错，看不出错）。

- 现存的清理路径：`NovelChapterTranslator.resetStale`（阅读器进入时跑）→ `dao.deleteOtherVersions`
- **当前值已经是 2**（上面那次「一行一段」时 +1 的），所以改切分规则照旧 +1 即可 ——
  别看到版本号已是 2 就以为"动完了不能再动"，也别把上面那句清理路径删了
- 另一条路是把 `splitVersion` 并进主键（要一次重建表的迁移），没做

### ⚠️ 当前状态（2026-09-27）

**已完成**：导入（txt / epub / zip-html / 文件夹）、双阅读器（左右 / 上下 / 滚动）、四种翻页动画、
背景、底部面板、字号与段落参数、面板 ↔ 宿主状态同步、**按批翻译（手动 / 自动 / 增量）**、
**长按多选翻译 / 重翻 / 清除选中段的译文**、章行段数与字数与失败原因、清空本章（二次确认）、
导出译文 / 原文 / 双语、书架。

**翻译口径（别再改错，有测试钉着）**：

- **批 = 连续 N 段**（默认 **2**（2026-10-03 用户口径，原 3），范围 1–10），**不跨章**
- **手动**：当前页第一段没翻的段起，翻**一批**
- **自动**：盯着**当前页**一批批翻，本页翻完就停（等翻页）。
  ⚠️ **自动恒为串行**（用户口径 2026-10-03：「自动翻译只会一批一批向后翻译，**前一批没结束不会开下一批**；
  增量和整章才受最大请求数限制」）→ 调度里 `slots` 在 AUTO 下被夹成 1；`OcrLock` 仍只看**引擎**
  （`concurrency() == 1`），远端 API 下 AUTO 不抢 OCR 锁
- **增量**：自动 + 不受当前页限制，从**当前页第一段**往后翻「向后批数 × 每批段数」段（**滑动窗口**，不是一次性额度：窗口翻完停下，用户翻页窗口自己前移、接着翻；章末即停）；**并发 N 只作用于它和整章**
- **防抖按「屏」+「上一批收尾」两个触发点**（2026-10-04 用户口径修）：
  用户报的现场是「**黄色翻译中的背景在上一批翻译完后就会立刻跳到当前正在翻的页面**」——
  旧写法只把防抖挂在"翻屏"上，而翻屏时上一批还在飞：那次 `delay(debounce)` 在"等在飞批"的
  过程中被**白白消耗**掉，等上一批真收尾时防抖早已过期 → 立刻认领下一批，黄底无缝跳过去。
  现在待翻的屏变了 **或 有批收尾（在飞清零）** 都会排一次「等待页面稳定」（两个 `AtomicBoolean`：
  `spanChanged` / `settled`，见 `NovelTranslationQueue` 的 `页面稳定等待` 日志），
  所以「上一批结束 → 停一拍 → 才轮到下一批」。
  ⚠️ **黄底只画「当前这一屏真的在飞」的段**（用户口径 2026-10-04：「我一直翻页还是会有黄底」）：
  翻屏的瞬间把不在本屏的在飞批从高亮里摘掉，一批落定也立刻摘掉 —— 一直翻页时屏幕上不该再出现黄底。
  ⚠️ 只在**整条流水线空了**才排（`inflight.isEmpty()`）：增量 N>1 时前面的批收尾不打断补位，
  第一批三个照旧错峰连发。
- 面板打开 / 双击翻译按钮 = 暂停并**回退手动**；**离开阅读器（返回键/关掉）** 与
  **切后台（home/息屏）现在是同一套**（用户口径 2026-10-04：「切后台只暂停什么意思？？
  且后台就直接关闭当前翻译进程，回退到手动模式！（本章翻译除外）」）→ 一律：停队列 + 中止在途
  请求 + **回退手动** + 提示 `reader_translate_force_stopped_to_manual`；
  ⚠️ **只有整章任务例外**（应用级宿主，继续跑）。
  （2026-09 那份"切后台只暂停、回来接着翻"的口径**已被用户明确撤销**，别再改回去。）
- 守卫：`NovelBatchPlannerTest`、`NovelTranslationQueueTest`（三种模式怎么推进 + 翻页防抖 +
  **上一批收尾后的防抖** + 清掉译文的段照常翻）、
  `NovelTranslationConcurrencyTest`（并发错峰 + **自动恒串行**）

### 批量翻译：章节卡片 + 应用级后台任务 + 并发错峰（2026-10 大改，改动前必读）

> 用户口径：与漫画**同一套**形态 —— 「翻译本章 / 清除本章译文」从面板挪到**每个章节卡片**上、
> 翻译本章要能**后台跑 + 通知栏看进度 + 随时暂停/取消**、排队中的批要显示「等待」、
> 文本可以比漫画更激进（**最多 10 个同时在飞**、错峰 500ms）、单批内容过长得先问一声。

**共享基础设施在 `translate/batch/`（漫画共用，改它之前看 `translate/CLAUDE.md`）**：
`ChapterJobRunner`（准备阶段串行 + 翻译阶段并发 N + 按章暂停/取消/等待）、`TranslationJobRegistry`
（通知栏唯一数据源）、`TranslationJobService`（前台服务，**每章一条通知**）。小说侧的宿主是
`NovelChapterJobHost` + `object NovelTranslationHub`（仿 `ReaderTranslationHub`：应用级 scope、
按 `novelId` 缓存、实现 `ChapterJobSource` 并 `register`、**无 UI 且无任务时回收**并取消收集协程）。

**面板 UI（改了面板就必须跟着改这几处）**：
- **字号层级（用户口径 2026-09-27）**：**筛选 tab 15sp > 章卡片标题 14sp > 页/段行 13sp**；按钮 12sp
- 章行 = **卡片**（`item_novel_chapter_card.xml`）：章标题 14sp 加粗 +
  **两个按钮**（主：`翻译本章 ⇄ 暂停 ⇄ 继续`，实心药丸；次：`清空译文 ⇄ 取消`，淡红药丸）+
  第二行进度/失败/字数 + 展开箭头 + 失败明细面板。**点卡片空白 = 切到该章**（切章只从记录里点）
- **卡片的样子**统一走 `CardBackdrop`（`mangaimport/reader/CardBackdrop.kt`）：
  **浅色面板 = 白底 + 1dp 细描边**（面板本身是白的，灰块会"和整体 UI 不搭"）、深色 = 深灰块 + 亮描边；
  圆角 10dp + 卡片间 4dp 留白 + `RippleDrawable` 水波（自绘底之后 `?selectableItemBackground` 就没了）
- ⚠️ **展开出来的行必须"在章卡片组里面"**：`CardBackdrop.applyNested(...)` —— **方角 + 左侧竖线 +
  上下零间距 + 缩进**（`item_translate_page_state.xml` 的 marginStart=14dp）。只做缩进+同色不够
- **在飞批也是行**：`activeBatches`（宿主 `activeByChapter()`，来自 **`runner.translatingPages()`**）在展开的章里
  逐批显示、**琥珀高亮 + 「翻译中」徽章**，排在「等待」行前面；宿主在**开翻前**也推一次 UI，
  否则 N 个批同时起飞要等第一个返回才看得见高亮。
  ⚠️ 判据是 **`translatingPages()`（真进翻译段）**，不是 `inFlightPages()`（含"已预取还没开始"，
  见 `ChapterTaskStage`）；`waitingBatches` 也相应改成 `waitingPages + queuedPages`
  （2026-09-28 与漫画面板「识别中 / 翻译中 / 等待」三段对齐）
- ⚠️ **`waiting` 属性必须自己 `rebuild()`**：面板推送顺序是「先 waiting、后 active」，而 `active` 为空时
  会提前 return —— 少了 rebuild，"刚排上队的批"要等下一次别的字段变化才出现（`NovelPanelSyncTest` 卡这条）
- ⚠️ **面板里原来的全局 `btn_translate_action` / `btn_translate_clear` 已按用户要求删除**，别再往面板加
- **翻译参数分两层**：**「等待页面稳定」（原「启动延迟」，2026-10-04 改名）单独一行**（常显）；
  **其余四个收进可折叠组「批次处理」**
  （`row_batch_group` + `btn_batch_group` + `batch_group_content`）：组内顺序 = **启动增量翻译向后 →
  每批段数 → 单批预警 → 翻译模型最大同时请求数**，**默认折叠**，展开态存 prefs
  （`novel_batch_group_expanded`，`NovelPanelSheet` 里那一份 prefs）。
  **取值排版统一成「段落」那套**（标签在左、取值贴右同一行，进度条另起一行占满整宽，说明再下一行），
  五项都要有说明文案；所有新控件（含 `tv_debounce_hint`/`tv_ahead_hint`/`tv_batch_hint`/
  `tv_batch_group_title`/`tv_batch_group_arrow`）都**必须**接 `applyPanelTheme`，否则深色面板下不可见
  （`NovelPanelThemeTest` 会拦）
- ⚠️ **「批次处理」组与下面的筛选（历史记录）栏之间留 10dp**（用户口径：说明别紧挨着那一栏）
- 新增宿主字段照旧**两个方向各一个出口**：`NovelPanelHostState`（+`NovelPanelState`）→
  `renderHostState`；面板 → 宿主走 `NovelPanelCallbacks`
  （`onChapterPrimary` / `onChapterSecondary` / `onChapterClear` / `onConcurrency` / `onBatchWarn`）。
  `NovelPanelSyncTest` 是这条契约的测试
- ⚠️ 章卡片**完成态**显示「翻译完成」标记 + 「清空译文」；**只有任务真的在跑/暂停**时才显示「暂停/取消」
  （判据是 `running || paused`，别写成 `job != null`/`jobActive` —— 那会让已完成的章一直显示暂停与取消）

**并发（`NovelTranslationQueue`）**：
- **N = `TranslationConcurrency.novelConcurrency(context, prefs)`**（`novel_concurrent_requests`；
  **本地引擎恒 1**）。⚠️ 滑块必须读写**默认 prefs** —— 阅读器自己那份是 `novel_*`，写错了就是
  "滑块能拖、值也存了，但翻译永远用默认值"的静默失效
- ⚠️ **默认值 = 3**（原 5，用户口径 2026-10-03）：老用户里「还停在旧默认 5」的由
  `NovelPanelStyle.migrateConcurrency` 一次性挪到 3，**自己拖过滑块的不动**（与 `migrateLineSpacing` 同一套）
- 调度循环：满员（`inflight.size >= N`）时 60ms 轮询**补位**；
  ⚠️ **AUTO 下 `slots` 被夹成 1**（自动恒串行，见上「翻译口径」），N 只作用于增量；
  `N > 1` 且未满员时按 **500ms 错峰**启动下一批
- **等页面稳定**：见上「翻译口径」那条（翻屏 **或** 上一批收尾都排一次；
  `AtomicBoolean spanChanged` / `settled`，日志 `页面稳定等待 Nms`）
- `done = 已译 ∪ failedAnchors ∪ **在飞批段号**` —— 最后一项是并发的前提：
  不把在飞的批当"已完成"，同一个锚点会被反复挑中 → 同一瞬间发出 N 个一模一样的请求（白烧额度）。
  ⚠️ **没有 `userCleared` 这一项了**（2026-10-04 撤）：清译文 = 一次显式编辑，宿主当场停队列 +
  回退手动，之后那几段就是普通"没译文的段"（见「清除选中段的译文」那条）
- ⚠️ **账本 `failedAnchors` 用并发集合**（N>1 时有多个工人读写），
  且**在飞账本的一切读写都要持锁**：曾出现"调度侧无锁 `sorted()` 迭代 + 工人侧加锁 `removeAll`"
  → `ConcurrentModificationException` 抛在 `launch` 里（scope 无异常处理器 = 崩进程）
- **`OcrLock` 只在 N==1（本地引擎）时整批持有**；N>1（远端 API）**不持锁** —— 那把锁是**OCR 引擎锁**，
  小说没有 OCR，与漫画 `translatePhase` 同口径。N==1 的行为与改造前**逐字一致**（既有 14 条断言原样过）
  ⚠️ 但持锁期间**必须持续打心跳**（`withLockHeartbeat(token)`，5s 一次）：本地引擎一整批可以跑几分钟，
  远超 `OcrLock.STALE_TIMEOUT_MS`(30s)，不打心跳会被自愈机制判成"持有者已死"而**强制放锁** →
  另一个翻译任务（另一本书的章节任务 / 漫画阅读器 OCR / 超分）随即拿到锁，**两个线程同时用同一个本地引擎**。
  ⚠️ 心跳协程写 `launch { }` 继承调用方派发器，**不要写 `launch(Dispatchers.IO)`** —— 那会把真实线程池的
  非确定性带进用 `runTest` 虚拟时钟写的 `NovelTranslationQueueTest`（实测 14 例里挂 10 例）。
- 暂停 = 只停取批、队列留着（=「等待」行）；取消 = 丢还没开始翻的批、**已翻好的保留**；
  **在途批「强制结束」**（`stop()` → `job.cancel()`：协程取消，译文只在成功后写库 ⇒ 取消不入库、也不等它
  —— 与漫画「打开面板/退出阅读器」的强制退出同一套，2026-09-28 核对过）。
  「等待」= `runner.waitingPages` + `queuedPages`（章任务）为唯一数据源，不要拿"提交数 − 完成数"推

**单批超长预警**：`NovelBatchWarning`（档位 `2048/4096/8192/16384/32768`，默认 **2048**
（2026-10-03 用户口径，原 4096；老用户里"还停在旧默认 4096"的由 `NovelPanelStyle.migrateBatchDefaults`
一次性挪到 2048，"每批段数 3 → 2"同一个迁移）；`utils/TokenEstimator` 粗估 token）+
`NovelBatchWarnGate` / `NovelOversizeConfirmer`（**同一轮只打扰一次**）。
判据收敛在 **`NovelChapterTranslator.translateBatch` 一处**（翻译本章 / 多选 / 自动 / 增量全汇到这里，
不会漏路径）；⚠️ **后台任务（阅读器已关）没有 UI 可弹窗时必须放行**，否则任务会静默停在一半。

⚠️ **弹窗的两个出口**（用户口径 2026-10-03）：
- **忽略并继续**（肯定）→ 照翻
- **取消本次翻译**（否定）→ **关闭当前翻译进程 + 回退手动**（不再是"只跳过这一批"）：
  前台队列走 `NovelReaderActivity.cancelCurrentTranslation()`（停队列 + 中止在途请求 + 模式回手动 +
  取消当前章的整章任务）；整章任务那条路在 `NovelChapterJobHost.translateUnit` 里收尾
  （`result.isEmpty && oversize.hasDeclined()` → `cancel(章)`），否则那一章会被一批批静默跳过
- 60s 不点 = 当作"继续"（提示 `novel_translate_oversize_timeout`），不能让后台任务无限挂着

**暂停提示（2026-10-04 更新：退出 与 切后台 同一套）**：**离开阅读器（返回键/关掉）**、
**切后台（home/息屏）**、**打开面板** 都是**强制退出在途翻译（含中止 HTTP）+ 回退手动** →
文案 `reader_translate_force_stopped_to_manual`（「翻译进程已强制退出，回退到手动模式」）；
**章节任务取消**（本来就手动）→ 只报 `reader_translate_force_stopped`（用户口径 2026-09-28）。
提示在 **`onStop`** 里发（`onDestroy` 里弹的 Toast 常被系统吞掉），`onDestroy` 只兜底 + 防重复
（兜底那条也把模式回退手动）。
⚠️ **提示必须走 [showSticky] 语义**（`showPausedNotice` 里那条注释）：提示发完紧接着就是 `onDestroy` 的
`TranslationStatusOverlay.dismiss()`，普通 `show()` 会被**当场抹掉**（只剩几十毫秒的一闪）——
用户报的「退出阅读器**没看到**回退提示」有一半来自这里。
⚠️ `pauseToManual` **不再动章节任务**（那是后台任务，暂停前台队列不该打断它）。
⚠️ `novel_translate_paused_background`（"已暂停，回来继续"）**已废弃**：切后台不再只暂停。

**两条"提示"通道别搞混（用户 2026-10-04 明确纠正过）**：
| 用户说的 | 实际指 | 实现 |
|---|---|---|
| 「**app 的通知系统**」 | 应用内的**状态浮层**（`TranslationStatusOverlay` 的芯片） | **等页面稳定那一拍 → 「等待页面稳定…」**（队列阶段 `WAITING_STABLE`，此刻还没发请求、也没有黄底）；自动/增量翻译中 → `showImmediate`「正在翻译 · 第 X/Y 页」；**整章 → `showRunning`「整章翻译中 · 第 N 章 x/y 批」**（`NovelReaderActivity.refreshChapterJobChips`，与别的提示并存、可按句柄移除） |
| 「通知」（系统通知栏） | Android 通知（**要下拉状态栏**才看得到） | 整章 = `TranslationJobService` 每章一条（id 10000+）；阅读器队列 = `ReaderTranslationNotify`（id 9997）。通道 `translation_progress`（DEFAULT 重要度） |

**通知栏（用户口径 2026-10-03：翻译过程的提示**必须进通知系统**，不只是面板徽章）**：
- **整章任务**：`TranslationJobService` 每章一条（注册表驱动），通道走
  **`TranslationNotifyChannel.ensure`**（唯一入口，`translation_progress` + **`IMPORTANCE_DEFAULT`**）
  ⚠️ 旧的 `chapter_translation` 建成 `IMPORTANCE_LOW`，而通道重要度**创建后 App 改不了** →
  MIUI 上不亮状态栏图标、还容易被折叠成"不重要通知"，用户报的「整章翻译没看到通知」就是它。
  换 id + 顺手删旧通道，守卫 `translate/batch/ReaderTranslationNotifyTest`
- **阅读器前台队列（手动一批 / 自动 / 增量）**：`translate/batch/ReaderTranslationNotify`
  （固定 id 9997，避开汇总 9998 与每章 10000+），由 `NovelReaderActivity.observeQueue` 逐次状态镜像
  ⚠️ **它也必须先 `TranslationNotifyChannel.ensure`**：Android 8+ 往**不存在**的通道发通知是
  **静默丢弃**，而这条通知不经过前台服务 —— 只用自动/增量、从没点过「翻译本章」的用户，
  通道根本没人建（这是"看不到通知"的第三种漏法）
  ⚠️ **只在 `TRANSLATING` / `WAITING_LOCK` 时挂着**，DRAINED/IDLE、面板打开、`onStop`/`onDestroy`
  立刻收掉 —— 一条写着"正在翻译"却什么都没在跑的通知比没有通知更糟
  ⚠️ 它**不进** `TranslationJobRegistry`、也**不需要前台服务**：队列活在阅读器的 `lifecycleScope` 里，
  离开阅读器就停（用户明确要求），没有"后台保活"的需求

**守卫**：`NovelTranslationConcurrencyTest`（错峰铺开 / 满员不发 / 结束补位 / N=1 全程单飞 /
并发下失败批不重挑）、`NovelBatchWarningTest`、`NovelChapterTranslatorTest`（预警三条路径）、
`NovelPanelSyncTest`、`NovelPanelThemeTest`、`NovelBatchPlannerTest`、`NovelTranslationQueueTest`。

**几条结构性约定（踩过才有的）**：

- **面板状态只有一个入口**：`NovelPanelHostState`（宿主能改、面板要显示的全部状态）+
  `NovelPanelSheet.renderHostState`。新增这类字段**必须同时加进类型和 render**，
  否则那处 UI 永远停在打开那一刻 —— `NovelPanelSyncTest` 是这条契约的测试
- ⚠️ **一个控件只能出现在两个配色清单之一**（`panelLabelIds` / `panelSubIds`，`intArrayOf` + 标记注释）：
  两段 `setTextColor` 后写的赢 → 同一个 id 两处都登记会**静默变淡色**。`PanelThemeGuardTest` 机械守卫。
- **面板主题也只有一个入口**：`NovelPanelSheet.applyPanelTheme`（深色/浅色随**阅读背景**，不随全局主题）。
  它是手写控件清单，**清单外的控件不会跟着切背景变** —— Tab 图标、筛选 chip、两个弹窗都因此漏过，
  表现是「切了背景有几处颜色不动，关掉面板再打开才正常」。新增控件组必须接进去，
  `NovelPanelThemeTest` 会拦（把修复行注释掉它就会红）
- **报错必须带原始原因**：引擎不能吞 `TranslationResult.Error`，要带出异常类型 + message 的
  cause 链 / 模型返回了什么（`NovelBatchResult.error` → `failCode` → 章行展开里原样显示）
- **无章节标记的 TXT = 整本一章**（不再按 8000 字硬切）
- 翻译按钮旁的**三态切换按页显示**：当前页有译文才出现
- **「保持段落完整」默认关**（排版面板里的开关）：关掉 = 按间距填满页面，段落可被切断
- **右下翻译浮层组的判据全部在 `NovelTranslateChrome`**（纯逻辑 + 单测），别再散在 Activity 里：
  - **翻译按钮**：当前视野还有没翻的可翻译段才显示（翻页 = 本页；滚动 = 当前屏幕可见段）。
    ⚠️ 滚动模式**不要**按整章判 —— 那就成了"始终显示"，用户明确否掉了
  - **三态按钮**：当前页（滚动模式 = 当前屏幕**可见段**）有译文才显示 —— 没译文时点了看不出变化。
    ⚠️ 判据变了要记得**重算**：`onPaged` 与 `updateFromScroll` 都要调 `refreshTranslationChrome`
    （早先只在 `loadChapter` 里调，于是翻页后浮层停在上一次的状态）
  - **「可翻译段」= TEXT 且非空白**（`isTranslatable`）：图片与 <4 字的短行按设计永不翻译，
    算进「待翻译」会让含 `……` 的页永远判不出"翻完了"、翻译按钮常驻且点了没反应
  - **没有「缓存命中」这一套**（与漫画相反）：不允许凭「本章/本页已有译文」把按钮变成重翻图标，
    否则"点一下会发生什么"取决于看不见的状态
- **重翻 = 长按多选段落**（文本阅读器唯一的重翻入口）：长按进选择模式 → 点段落加减选 →
  点空白/返回键退出（切章、开面板、切阅读模式、离开阅读器也清；**翻页不清**）。
  按钮语义随选中集变：全未翻 = 翻译 / 全已翻 = 重新翻译 / 混合 = 翻译+重翻（左侧文案写明 + 段数）。
  翻译时按面板「每批段数」拆成多次请求、逐批上屏；队列走 `translateExact`（**不过规划器** ——
  规划器会把已有译文的段当已完成跳过，那样重翻永远翻不动）
- **清除选中段的译文**（与长按多选**共用同一份选中集**）：选中集里**只要有已翻的段**，右下角就出现
  回收站图标（判据收在 `NovelTranslateChrome.showClear(translatedSelected) = translatedSelected > 0`）→
  弹窗报「将清除选中的 N 段译文」（N = 选中**且已翻**的段数）→ 确定后删库 + 重载本章，
  那几段立刻回原文显示，三态/翻译按钮按新状态重算
  ⚠️ **"用户清过的段别再自动翻"的那份账已经取消**（2026-10-04 用户口径：「清译文后重翻只有
  手动生效，自动和增量即使当前页面刚才删除了译文，开启也没有任何翻译，不会翻译。你要考虑到
  自动/增量/本章翻译模式下编辑删除的情况」）→ 现在：**清译文 = 一次显式编辑**，
  `clearSelectedTranslations` 里当场 `pauseToManual()`（停前台队列 + 回退手动）——
  ① 刚清完**不会**立刻又翻回来（不会"清了个寂寞"）；② 用户之后**再开启**自动/增量时，
  那几段就是普通"没译文的段"，照常被翻。别再往队列里加"跳过账"。
  守卫：`NovelTranslationQueueTest` 的「清掉译文的段重新开启自动照常翻」
- **阅读位置是 `NovelAnchor(段号, 段内比例)`，不是段号**（`NovelAnchors`）：
  一段能跨好几页（只按空行分段，整章一段的样本就是），只按段号恢复会落到**段首那一页/段顶** ——
  译文到达必须重排，重排一次就把读者从段中间拽回段首（用户报的「翻译之后位置跳变」）。
  翻页模式锚点取本页第一个 segment 的段内比例、恢复时找**包含**该比例位置的那一页；
  滚动模式锚点记「首可见段已滚过多少」、恢复时 `scrollToPosition` 后再按新高度补偏移。
  ⚠️ `NovelChapterRepository.pageOfParagraph` 给的是「含该段的**第一页**」，**不要**拿来恢复阅读位置
  （断点续读落盘仍是段号，比例从段首起算）
- **锚点同时是「强制分页点」**（`NovelPaginator.paginate(anchor=…)` → `forceBreak`）：
  `pageOf` 只能给到"含锚点的那一页"，锚点可能落在**页尾** —— 译文比原文短时它被挤回**上一页**，
  读者看到的就是「翻译完回到前一页」（用户报的，`NovelAnchorPageFlowTest` 复现）。
  所以带位重排时必须让锚点那一行**另起一页**：重排前后屏幕第一行是同一行。
  锚点要进 `NovelChapterRepository.load` 的缓存键（不同锚点切出不同页表）；
  滚动模式与跳到章末不传。⚠️ 行号必须由**同一份 layout** 反查（`getLineForOffset`），不能自己除。
  ⚠️ **锚点之后正着填、锚点之前倒着填**（`paginateAround`）：只"提前收页"的话锚点前一页只剩小半页、
  底部一大片空白（用户报的「有的页面提前分页、底部预留大片空白」，双语↔译文/原文来回切最明显）。
  锚点本来就在页上半部分时**不强分**（`shouldForceBreak` 阈值）——那时强分没有收益，只白白扰动页表
- **带位重排期间锚点必须钉住**（`NovelReaderActivity.carryAnchor`）：
  `pageOf` 只能给到**页**（向下取整），落位后 `onPaged` 若把锚点重取成"这一页的页首"，
  页首永远比逻辑位置靠前 → **每批退最多一屏、误差逐批累积、单向往回跑**（实测 5 批丢一半位置）。
  只有用户真导航（滑动/点按翻页/拖进度条/切章/换模式）才允许重取；
  ⚠️ 不能"落地后清"——ViewPager2 同一帧末还会补派发一次 `onPageSelected`，那次会把锚点打回页首。
  `refreshTranslations` 取锚点要在**读完译文之后**（挂起前的锚点会因 IO 期间翻页而过期）。
  ⚠️ 附带纠正：ViewPager2 对 `notifyItemRangeChanged` 与 `notifyDataSetChanged` 一视同仁，
  都不会弹回第一页；真正的坑是**重排后页数缩到当前页号以下**会重置到第 0 页 → 刷新页表后
  必须紧跟 `setCurrentItem`（另有 `snapPagerToAnchor` 兜底）
- **「本章翻完了吗」的分母 = 整章可翻译段数**：SQL 那边是 `COUNT(*)`（数据库里这一章有几行），
  而行是**按批惰性写的** —— 只写这一批的话分母退化成"翻过的段数"，翻了几段就把整章标成
  「已翻译」（用户报的「没翻完却显示全部完成」）。所以 `NovelChapterTranslator.translateBatch`
  开翻前先 `ensureChapterRows` 把整章可翻译段落补成 IDLE 行（`insertIgnore` 幂等），
  章行/筛选再用宿主解析出的真分母（`NovelChapterStateAdapter.totalOf`）而不是库里行数
- **「本章多少字」同理，取整章可翻译段的字符和**（`NovelChapterRepository.charCountOf`，按
  `SPLIT_VERSION` 缓存）—— **不是库里已有的行数**（行是按批惰性写的，拿它当"本章多少字"会随翻的
  进度一起长）。`ensureChapterTotal` 里与分母**一次取回**（同一份段落缓存命中，不额外解析一遍），
  显示在面板章行第二行的「已翻 3/40 · 12,345字」里（`novel_chapter_progress_chars` 拼，中文无空格；
  分母或字数取回 0 时整行回落成只有进度）—— 用户要它是为了估翻译费用
- **增量 = 跟着当前页走的窗口**（不是一次性额度）：窗口 = 当前页第一段往后
  「向后批数 × 每批段数」段；用完停下，用户**翻页窗口自己前移**接着翻（`aheadLimitPara`）。
  面板增量行显示换算后的段数（"3 批 = 15 段"），改批数或每批段数都要重算（走 `refreshDerivedUi`）
  ⚠️ 窗口**起点**取「当前视野**全部**段」（`currentPageSpanIndexes`，含不可翻译的图片/短行），
  **不是**锚点用的那份可翻译段：本页一个可翻译段都没有时，用可翻译段定位会退化成「章首」→
  `remaining` 算成 0、只弹「已向后翻完 0 批 · 翻页继续」却一批都不翻（用户报的「当前页段数不足 →
  增量/自动始终 0 批」）。`NovelBatchPlanner.anchorForMode` 在**本页无可翻译段**时改用窗口起点
  继续向后（**自动模式不变**：本页翻完就停，等翻页）
- 状态芯片显示**翻到哪一页/哪一段**，不显示第几章（增量会一直往后翻，章号看不出进度）；
  且 DRAINED 不再喊「已翻译」—— 走到 DRAINED 有四种原因（翻完本页/翻完窗口/本章真翻完/一批失败被跳过）
- **滚动模式进度条按「像素比例」算，不按段序号**（`NovelScrollProgress`）：
  段落按空行切，一个几万字的章节可能**整章只有一段**（样例 `英文-单段超长-无空行.txt`），
  按「第几段/共几段」算的话比例恒为 0/1 → 进度条永远不动（用户报的「章节文字太多时进度条失效」）。
  位置取 `computeVerticalScrollRange/Offset/Extent`；绿条仍按段序号的**等分切片**（段高要测量才知道，
  滚动模式是懒加载的）。拖拽寻页也按比例滚（`seekScrollTo`），**循环补齐**：估算总长会漂，
  一次 `scrollBy` 未必吃到目标。⚠️ 程序化 `scrollToPosition` **不派发 onScrolled**，
  续读要靠 `novelScroll` 的 layout listener 补刷 —— 那个 listener 必须带「首可见段变了才刷」的闸门，
  否则 `setText → requestLayout → 布局 → 再补刷` 是每帧一次布局的死循环。
- **双语模式段间距 ×1.8**（`BILINGUAL_PARA_SPACING_FACTOR`，在 `NovelPanelStyle.textStyle` 里乘）：
  一段双语 = 原文行 + 译文行，行间只是一个普通行距；段间距不拉开就分不清"这行译文属于哪段原文"。
  ⚠️ 只作用于排版（分页与绘制共用同一份 style），**不改用户设置里那个 dp 数字**
- **琥珀高亮 = 「真的在飞的那几批」**（`NovelTextRenderer.COLOR_ACTIVE_BATCH`，与选中的蓝区分开）：
  只在 `TRANSLATING` / `WAITING_LOCK` 期间高亮在飞的段，**出结果（成功/失败）立刻擦**。
  ⚠️ 早先写的是「认领了就高亮、翻完也不擦」（本意是让你看得见"刚翻的是哪几段"），但一旦某批卡住/失败，
  那几段就**永远黄着** —— 用户看到的「首批一直黄着却不翻译」正是它（2026-10-03 改）。
  「哪几段失败了」改由面板失败明细 + 红条报，不靠底色记。高亮底两种模式都**铺满整行 + 8dp 圆角**
  （`HIGHLIGHT_CORNER_DP`）——只铺正文列会像被两侧裁了一刀
- **三态图标是自绘的** `ic_display_{translated,original,bilingual}`：
  ⚠️ 不得与翻译按钮的 `ic_reader_translate` **同形**（同一浮层组并排显示，用户会直接说"图标重复"）；
  ⚠️ 不要去描汉字字形（描「文」在 22dp 下看着就是「六」，用户问过"这什么玩意"）。
  三枚用同一套「文字块」语言：原文=多行细行 / 译文=一行粗行+一行短行 / 双语=上下两块都画
- **默认行距 1.4×**（原 1.5×）：⚠️ 只改常量对已用过的人无效（prefs 里存着旧默认值），
  所以 `migrateLineSpacing` 会把"还停在旧默认值"的人一起挪过来，自己调过行距的不动
- **段距与字号/行距完全解耦**（用户明确要求）：`paragraphSpacingDp` 只夹在固定的
  `PARA_SPACING_ABS_MIN..PARA_SPACING_MAX`，面板的 `sb_para_spacing` 也**只设固定 min/max**。
  ⚠️ 别把「段距必须大于行距」的动态下限接回来 —— 那样面板要按字号/行距动态改 SeekBar 的 min/max，
  而 SeekBar 会把 progress **夹到新区间**，用户一调字号或行距、段距滑块自己就跳走了
  （用户报的「调字号或行间距，段间距的进度条也会一起变化」）。行距拉大时段距显得密是用户看得见、
  拖一下就能改的事，不该由系统偷偷改他设的值。守卫：`NovelPanelStyleTest` 的
  「改行距不动段距」「改字号不动段距」
- **导出落点 = 手机的 Download**（`MediaStore.Downloads` + `RELATIVE_PATH=DIRECTORY_DOWNLOADS`，
  与漫画 `MangaReaderActivity.writeToDownloads` 同一个落点、同一句提示 `toast_saved_to_download`）。
  ⚠️ 不要写回 `getExternalFilesDir()`：那在 `/Android/data/<包名>/files/` 下，Android 11+ 的文件管理器
  进不去、用户拿不到导出的书（而且卸载即删）。重名交给 MediaStore 去重（会写成 `名字 (1).txt`），
  写完回读**实际**文件名再提示。拼字节那一层拆在 `NovelExport.writeBook`（可单测），
  MediaStore 那层只能真机验（Robolectric 没有 MediaProvider，`insert` 返回 null）
- **模型切换要立刻生效**：队列把引擎**按值缓存**了，设置页只写 prefs —— 所以阅读器监听默认 prefs
  （`NovelEngineConfig` 判定哪些键算引擎配置），变了就丢掉缓存的队列，下次用到时重建。
  ⚠️ 别改成「每批现造引擎」：本地引擎（NLLB）每造一次都要重载模型。监听要注册在**阅读器**里而不是面板里
  （改模型要先离开面板去设置页，面板回来时重新 onStart 才注册，那一刻已经错过变化）

**没做**：真机上端到端跑一次翻译（我只跑到单测 + 装机，实际翻译效果要用户验）。

样本：`tools/gen_novel_fixtures.py --repo` 生成英/日/韩长样本（每章 100 段，固定种子可复现）。
⚠️ **样本必须是被翻译的那一侧语言** —— 中文样本测「翻译成中文」等于什么都没测，
`NovelPaginationRealismTest` 里有语言占比守卫卡这件事。
