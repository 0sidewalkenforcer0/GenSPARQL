# OpenRouter Provider 测试

这个目录包含 OpenRouter Provider 的测试用例。

## 测试文件

- `OpenRouterProviderTest.java` - OpenRouter Provider 的功能测试

## 运行测试

### 基本测试（不需要 API Key）

这些测试会检查 provider 的基本功能，不需要 API Key：

```bash
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm
mvn test -Dtest=OpenRouterProviderTest -pl gensparql-llm
```

### 完整测试（需要 API Key）

要运行所有测试（包括实际调用 OpenRouter API 的测试），需要设置环境变量：

```bash
export OPENROUTER_API_KEY="your-api-key-here"
mvn test -Dtest=OpenRouterProviderTest -pl gensparql-llm
```

## 测试用例

### 1. testProviderCreation
- 测试 provider 的创建
- 不需要 API Key

### 2. testIsAvailable
- 测试 provider 的可用性检查
- 需要 API Key（使用 `@EnabledIfEnvironmentVariable`）

### 3. testIsNotAvailableWithoutApiKey
- 测试没有 API Key 时 provider 不可用
- 不需要 API Key

### 4. testSimpleGeneration
- 测试简单的生成请求
- 需要 API Key
- 使用模型：`allenai/olmo-3.1-32b-think:free`

### 5. testAsyncGeneration
- 测试异步生成
- 需要 API Key

### 6. testGenerationWithContext
- 测试带上下文的生成（类似 Query 2 的场景）
- 需要 API Key

### 7. testGenerationFailsWithoutApiKey
- 测试没有 API Key 时生成失败
- 不需要 API Key

### 8. testModelSpecFromURI
- 测试从 URI 解析 ModelSpec
- 需要 API Key

## 获取 OpenRouter API Key

1. 访问 https://openrouter.ai/
2. 注册账号
3. 在 Dashboard 中创建 API Key
4. 设置环境变量：`export OPENROUTER_API_KEY="YOUR_OPENROUTER_API_KEY..."`

## 测试结果说明

- **Tests run**: 总测试数
- **Failures**: 失败的测试数
- **Errors**: 错误的测试数
- **Skipped**: 跳过的测试数（通常是因为没有设置 API Key）

如果看到 "Skipped: 5"，这是正常的，表示需要 API Key 的测试被跳过了。

