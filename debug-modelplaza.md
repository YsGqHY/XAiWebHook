# Model Plaza 调试指南

## 当前状态

已完成：
1. ✅ CLI Bridge 登录成功（token 已获取）
2. ✅ 页面加载策略改为 `DOMCONTENTLOADED`（避免 `NETWORKIDLE` 超时）
3. ✅ 新版本插件已构建：`build/mirai/XAiWebHook-0.2.0.mirai2.jar`

待验证：
- 页面元素 XPath 是否正确
- 搜索功能是否可用
- 分组筛选是否可用

## 调试步骤

### 1. 启用可见浏览器模式

在 `config/kim.hhhhhy.x.webhook/webhook_config.yml` 中修改：

```yaml
browser:
  headless: false  # 改为 false，显示浏览器窗口
```

### 2. 更新插件并重载

```bash
# 复制新版本插件到服务器 plugins 目录
# 在 mirai-console 执行：
/xwebhook reload
```

### 3. 触发查询命令

在配置的群聊发送：
```
分组 gpt-4
```

这时会弹出可见的浏览器窗口。

### 4. 观察浏览器行为

**预期行为**：
1. 浏览器打开 Model Plaza 页面
2. 自动填充搜索框
3. 2 秒后开始查找元素

**如果页面停留在登录页**：
- 手动登录（CLI Bridge token 可能已过期）
- 登录后页面应自动跳转到 Model Plaza

**如果页面正常但报元素找不到**：
- 说明 XPath 不正确，需要检查实际 DOM 结构

### 5. 获取正确的 XPath

如果元素定位失败，你需要：

1. **打开浏览器开发者工具**（F12）
2. **检查实际 DOM 结构**
3. **获取正确的 XPath**

#### 需要的元素：

**搜索框**：
- 当前 XPath: `//*[@id="app"]/div[2]/div[2]/main/div/div[1]/div[1]/div/input`
- 用途：输入模型名进行搜索

**分组容器**：
- 当前 XPath: `//*[@id="app"]/div[2]/div[2]/main/div/div[1]/div[3]`
- 用途：找到左侧的分组筛选区域

**模型卡片列表**：
- 当前 XPath: `//*[@id="app"]/div[2]/div[2]/main/div/div[3]//div[contains(@class, 'model-card')]`
- 用途：获取搜索结果中的模型列表

**分组标签**（在模型详情表格中）：
- 当前 XPath: `//*[@id="app"]/div[2]/div[2]/main/div/div[3]/div[1]/div/div[4]/div/div/div/div/table/thead/tr/th[3]`
- 用途：从模型卡片中提取分组信息

#### 如何获取 XPath：

1. 在开发者工具中右键点击元素
2. 选择 **Copy → Copy XPath**
3. 将 XPath 发给我

### 6. 提供调试信息

请提供以下信息：

1. **浏览器是否成功打开？**
2. **页面是否正常加载（不在登录页）？**
3. **控制台日志输出**（尤其是 `[ModelPlaza]` 开头的）
4. **如果元素找不到，提供正确的 XPath**

## 临时调试代码

如果你想手动测试页面元素，可以在浏览器控制台运行：

```javascript
// 测试搜索框
document.evaluate('//*[@id="app"]/div[2]/div[2]/main/div/div[1]/div[1]/div/input', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue

// 测试分组容器
document.evaluate('//*[@id="app"]/div[2]/div[2]/main/div/div[1]/div[3]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue

// 测试模型卡片
document.querySelectorAll('.model-card')
```

如果返回 `null` 或空数组，说明 XPath/选择器不正确。

## 下一步

完成上述调试后，告诉我：
1. 哪些 XPath 需要修正（提供正确的）
2. 页面交互有什么问题
3. 日志中的错误信息

我会据此更新代码并重新构建。
