# GENSPARQL_Data - 细粒度查询归约分类

本文件夹包含对 FB15k-237+H 和 NELL995+H 数据集的细粒度查询分类。

## 目录结构

```
GENSPARQL_Data/
├── FB15k-237+H/
│   ├── 2p/
│   │   ├── pattern_01/    # 第1跳train, 第2跳test
│   │   ├── pattern_10/    # 第1跳test, 第2跳train
│   │   └── pattern_11/    # 两跳都是test
│   ├── 3p/
│   │   ├── pattern_001/   # r1:train, r2:train, r3:test
│   │   ├── pattern_010/   # r1:train, r2:test, r3:train
│   │   ├── pattern_100/   # r1:test, r2:train, r3:train
│   │   ├── pattern_011/   # r1:train, r2:test, r3:test
│   │   ├── pattern_110/   # r1:test, r2:test, r3:train
│   │   ├── pattern_101/   # r1:test, r2:train, r3:test
│   │   └── pattern_111/   # 三跳都是test
│   ├── 4p/
│   │   └── pattern_XXXX/  # 4位二进制模式
│   ├── 2i/
│   │   └── pattern_XX/    # 2位二进制模式
│   ├── 3i/
│   │   └── pattern_XXX/   # 3位二进制模式
│   ├── pi/
│   │   └── pattern_XXX/   # 对应 r1, r2, r3
│   ├── ip/
│   │   └── pattern_XXX/   # 对应 r1, r2, r3
│   └── up/
│       └── pattern_XXX/   # 对应 r1, r2, r3
└── NELL995+H/
    └── (同上结构)
```

## 模式编码说明

每个模式使用二进制编码，其中：
- `0` = 该边在训练图中已存在 (train)
- `1` = 该边需要预测 (test_only)

### 链式查询 (xp)

对于 **3p 查询**: `anchor --r1--> e1 --r2--> e2 --r3--> answer`

| 模式 | 含义 | 推理复杂度 |
|------|------|-----------|
| `001` | r1:train, r2:train, r3:test | 1跳推理 |
| `010` | r1:train, r2:test, r3:train | 1跳推理 |
| `100` | r1:test, r2:train, r3:train | 1跳推理 |
| `011` | r1:train, r2:test, r3:test | 2跳推理 |
| `110` | r1:test, r2:test, r3:train | 2跳推理 |
| `101` | r1:test, r2:train, r3:test | 2跳推理 |
| `111` | r1:test, r2:test, r3:test | 3跳推理（完全推理）|

### 交集查询 (xi)

对于 **2i 查询**: `(e1 --r1--> ?) ∩ (e2 --r2--> ?)`

| 模式 | 含义 |
|------|------|
| `01` | r1:train, r2:test |
| `10` | r1:test, r2:train |
| `11` | r1:test, r2:test |

### 混合查询 (pi/ip/up)

对于 **pi 查询**: `(e1 --r1--> ? --r2--> ?) ∩ (e2 --r3--> ?)`
- 模式的三位分别对应 r1, r2, r3

对于 **ip 查询**: `((e1 --r1--> ?) ∩ (e2 --r2--> ?)) --r3--> answer`
- 模式的三位分别对应 r1, r2, r3

## 每个模式文件夹内容

每个 `pattern_XXX/` 文件夹包含：

- `test-queries.pkl`: 查询集合 `{query_structure: set of queries}`
- `test-hard-answers.pkl`: 困难答案 `{query: set of answers}`
- `test-easy-answers.pkl`: 简单答案（用于过滤）

## 使用示例

```python
import pickle

# 加载 3p 查询中模式为 001 的数据
base_path = "GENSPARQL_Data/FB15k-237+H/3p/pattern_001"

with open(f"{base_path}/test-queries.pkl", "rb") as f:
    queries = pickle.load(f)

with open(f"{base_path}/test-hard-answers.pkl", "rb") as f:
    hard_answers = pickle.load(f)

# 查看统计
print(f"查询结构: {list(queries.keys())}")
for struct, qs in queries.items():
    print(f"查询数量: {len(qs)}")
    print(f"总答案数: {sum(len(hard_answers[q]) for q in qs)}")
```

## 生成脚本

数据由 `create_fine_grained_splits.py` 脚本生成。如需重新生成：

```bash
cd data/GENSPARQL_Data
python create_fine_grained_splits.py
```
