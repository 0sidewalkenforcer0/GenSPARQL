# 单独运行查询脚本

这个目录包含了4个独立的查询示例，每个都可以单独运行和测试。

## 文件结构

### Java 文件
- `Query1Example.java` - 基础 SPARQL 查询（不使用 GENOP）
- `Query2Example.java` - GENOP Context Mode（使用变量生成描述）
- `Query3Example.java` - GENOP Base Mode（无输入变量，直接生成列表）
- `Query4Example.java` - GENOP Semantic Filter（语义过滤）
- `Query6Example.java` - SimScore-based Similarity Join（相似度连接）
- `Query7Example.java` - Cartesian Product with GENOP（笛卡尔积）
- `Query8Example.java` - GENOP before BGP（GENOP 在 BGP 之前）

### 运行脚本
- `run_query1.sh` - 运行 Query 1
- `run_query2.sh` - 运行 Query 2
- `run_query3.sh` - 运行 Query 3
- `run_query4.sh` - 运行 Query 4
- `run_query6.sh` - 运行 Query 6
- `run_query7.sh` - 运行 Query 7
- `run_query8.sh` - 运行 Query 8

## 使用方法

### 方法 1：使用运行脚本（推荐）

```bash
# 从项目根目录运行
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm
./gensparql-example/run_query1.sh
./gensparql-example/run_query2.sh
./gensparql-example/run_query3.sh
./gensparql-example/run_query4.sh
./gensparql-example/run_query6.sh
./gensparql-example/run_query7.sh
./gensparql-example/run_query8.sh
```

### 方法 2：从示例目录运行

```bash
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm/gensparql-example
./run_query1.sh
./run_query2.sh
./run_query3.sh
./run_query4.sh
./run_query6.sh
./run_query7.sh
./run_query8.sh
```

## 查询说明

### Query 1: List Scientists and Awards
- **类型**: 基础 SPARQL 查询
- **功能**: 列出所有科学家和他们的奖项
- **不需要**: LLM API Key

### Query 2: Generate Descriptions with GENOP
- **类型**: GENOP Context Mode
- **功能**: 使用 GENOP 为每个科学家生成描述
- **需要**: OpenRouter API Key（或使用 Mock provider）

### Query 3: Base Mode GENOP - List Nobel Prize Winners
- **类型**: GENOP Base Mode
- **功能**: 直接让 LLM 列出5个获得诺贝尔奖的科学家
- **需要**: OpenRouter API Key（或使用 Mock provider）

### Query 4: Semantic Filter - Scientists Born in France
- **类型**: GENOP Semantic Filter
- **功能**: 使用 LLM 的知识判断哪些科学家出生在法国
- **需要**: OpenRouter API Key（或使用 Mock provider）

### Query 6: SimScore-based Similarity Join
- **类型**: SimScore Similarity Join
- **功能**: 演示 RDF 数据和 LLM 生成数据之间的语义相似度连接
- **特点**: 
  - 从数据库查询科学家名字（RDF 源）
  - 使用 GENOP 生成描述（GEN 源）
  - 通过 SimScore 进行相似度匹配连接
  - 支持阈值参数控制匹配精度
- **需要**: OpenRouter API Key（或使用 Mock provider）

### Query 7: Cartesian Product with GENOP
- **类型**: Cartesian Product
- **功能**: 演示当多个 BGP 没有共同变量时，GENOP 如何处理输入变量的笛卡尔积
- **特点**: 
  - BGP1: 查询科学家（绑定 ?scientist）
  - BGP2: 查询奖项（绑定 ?awardLabel）
  - 两个 BGP 没有共同变量 → 产生笛卡尔积
  - GENOP 使用两个变量作为输入，对每个组合生成描述
  - 如果 BGP1 有 3 个科学家，BGP2 有 3 个奖项，GENOP 会被调用 3×3=9 次
- **需要**: OpenRouter API Key（或使用 Mock provider）

### Query 8: GENOP before BGP
- **类型**: Pattern Order Independence
- **功能**: 演示 GENOP 出现在 BGP 之前，但使用 BGP 绑定的变量作为输入
- **特点**: 
  - GENOP 出现在查询的第一位，使用 ?scientist 作为输入变量
  - BGP 出现在 GENOP 之后，绑定 ?scientist
  - 演示 SPARQL 模式顺序的语义无关性（自然连接是可交换的）
  - 查询引擎应该能够处理变量依赖关系，无论模式顺序如何
  - 测试查询引擎是否正确处理变量依赖，即使 GENOP 在绑定变量的 BGP 之前
- **需要**: OpenRouter API Key（或使用 Mock provider）

## 配置 API Key

如果要使用真实的 LLM，需要设置环境变量：

```bash
export OPENROUTER_API_KEY="your-api-key-here"
```

然后运行相应的脚本。

## 使用 Mock Provider（测试用）

如果需要测试但不想配置 API key，可以修改对应的 Java 文件，在 `main` 方法中添加：

```java
GenSPARQL.setDefaultProvider("mock");
```

## 故障排除

如果遇到编译错误，确保项目已编译：

```bash
cd /Users/guaiguai/Documents/SPARQL/cursor/jena_llm
mvn compile -DskipTests
```

如果遇到文件找不到的错误，确保从项目根目录运行脚本。

