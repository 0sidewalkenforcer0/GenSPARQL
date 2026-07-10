#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
创建细粒度的查询归约分类

对于每个查询类型（如3p），不仅按归约层级（1p/2p/3p）分类，
还要进一步按照具体的边模式（如001, 010, 100等）分类。

边模式说明：
- 0 = 该边在训练图中已存在 (train)
- 1 = 该边需要预测 (test_only)

例如 3p 查询: anchor --r1--> e1 --r2--> e2 --r3--> answer
- 001: r1已知, r2已知, r3需预测
- 010: r1已知, r2需预测, r3已知
- 100: r1需预测, r2已知, r3已知
- 011: r1已知, r2需预测, r3需预测
- 110: r1需预测, r2需预测, r3已知
- 101: r1需预测, r2已知, r3需预测
- 111: r1需预测, r2需预测, r3需预测
"""

import pickle
import os
from collections import defaultdict
from itertools import product
import sys

# 添加父目录到路径以导入必要的模块
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..'))


def load_pkl(filepath):
    """加载 pickle 文件"""
    with open(filepath, 'rb') as f:
        return pickle.load(f)


def save_pkl(data, filepath):
    """保存 pickle 文件"""
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, 'wb') as f:
        pickle.dump(data, f)


def load_kg_data(dataset_path):
    """加载知识图谱数据"""
    print(f"Loading KG data from {dataset_path}...")
    
    # 加载映射
    ent2id = load_pkl(os.path.join(dataset_path, 'ent2id.pkl'))
    rel2id = load_pkl(os.path.join(dataset_path, 'rel2id.pkl'))
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    id2rel = load_pkl(os.path.join(dataset_path, 'id2rel.pkl'))
    
    print(f"  Entities: {len(ent2id)}, Relations: {len(rel2id)}")
    
    # 构建训练图和测试图
    train_ent_out = defaultdict(lambda: defaultdict(set))
    test_only_ent_out = defaultdict(lambda: defaultdict(set))
    all_ent_out = defaultdict(lambda: defaultdict(set))
    
    train_triples = set()
    
    # 读取训练三元组 (格式: h_id \t r_id \t t_id)
    train_file = os.path.join(dataset_path, 'train.txt')
    if os.path.exists(train_file):
        with open(train_file, 'r') as f:
            for line in f:
                parts = line.strip().split('\t')
                if len(parts) == 3:
                    h_id, r_id, t_id = int(parts[0]), int(parts[1]), int(parts[2])
                    train_ent_out[h_id][r_id].add(t_id)
                    all_ent_out[h_id][r_id].add(t_id)
                    train_triples.add((h_id, r_id, t_id))
    
    print(f"  Train triples: {len(train_triples)}")
    
    # 读取测试三元组
    test_file = os.path.join(dataset_path, 'test.txt')
    test_count = 0
    if os.path.exists(test_file):
        with open(test_file, 'r') as f:
            for line in f:
                parts = line.strip().split('\t')
                if len(parts) == 3:
                    h_id, r_id, t_id = int(parts[0]), int(parts[1]), int(parts[2])
                    all_ent_out[h_id][r_id].add(t_id)
                    # 只添加到 test_only（不在 train 中的边）
                    if (h_id, r_id, t_id) not in train_triples:
                        test_only_ent_out[h_id][r_id].add(t_id)
                        test_count += 1
    
    print(f"  Test-only triples: {test_count}")
    
    # 读取验证三元组
    valid_file = os.path.join(dataset_path, 'valid.txt')
    if os.path.exists(valid_file):
        with open(valid_file, 'r') as f:
            for line in f:
                parts = line.strip().split('\t')
                if len(parts) == 3:
                    h_id, r_id, t_id = int(parts[0]), int(parts[1]), int(parts[2])
                    all_ent_out[h_id][r_id].add(t_id)
    
    print(f"  Train entities with edges: {len(train_ent_out)}")
    print(f"  Test-only entities with edges: {len(test_only_ent_out)}")
    
    return train_ent_out, test_only_ent_out, all_ent_out


def compute_answers_2p(entity, rels, ent_out1, ent_out2):
    """计算 2p 查询的答案"""
    answers = set()
    for mid in ent_out1[entity][rels[0]]:
        answers.update(ent_out2[mid][rels[1]])
    return answers


def compute_answers_3p(entity, rels, ent_out1, ent_out2, ent_out3):
    """计算 3p 查询的答案"""
    answers = set()
    for mid1 in ent_out1[entity][rels[0]]:
        for mid2 in ent_out2[mid1][rels[1]]:
            answers.update(ent_out3[mid2][rels[2]])
    return answers


def compute_answers_4p(entity, rels, ent_out1, ent_out2, ent_out3, ent_out4):
    """计算 4p 查询的答案"""
    answers = set()
    for mid1 in ent_out1[entity][rels[0]]:
        for mid2 in ent_out2[mid1][rels[1]]:
            for mid3 in ent_out3[mid2][rels[2]]:
                answers.update(ent_out4[mid3][rels[3]])
    return answers


def classify_2p_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out, all_ent_out):
    """
    对 2p 查询的答案按边模式分类
    模式: 01, 10, 11
    """
    entity = query[0]
    rel1 = query[1][0]
    rel2 = query[1][1]
    
    pattern_answers = defaultdict(set)
    
    for ans in hard_answers:
        found_pattern = None
        
        # 检查 01: train + test_only
        answers_01 = compute_answers_2p(entity, [rel1, rel2], train_ent_out, test_only_ent_out)
        if ans in answers_01:
            found_pattern = '01'
        
        # 检查 10: test_only + train
        if found_pattern is None:
            answers_10 = compute_answers_2p(entity, [rel1, rel2], test_only_ent_out, train_ent_out)
            if ans in answers_10:
                found_pattern = '10'
        
        # 检查 11: test_only + test_only
        if found_pattern is None:
            answers_11 = compute_answers_2p(entity, [rel1, rel2], test_only_ent_out, test_only_ent_out)
            if ans in answers_11:
                found_pattern = '11'
        
        if found_pattern:
            pattern_answers[found_pattern].add(ans)
    
    return pattern_answers


def classify_3p_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out, all_ent_out):
    """
    对 3p 查询的答案按边模式分类
    模式: 001, 010, 100, 011, 110, 101, 111
    """
    entity = query[0]
    rel1 = query[1][0]
    rel2 = query[1][1]
    rel3 = query[1][2]
    
    pattern_answers = defaultdict(set)
    
    # 预计算所有模式的答案集
    patterns = {
        '001': compute_answers_3p(entity, [rel1, rel2, rel3], train_ent_out, train_ent_out, test_only_ent_out),
        '010': compute_answers_3p(entity, [rel1, rel2, rel3], train_ent_out, test_only_ent_out, train_ent_out),
        '100': compute_answers_3p(entity, [rel1, rel2, rel3], test_only_ent_out, train_ent_out, train_ent_out),
        '011': compute_answers_3p(entity, [rel1, rel2, rel3], train_ent_out, test_only_ent_out, test_only_ent_out),
        '110': compute_answers_3p(entity, [rel1, rel2, rel3], test_only_ent_out, test_only_ent_out, train_ent_out),
        '101': compute_answers_3p(entity, [rel1, rel2, rel3], test_only_ent_out, train_ent_out, test_only_ent_out),
        '111': compute_answers_3p(entity, [rel1, rel2, rel3], test_only_ent_out, test_only_ent_out, test_only_ent_out),
    }
    
    # 按优先级分配（更少的1优先）
    priority_order = ['001', '010', '100', '011', '110', '101', '111']
    assigned = set()
    
    for pattern in priority_order:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def classify_4p_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out, all_ent_out):
    """
    对 4p 查询的答案按边模式分类
    """
    entity = query[0]
    rels = [query[1][0], query[1][1], query[1][2], query[1][3]]
    
    pattern_answers = defaultdict(set)
    
    # 生成所有 4 位二进制模式
    all_patterns = [''.join(map(str, bits)) for bits in product([0, 1], repeat=4)]
    all_patterns = [p for p in all_patterns if p != '0000']  # 排除全0（在训练集中已存在）
    
    # 按1的数量排序（优先分配给更简单的模式）
    all_patterns.sort(key=lambda x: x.count('1'))
    
    def get_ent_out(bit):
        return test_only_ent_out if bit == '1' else train_ent_out
    
    # 预计算所有模式的答案集
    patterns = {}
    for pattern in all_patterns:
        patterns[pattern] = compute_answers_4p(
            entity, rels,
            get_ent_out(pattern[0]),
            get_ent_out(pattern[1]),
            get_ent_out(pattern[2]),
            get_ent_out(pattern[3])
        )
    
    # 按优先级分配
    assigned = set()
    for pattern in all_patterns:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def classify_2i_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out):
    """
    对 2i 查询的答案按边模式分类
    """
    entity1 = query[0][0]
    rel1 = query[0][1][0]
    entity2 = query[1][0]
    rel2 = query[1][1][0]
    
    pattern_answers = defaultdict(set)
    
    patterns = {
        '01': train_ent_out[entity1][rel1] & test_only_ent_out[entity2][rel2],
        '10': test_only_ent_out[entity1][rel1] & train_ent_out[entity2][rel2],
        '11': test_only_ent_out[entity1][rel1] & test_only_ent_out[entity2][rel2],
    }
    
    priority_order = ['01', '10', '11']
    assigned = set()
    
    for pattern in priority_order:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def classify_3i_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out):
    """
    对 3i 查询的答案按边模式分类
    """
    entity1 = query[0][0]
    rel1 = query[0][1][0]
    entity2 = query[1][0]
    rel2 = query[1][1][0]
    entity3 = query[2][0]
    rel3 = query[2][1][0]
    
    pattern_answers = defaultdict(set)
    
    def get_ans(e, r, is_test):
        return test_only_ent_out[e][r] if is_test else train_ent_out[e][r]
    
    all_patterns = [''.join(map(str, bits)) for bits in product([0, 1], repeat=3)]
    all_patterns = [p for p in all_patterns if p != '000']
    all_patterns.sort(key=lambda x: x.count('1'))
    
    patterns = {}
    for pattern in all_patterns:
        ans1 = get_ans(entity1, rel1, pattern[0] == '1')
        ans2 = get_ans(entity2, rel2, pattern[1] == '1')
        ans3 = get_ans(entity3, rel3, pattern[2] == '1')
        patterns[pattern] = ans1 & ans2 & ans3
    
    assigned = set()
    for pattern in all_patterns:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def classify_pi_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out, all_ent_out):
    """
    对 pi 查询的答案按边模式分类
    pi 结构: (e1 --r1--> ? --r2--> ?) ∩ (e2 --r3--> ?)
    模式: 位置分别对应 r1, r2, r3
    """
    entity1 = query[0][0]
    rel1 = query[0][1][0]
    rel2 = query[0][1][1]
    entity2 = query[1][0]
    rel3 = query[1][1][0]
    
    pattern_answers = defaultdict(set)
    
    def get_ent_out(bit):
        return test_only_ent_out if bit == '1' else train_ent_out
    
    all_patterns = [''.join(map(str, bits)) for bits in product([0, 1], repeat=3)]
    all_patterns = [p for p in all_patterns if p != '000']
    all_patterns.sort(key=lambda x: x.count('1'))
    
    patterns = {}
    for pattern in all_patterns:
        subquery_2p = compute_answers_2p(entity1, [rel1, rel2], get_ent_out(pattern[0]), get_ent_out(pattern[1]))
        subquery_1p = get_ent_out(pattern[2])[entity2][rel3]
        patterns[pattern] = subquery_2p & subquery_1p
    
    assigned = set()
    for pattern in all_patterns:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def classify_ip_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out, all_ent_out):
    """
    对 ip 查询的答案按边模式分类
    ip 结构: ((e1 --r1--> ?) ∩ (e2 --r2--> ?)) --r3--> answer
    模式: 位置分别对应 r1, r2, r3
    """
    entity1 = query[0][0][0]
    rel1 = query[0][0][1][0]
    entity2 = query[0][1][0]
    rel2 = query[0][1][1][0]
    rel3 = query[1][0]
    
    pattern_answers = defaultdict(set)
    
    def get_ent_out(bit):
        return test_only_ent_out if bit == '1' else train_ent_out
    
    all_patterns = [''.join(map(str, bits)) for bits in product([0, 1], repeat=3)]
    all_patterns = [p for p in all_patterns if p != '000']
    all_patterns.sort(key=lambda x: x.count('1'))
    
    patterns = {}
    for pattern in all_patterns:
        # 先计算交集
        ans1 = get_ent_out(pattern[0])[entity1][rel1]
        ans2 = get_ent_out(pattern[1])[entity2][rel2]
        intersection = ans1 & ans2
        # 再计算最后一跳
        final_ans = set()
        for mid in intersection:
            final_ans.update(get_ent_out(pattern[2])[mid][rel3])
        patterns[pattern] = final_ans
    
    assigned = set()
    for pattern in all_patterns:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def classify_up_by_pattern(query, hard_answers, train_ent_out, test_only_ent_out, all_ent_out):
    """
    对 up 查询的答案按边模式分类
    up 结构: ((e1 --r1--> ?) ∪ (e2 --r2--> ?)) --r3--> answer
    模式: 位置分别对应 r1, r2, r3
    """
    entity1 = query[0][0][0]
    rel1 = query[0][0][1][0]
    entity2 = query[0][1][0]
    rel2 = query[0][1][1][0]
    rel3 = query[1][0]
    
    pattern_answers = defaultdict(set)
    
    def get_ent_out(bit):
        return test_only_ent_out if bit == '1' else train_ent_out
    
    all_patterns = [''.join(map(str, bits)) for bits in product([0, 1], repeat=3)]
    all_patterns = [p for p in all_patterns if p != '000']
    all_patterns.sort(key=lambda x: x.count('1'))
    
    patterns = {}
    for pattern in all_patterns:
        # 先计算并集
        ans1 = get_ent_out(pattern[0])[entity1][rel1]
        ans2 = get_ent_out(pattern[1])[entity2][rel2]
        union = ans1 | ans2
        # 再计算最后一跳
        final_ans = set()
        for mid in union:
            final_ans.update(get_ent_out(pattern[2])[mid][rel3])
        patterns[pattern] = final_ans
    
    assigned = set()
    for pattern in all_patterns:
        for ans in hard_answers:
            if ans not in assigned and ans in patterns[pattern]:
                pattern_answers[pattern].add(ans)
                assigned.add(ans)
    
    return pattern_answers


def get_query_structure_name(query_type):
    """获取查询结构的元组形式"""
    structures = {
        '2p': ('e', ('r', 'r')),
        '3p': ('e', ('r', 'r', 'r')),
        '4p': ('e', ('r', 'r', 'r', 'r')),
        '2i': (('e', ('r',)), ('e', ('r',))),
        '3i': (('e', ('r',)), ('e', ('r',)), ('e', ('r',))),
        '4i': (('e', ('r',)), ('e', ('r',)), ('e', ('r',)), ('e', ('r',))),
        'pi': (('e', ('r', 'r')), ('e', ('r',))),
        'ip': ((('e', ('r',)), ('e', ('r',))), ('r',)),
        'up': ((('e', ('r',)), ('e', ('r',))), ('r',)),
    }
    return structures.get(query_type)


def get_pattern_description(query_type, pattern):
    """获取模式的描述"""
    descriptions = {
        '2p': {
            '01': 'r1:train, r2:test',
            '10': 'r1:test, r2:train',
            '11': 'r1:test, r2:test',
        },
        '3p': {
            '001': 'r1:train, r2:train, r3:test',
            '010': 'r1:train, r2:test, r3:train',
            '100': 'r1:test, r2:train, r3:train',
            '011': 'r1:train, r2:test, r3:test',
            '110': 'r1:test, r2:test, r3:train',
            '101': 'r1:test, r2:train, r3:test',
            '111': 'r1:test, r2:test, r3:test',
        },
    }
    return descriptions.get(query_type, {}).get(pattern, pattern)


def process_query_type(query_type, dataset_path, output_path, train_ent_out, test_only_ent_out, all_ent_out):
    """处理单个查询类型"""
    reduction_path = os.path.join(dataset_path, 'test-query-reduction', query_type)
    
    if not os.path.exists(reduction_path):
        print(f"  Skipping {query_type}: directory not found")
        return
    
    # 加载原始查询和答案
    queries_file = os.path.join(dataset_path, 'test-queries.pkl')
    hard_answers_file = os.path.join(dataset_path, 'test-hard-answers.pkl')
    easy_answers_file = os.path.join(dataset_path, 'test-easy-answers.pkl')
    
    if not os.path.exists(queries_file):
        print(f"  Skipping {query_type}: queries file not found")
        return
    
    all_queries = load_pkl(queries_file)
    all_hard_answers = load_pkl(hard_answers_file)
    all_easy_answers = load_pkl(easy_answers_file)
    
    query_structure = get_query_structure_name(query_type)
    if query_structure not in all_queries:
        print(f"  Skipping {query_type}: query structure not found in queries")
        return
    
    queries = all_queries[query_structure]
    print(f"  Processing {query_type}: {len(queries)} queries")
    
    # 选择分类函数
    classify_func = {
        '2p': classify_2p_by_pattern,
        '3p': classify_3p_by_pattern,
        '4p': classify_4p_by_pattern,
        '2i': classify_2i_by_pattern,
        '3i': classify_3i_by_pattern,
        'pi': classify_pi_by_pattern,
        'ip': classify_ip_by_pattern,
        'up': classify_up_by_pattern,
    }.get(query_type)
    
    if classify_func is None:
        print(f"  Skipping {query_type}: no classification function")
        return
    
    # 按模式分类
    pattern_queries = defaultdict(set)  # pattern -> set of queries
    pattern_hard_answers = defaultdict(dict)  # pattern -> {query: answers}
    pattern_easy_answers = defaultdict(dict)  # pattern -> {query: answers}
    
    for query in queries:
        hard_ans = all_hard_answers.get(query, set())
        easy_ans = all_easy_answers.get(query, set())
        
        if len(hard_ans) == 0:
            continue
        
        # 分类答案
        if query_type in ['2i', '3i']:
            pattern_answers = classify_func(query, hard_ans, train_ent_out, test_only_ent_out)
        else:
            pattern_answers = classify_func(query, hard_ans, train_ent_out, test_only_ent_out, all_ent_out)
        
        # 将结果按模式保存
        for pattern, answers in pattern_answers.items():
            if len(answers) > 0:
                pattern_queries[pattern].add(query)
                pattern_hard_answers[pattern][query] = answers
                pattern_easy_answers[pattern][query] = easy_ans
    
    # 保存结果
    query_output_path = os.path.join(output_path, query_type)
    os.makedirs(query_output_path, exist_ok=True)
    stats = []
    
    for pattern in sorted(pattern_queries.keys(), key=lambda x: (x.count('1'), x)):
        pattern_path = os.path.join(query_output_path, f"pattern_{pattern}")
        os.makedirs(pattern_path, exist_ok=True)
        
        # 保存查询
        queries_dict = {query_structure: pattern_queries[pattern]}
        save_pkl(queries_dict, os.path.join(pattern_path, 'test-queries.pkl'))
        
        # 保存答案
        save_pkl(pattern_hard_answers[pattern], os.path.join(pattern_path, 'test-hard-answers.pkl'))
        save_pkl(pattern_easy_answers[pattern], os.path.join(pattern_path, 'test-easy-answers.pkl'))
        
        n_queries = len(pattern_queries[pattern])
        n_answers = sum(len(ans) for ans in pattern_hard_answers[pattern].values())
        stats.append(f"    {pattern}: {n_queries} queries, {n_answers} answers")
        print(f"    Saved pattern {pattern}: {n_queries} queries, {n_answers} answers")
    
    # 保存统计信息
    with open(os.path.join(query_output_path, 'stats.txt'), 'w') as f:
        f.write(f"Query type: {query_type}\n")
        f.write(f"Query structure: {query_structure}\n\n")
        f.write("Pattern statistics:\n")
        for s in stats:
            f.write(s + '\n')


def main():
    base_path = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.dirname(base_path)
    
    datasets = ['FB15k-237+H', 'NELL995+H']
    query_types = ['2p', '3p', '4p', '2i', '3i', 'pi', 'ip', 'up']
    
    for dataset in datasets:
        dataset_path = os.path.join(data_path, dataset)
        output_path = os.path.join(base_path, dataset)
        
        if not os.path.exists(dataset_path):
            print(f"Dataset not found: {dataset_path}")
            continue
        
        print(f"\n{'='*60}")
        print(f"Processing dataset: {dataset}")
        print(f"{'='*60}")
        
        # 加载知识图谱数据
        train_ent_out, test_only_ent_out, all_ent_out = load_kg_data(dataset_path)
        
        # 处理每个查询类型
        for query_type in query_types:
            print(f"\n--- {query_type} ---")
            process_query_type(query_type, dataset_path, output_path, 
                             train_ent_out, test_only_ent_out, all_ent_out)
    
    print(f"\n{'='*60}")
    print("Done! Results saved to GENSPARQL_Data/")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
