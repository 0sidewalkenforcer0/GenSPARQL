# 如何运行 OpenRouterProviderTest

## 方法 1：基本测试（不需要 API Key）

运行不需要 API Key 的测试：

```bash
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm
mvn test -Dtest=OpenRouterProviderTest -pl gensparql-llm
```

这会运行：
- ✅ testProviderCreation
- ✅ testIsNotAvailableWithoutApiKey
- ✅ testGenerationFailsWithoutApiKey
- ⏭️ testOpenRouterAPI (会被跳过，因为没有 API Key)

## 方法 2：完整测试（需要 API Key）

### 步骤 1：设置 API Key

```bash
export OPENROUTER_API_KEY="your-api-key-here"
```

### 步骤 2：运行测试

```bash
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm
mvn test -Dtest=OpenRouterProviderTest -pl gensparql-llm
```

这会运行所有测试，包括实际的 API 调用测试。

## 方法 3：只运行 API 测试

```bash
export OPENROUTER_API_KEY="your-api-key-here"
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm
mvn test -Dtest=OpenRouterProviderTest#testOpenRouterAPI -pl gensparql-llm
```

## 方法 4：在 IDE 中运行

1. 打开 `OpenRouterProviderTest.java`
2. 右键点击类名或测试方法
3. 选择 "Run" 或 "Debug"
4. 如果要运行 API 测试，需要先设置环境变量

### 在 IntelliJ IDEA 中设置环境变量

1. Run → Edit Configurations
2. 选择测试配置
3. 在 "Environment variables" 中添加：
   - Name: `OPENROUTER_API_KEY`
   - Value: `your-api-key-here`

## 获取 OpenRouter API Key

1. 访问 https://openrouter.ai/
2. 注册/登录账号
3. 进入 Dashboard → Keys
4. 创建新的 API Key
5. 复制 API Key（格式：`YOUR_OPENROUTER_API_KEY...`）

## 测试输出示例

### 没有 API Key 时：
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 1
```

### 有 API Key 时：
```
=== OpenRouter API Test ===
Prompt: How many r's are in the word 'strawberry'? Answer with just a number.
Response: 3
Tokens used: 15 prompt + 1 completion
Latency: 1234ms
===========================

Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

## 常见问题

### Q: 测试被跳过怎么办？
A: 设置 `OPENROUTER_API_KEY` 环境变量即可。

### Q: 如何验证 API Key 是否有效？
A: 运行测试，如果 `testOpenRouterAPI` 通过，说明 API Key 有效。

### Q: 测试失败怎么办？
A: 检查：
1. API Key 是否正确设置
2. 网络连接是否正常
3. OpenRouter 服务是否可用

