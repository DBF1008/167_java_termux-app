# 会话列表固定与重排功能实现计划

## 背景

当前 Termux 侧边栏会话列表完全按创建顺序排列，没有固定(Pin)或重排能力。会话一多后常用会话难以快速定位，重连服务或重建界面后顺序也不稳定。本次改动为会话列表增加固定和重排能力，让常用会话保持稳定顺序，并在界面重建后正确恢复。

## 架构决策

| 决策 | 方案 | 理由 |
|------|------|------|
| 数据模型 | 外部 `SessionOrderManager` 管理，不修改 `TermuxSession`/`TerminalSession` | `TerminalSession` 在 `terminal-emulator` 模块（零 Android 依赖），添加持久化字段破坏模块边界；通过 handle(UUID) 关联更干净 |
| 显示排序 | adapter 维护独立的 `mDisplayList`，不修改底层 `mTermuxSessions` | 底层列表由 service 独立管理生命周期，排序与展示解耦避免线程竞争 |
| 用户交互 | 长按弹出多选项菜单（重命名/固定/上移/下移） | ListView 不便做拖拽，长按已有重命名入口，扩展一致性好 |
| 持久化 | SharedPreferences 中存 JSON 数组（单个 key） | 原子写入，max 8 条约 640 字节，与现有 `current_session` 模式一致 |
| 键盘快捷键 | 快捷键跟随显示顺序（display list） | 用户期望按视觉位置切换，而非内部创建顺序 |

## 实现步骤

### Phase 1: 核心数据层 — SessionOrderManager

#### 1.1 新增偏好常量
**文件:** `termux-shared/.../preferences/TermuxPreferenceConstants.java`
- 在 `TERMUX_APP` 内部类中新增:
```java
public static final String KEY_SESSION_ORDER_STATE = "session_order_state";
```

#### 1.2 新增偏好读写方法
**文件:** `termux-shared/.../preferences/TermuxAppSharedPreferences.java`
- 新增 `getSessionOrderState()` / `setSessionOrderState(String json)`:
```java
public String getSessionOrderState() {
    return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_SESSION_ORDER_STATE, null, true);
}
public void setSessionOrderState(String value) {
    SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_SESSION_ORDER_STATE, value, false);
}
```

#### 1.3 新建 SessionOrderManager
**新文件:** `termux-shared/.../shell/SessionOrderManager.java`
- 包路径: `com.termux.shared.termux.shell`
- 依赖: `TermuxAppSharedPreferences`

**内部数据结构:**
```java
// 每个会话的排序元数据
static class OrderEntry {
    String handle;    // TerminalSession.mHandle
    boolean pinned;
    int position;     // 组内排序键
}
```

**核心 API:**
```java
public class SessionOrderManager {
    private final TermuxAppSharedPreferences mPrefs;

    // 构建排序后的显示列表
    public List<TermuxSession> buildDisplayList(List<TermuxSession> sessions);

    // 固定/取消固定
    public void setPinned(String handle, boolean pinned);
    public boolean isPinned(String handle);

    // 组内移动
    public void moveUp(String handle);
    public void moveDown(String handle);

    // 会话生命周期回调
    public void onNewSession(String handle);
    public void removeEntry(String handle);

    // 持久化
    List<OrderEntry> loadEntries();     // 从 SP 读取，异常时返回空列表
    void saveEntries(List<OrderEntry>);  // 序列化为 JSON 写入 SP
}
```

**排序规则:**
- `buildDisplayList()`: 按 `(pinned DESC, position ASC)` 排序
- 新会话自动分配到 unpinned 组末尾
- 未知 handle 自动创建默认条目（unpinned, position=max+1）
- 不存在于活跃列表中的旧条目在保存时清理

**JSON 格式:**
```json
[{"h":"uuid1","p":true,"o":0},{"h":"uuid2","p":false,"o":0}]
```

### Phase 2: 单元测试 — SessionOrderManager

#### 2.1 新增 Robolectric 测试依赖
**文件:** `termux-shared/build.gradle`
- 在 dependencies 块添加:
```groovy
testImplementation "org.robolectric:robolectric:4.10"
```
- 添加 testOptions:
```groovy
testOptions {
    unitTests {
        includeAndroidResources = true
    }
}
```

#### 2.2 新建测试类
**新文件:** `termux-shared/src/test/java/com/termux/shared/termux/shell/SessionOrderManagerTest.java`

| # | 测试用例 | 覆盖场景 |
|---|---------|---------|
| 1 | `buildDisplayList_emptySessions_returnsEmpty` | 空会话列表 |
| 2 | `buildDisplayList_noStoredState_returnsInsertionOrder` | 首次启动/无持久化数据 |
| 3 | `buildDisplayList_withPinnedAndUnpinned_pinnedFirst` | 固定会话排前 |
| 4 | `setPinned_true_movesToTopOfPinnedGroup` | 固定操作 |
| 5 | `setPinned_false_movesToUnpinnedGroup` | 取消固定操作 |
| 6 | `moveUp_withinGroup_swapsPositions` | 组内上移 |
| 7 | `moveUp_atBoundary_noOp` | 边界条件-顶部 |
| 8 | `moveDown_withinGroup_swapsPositions` | 组内下移 |
| 9 | `moveUp_doesNotCrossPinBoundary` | 不跨组移动 |
| 10 | `removeEntry_compactsPositions` | 删除后位置紧凑 |
| 11 | `onNewSession_appendsToUnpinnedEnd` | 新会话默认位置 |
| 12 | `staleEntries_removedOnRebuild` | 过期条目自动清理 |
| 13 | `corruptJson_degradesGracefully` | 异常数据容错 |
| 14 | `persistence_roundTrip` | 保存→新实例→加载一致 |
| 15 | `maxSessions_eightEntries_correctOrder` | MAX_SESSIONS=8 边界 |

### Phase 3: 修改 adapter — TermuxSessionsListViewController

**文件:** `app/.../terminal/TermuxSessionsListViewController.java`

**改动:**
1. 新增字段:
   - `private final List<TermuxSession> mDisplayList` — 排序后的显示列表
   - `private final SessionOrderManager mOrderManager` — 排序管理器

2. 修改构造函数: 接收 `SessionOrderManager` 参数

3. 新增 `refreshDisplayList()` 方法:
   - 调用 `mOrderManager.buildDisplayList(sourceList)` 更新 `mDisplayList`
   - 调用 `clear()` + `addAll(mDisplayList)` + `notifyDataSetChanged()`

4. 修改 `getView()`:
   - 使用 `getItem(position)` 从 display list 获取会话
   - 固定会话显示 📌 图标前缀（通过 SpannableString）

5. 修改 `onItemLongClick()`:
   - 替换原来的直接重命名，改为 `AlertDialog.Builder.setItems()` 弹出选项菜单:
     - **重命名** → 原有逻辑
     - **固定/取消固定** → `mOrderManager.setPinned()` + `refreshDisplayList()`
     - **上移** → `mOrderManager.moveUp()` + `refreshDisplayList()`
     - **下移** → `mOrderManager.moveDown()` + `refreshDisplayList()`
   - 根据当前状态动态显示标签（已固定显示"取消固定"等）
   - 到达边界时禁用上移/下移选项

### Phase 4: 修改会话管理 — TermuxTerminalSessionActivityClient

**文件:** `app/.../terminal/TermuxTerminalSessionActivityClient.java`

**改动:**
1. 新增字段: `private SessionOrderManager mOrderManager`
2. 构造函数: 接收 `SessionOrderManager`

3. `addNewSession()`: 成功创建后调用 `mOrderManager.onNewSession(handle)`

4. `removeFinishedSession()`: 删除前调用 `mOrderManager.removeEntry(handle)`

5. `switchToSession(boolean forward)`: 基于 display list 索引切换
   ```java
   List<TermuxSession> displayList = mActivity.getTermuxSessionListViewController().getDisplayList();
   int currentIndex = indexOf(session, displayList);
   int newIndex = forward ? (currentIndex+1) % size : (currentIndex-1+size) % size;
   setCurrentSession(displayList.get(newIndex).getTerminalSession());
   ```

6. `switchToSession(int index)`: 从 display list 取会话

7. `checkAndScrollToSession()`: 使用 display list 中的索引进行高亮和滚动

8. `toToastTitle()`: 使用 display list 索引显示编号

### Phase 5: 连接各组件 — TermuxActivity

**文件:** `app/.../TermuxActivity.java`

**改动:**
1. `onCreate()` 中创建 `SessionOrderManager` 实例
2. 传递给 `TermuxTerminalSessionActivityClient` 和 `TermuxSessionsListViewController`
3. `setTermuxSessionsListView()`: 传入 `SessionOrderManager`
4. `onServiceConnected()` 后调用 `refreshDisplayList()` 恢复显示顺序
5. 新增 `getSessionOrderManager()` getter

### Phase 6: 键盘快捷键适配

**文件:** `app/.../terminal/TermuxTerminalViewClient.java`

**改动:**
1. `Ctrl+Alt+1~9` (line 278-281): 改用 display list 索引
   ```java
   int index = unicodeChar - '1';
   mTermuxTerminalSessionActivityClient.switchToSession(index);  // 已改为用 display list
   ```

2. 新增快捷键:
   - `Ctrl+Alt+e` → 切换当前会话固定状态 (toggle pin)
   ```java
   } else if (unicodeChar == 'e' /* pin/unpin */) {
       mTermuxTerminalSessionActivityClient.togglePinCurrentSession();
   }
   ```

### Phase 7: 回归与集成测试

#### 7.1 新建测试类
**新文件:** `app/src/test/java/com/termux/app/terminal/SessionListManagementTest.java`

使用 Robolectric + JUnit 4，覆盖:

| # | 测试用例 | 覆盖场景 |
|---|---------|---------|
| 1 | `testNewSession_appendedToUnpinnedEnd` | 新建会话 |
| 2 | `testRenameSession_nameUpdated` | 重命名 |
| 3 | `testDeleteSession_removedFromDisplayList` | 删除会话 |
| 4 | `testDeletePinnedSession_orderCompacted` | 删除固定会话 |
| 5 | `testReconnectService_restoresCurrentSession` | 重连服务恢复当前会话 |
| 6 | `testReconnectService_preservesPinAndOrder` | 重连后保持固定和排序 |
| 7 | `testReconnectService_currentSessionDeleted_fallbackToLast` | 当前会话已删除时回退 |
| 8 | `testMaxSessions_eighthAllowed` | MAX_SESSIONS 边界-允许第8个 |
| 9 | `testMaxSessions_ninthBlocked` | MAX_SESSIONS 边界-阻止第9个 |
| 10 | `testKeyboardShortcuts_followDisplayOrder` | 快捷键跟随显示顺序 |
| 11 | `testSwitchNextPrev_wrapsInDisplayOrder` | 前后切换在显示顺序中环绕 |
| 12 | `testPinToggle_updatesDisplayOrder` | 固定切换更新显示 |
| 13 | `testMoveUpDown_withinGroup` | 上下移动在组内生效 |

## 关键文件清单

### 新建文件 (3)
- `termux-shared/src/main/java/com/termux/shared/termux/shell/SessionOrderManager.java`
- `termux-shared/src/test/java/com/termux/shared/termux/shell/SessionOrderManagerTest.java`
- `app/src/test/java/com/termux/app/terminal/SessionListManagementTest.java`

### 修改文件 (7)
- `termux-shared/.../preferences/TermuxPreferenceConstants.java` — 新增 key 常量
- `termux-shared/.../preferences/TermuxAppSharedPreferences.java` — 新增 getter/setter
- `termux-shared/build.gradle` — 添加 Robolectric 测试依赖
- `app/.../terminal/TermuxSessionsListViewController.java` — display list + 长按菜单 + pin 图标
- `app/.../terminal/TermuxTerminalSessionActivityClient.java` — 排序 hooks + display list 导航
- `app/.../terminal/TermuxTerminalViewClient.java` — 快捷键适配 + 新增 pin 快捷键
- `app/.../TermuxActivity.java` — 创建 manager 实例并注入

## 执行顺序

```
Phase 1 (数据层) → Phase 2 (单元测试验证) → Phase 3 (adapter) → Phase 4 (activity client) → Phase 5 (wiring) → Phase 6 (shortcuts) → Phase 7 (回归测试)
```

每个 Phase 完成后项目应可编译通过。
