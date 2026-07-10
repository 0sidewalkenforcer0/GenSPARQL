#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
将 pkl 格式的查询转换为可读的 txt 格式

将实体和关系的 ID 转换回原始名称，并以人类可读的格式存储。
"""

import pickle
import os
import json
from collections import defaultdict


def load_pkl(filepath):
    """加载 pickle 文件"""
    with open(filepath, 'rb') as f:
        return pickle.load(f)


def format_query_2p(query, id2ent, id2rel):
    """格式化 2p 查询: anchor --r1--> ? --r2--> ?"""
    anchor = id2ent.get(query[0], f"entity_{query[0]}")
    r1 = id2rel.get(query[1][0], f"rel_{query[1][0]}")
    r2 = id2rel.get(query[1][1], f"rel_{query[1][1]}")
    return f"{anchor} --[{r1}]--> ? --[{r2}]--> ?"


def format_query_3p(query, id2ent, id2rel):
    """格式化 3p 查询: anchor --r1--> ? --r2--> ? --r3--> ?"""
    anchor = id2ent.get(query[0], f"entity_{query[0]}")
    r1 = id2rel.get(query[1][0], f"rel_{query[1][0]}")
    r2 = id2rel.get(query[1][1], f"rel_{query[1][1]}")
    r3 = id2rel.get(query[1][2], f"rel_{query[1][2]}")
    return f"{anchor} --[{r1}]--> ? --[{r2}]--> ? --[{r3}]--> ?"


def format_query_4p(query, id2ent, id2rel):
    """格式化 4p 查询"""
    anchor = id2ent.get(query[0], f"entity_{query[0]}")
    rels = [id2rel.get(r, f"rel_{r}") for r in query[1]]
    path = f"{anchor}"
    for r in rels:
        path += f" --[{r}]--> ?"
    return path


def format_query_2i(query, id2ent, id2rel):
    """格式化 2i 查询: (e1 --r1--> ?) ∩ (e2 --r2--> ?)"""
    e1 = id2ent.get(query[0][0], f"entity_{query[0][0]}")
    r1 = id2rel.get(query[0][1][0], f"rel_{query[0][1][0]}")
    e2 = id2ent.get(query[1][0], f"entity_{query[1][0]}")
    r2 = id2rel.get(query[1][1][0], f"rel_{query[1][1][0]}")
    return f"({e1} --[{r1}]--> ?) ∩ ({e2} --[{r2}]--> ?)"


def format_query_3i(query, id2ent, id2rel):
    """格式化 3i 查询: (e1 --r1--> ?) ∩ (e2 --r2--> ?) ∩ (e3 --r3--> ?)"""
    parts = []
    for i in range(3):
        e = id2ent.get(query[i][0], f"entity_{query[i][0]}")
        r = id2rel.get(query[i][1][0], f"rel_{query[i][1][0]}")
        parts.append(f"({e} --[{r}]--> ?)")
    return " ∩ ".join(parts)


def format_query_pi(query, id2ent, id2rel):
    """格式化 pi 查询: (e1 --r1--> ? --r2--> ?) ∩ (e2 --r3--> ?)"""
    e1 = id2ent.get(query[0][0], f"entity_{query[0][0]}")
    r1 = id2rel.get(query[0][1][0], f"rel_{query[0][1][0]}")
    r2 = id2rel.get(query[0][1][1], f"rel_{query[0][1][1]}")
    e2 = id2ent.get(query[1][0], f"entity_{query[1][0]}")
    r3 = id2rel.get(query[1][1][0], f"rel_{query[1][1][0]}")
    return f"({e1} --[{r1}]--> ? --[{r2}]--> ?) ∩ ({e2} --[{r3}]--> ?)"


def format_query_ip(query, id2ent, id2rel):
    """格式化 ip 查询: ((e1 --r1--> ?) ∩ (e2 --r2--> ?)) --r3--> ?"""
    e1 = id2ent.get(query[0][0][0], f"entity_{query[0][0][0]}")
    r1 = id2rel.get(query[0][0][1][0], f"rel_{query[0][0][1][0]}")
    e2 = id2ent.get(query[0][1][0], f"entity_{query[0][1][0]}")
    r2 = id2rel.get(query[0][1][1][0], f"rel_{query[0][1][1][0]}")
    r3 = id2rel.get(query[1][0], f"rel_{query[1][0]}")
    return f"(({e1} --[{r1}]--> ?) ∩ ({e2} --[{r2}]--> ?)) --[{r3}]--> ?"


def format_query_up(query, id2ent, id2rel):
    """格式化 up 查询: ((e1 --r1--> ?) ∪ (e2 --r2--> ?)) --r3--> ?"""
    e1 = id2ent.get(query[0][0][0], f"entity_{query[0][0][0]}")
    r1 = id2rel.get(query[0][0][1][0], f"rel_{query[0][0][1][0]}")
    e2 = id2ent.get(query[0][1][0], f"entity_{query[0][1][0]}")
    r2 = id2rel.get(query[0][1][1][0], f"rel_{query[0][1][1][0]}")
    r3 = id2rel.get(query[1][0], f"rel_{query[1][0]}")
    return f"(({e1} --[{r1}]--> ?) ∪ ({e2} --[{r2}]--> ?)) --[{r3}]--> ?"


def format_answers(answers, id2ent):
    """格式化答案列表"""
    return [id2ent.get(a, f"entity_{a}") for a in answers]


def get_query_formatter(query_type):
    """获取查询格式化函数"""
    formatters = {
        '2p': format_query_2p,
        '3p': format_query_3p,
        '4p': format_query_4p,
        '2i': format_query_2i,
        '3i': format_query_3i,
        'pi': format_query_pi,
        'ip': format_query_ip,
        'up': format_query_up,
    }
    return formatters.get(query_type)


def convert_pattern_to_readable(dataset_path, gensparql_path, output_path):
    """将一个数据集的所有模式转换为可读格式"""
    
    # 加载映射
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    id2rel = load_pkl(os.path.join(dataset_path, 'id2rel.pkl'))
    
    print(f"  Loaded mappings: {len(id2ent)} entities, {len(id2rel)} relations")
    
    # 遍历所有查询类型
    query_types = ['2p', '3p', '4p', '2i', '3i', 'pi', 'ip', 'up']
    
    for query_type in query_types:
        query_type_path = os.path.join(gensparql_path, query_type)
        if not os.path.exists(query_type_path):
            continue
        
        print(f"  Processing {query_type}...")
        formatter = get_query_formatter(query_type)
        if formatter is None:
            continue
        
        # 遍历所有模式
        for pattern_dir in os.listdir(query_type_path):
            if not pattern_dir.startswith('pattern_'):
                continue
            
            pattern_path = os.path.join(query_type_path, pattern_dir)
            if not os.path.isdir(pattern_path):
                continue
            
            pattern = pattern_dir.replace('pattern_', '')
            
            # 加载数据
            queries_file = os.path.join(pattern_path, 'test-queries.pkl')
            hard_answers_file = os.path.join(pattern_path, 'test-hard-answers.pkl')
            
            if not os.path.exists(queries_file) or not os.path.exists(hard_answers_file):
                continue
            
            queries_dict = load_pkl(queries_file)
            hard_answers = load_pkl(hard_answers_file)
            
            # 创建输出目录
            output_pattern_path = os.path.join(output_path, query_type, pattern_dir)
            os.makedirs(output_pattern_path, exist_ok=True)
            
            # 转换并保存
            all_qa_pairs = []
            
            for query_structure, queries in queries_dict.items():
                for query in queries:
                    formatted_query = formatter(query, id2ent, id2rel)
                    answers = hard_answers.get(query, set())
                    formatted_answers = format_answers(answers, id2ent)
                    
                    qa_pair = {
                        'query_raw': str(query),
                        'query_readable': formatted_query,
                        'pattern': pattern,
                        'num_answers': len(answers),
                        'answers': formatted_answers
                    }
                    all_qa_pairs.append(qa_pair)
            
            # 保存为 txt 格式（人类可读）
            txt_file = os.path.join(output_pattern_path, 'queries_readable.txt')
            with open(txt_file, 'w', encoding='utf-8') as f:
                f.write(f"Query Type: {query_type}\n")
                f.write(f"Pattern: {pattern}\n")
                f.write(f"Pattern Meaning: {get_pattern_meaning(query_type, pattern)}\n")
                f.write(f"Total Queries: {len(all_qa_pairs)}\n")
                f.write(f"Total Answers: {sum(qa['num_answers'] for qa in all_qa_pairs)}\n")
                f.write("=" * 80 + "\n\n")
                
                for i, qa in enumerate(all_qa_pairs[:100], 1):  # 只保存前100个示例
                    f.write(f"[Query {i}]\n")
                    f.write(f"  Query: {qa['query_readable']}\n")
                    f.write(f"  Answers ({qa['num_answers']}): ")
                    if qa['num_answers'] <= 10:
                        f.write(", ".join(qa['answers']) + "\n")
                    else:
                        f.write(", ".join(qa['answers'][:10]) + f", ... (+{qa['num_answers']-10} more)\n")
                    f.write("\n")
                
                if len(all_qa_pairs) > 100:
                    f.write(f"\n... and {len(all_qa_pairs) - 100} more queries (see JSON file for complete data)\n")
            
            # 保存为 JSON 格式（完整数据，可程序处理）
            json_file = os.path.join(output_pattern_path, 'queries_readable.json')
            with open(json_file, 'w', encoding='utf-8') as f:
                json.dump({
                    'query_type': query_type,
                    'pattern': pattern,
                    'pattern_meaning': get_pattern_meaning(query_type, pattern),
                    'total_queries': len(all_qa_pairs),
                    'total_answers': sum(qa['num_answers'] for qa in all_qa_pairs),
                    'queries': all_qa_pairs
                }, f, ensure_ascii=False, indent=2)
            
            print(f"    Saved {pattern}: {len(all_qa_pairs)} queries")


def get_pattern_meaning(query_type, pattern):
    """获取模式的含义描述"""
    if query_type in ['2p', '3p', '4p']:
        edges = []
        for i, bit in enumerate(pattern):
            edge_type = "test (需预测)" if bit == '1' else "train (已知)"
            edges.append(f"r{i+1}: {edge_type}")
        return "; ".join(edges)
    
    elif query_type in ['2i', '3i']:
        branches = []
        for i, bit in enumerate(pattern):
            branch_type = "test (需预测)" if bit == '1' else "train (已知)"
            branches.append(f"branch{i+1}: {branch_type}")
        return "; ".join(branches)
    
    elif query_type == 'pi':
        # pi: (e1 --r1--> ? --r2--> ?) ∩ (e2 --r3--> ?)
        meanings = []
        for i, (name, bit) in enumerate(zip(['r1 (2p部分第1跳)', 'r2 (2p部分第2跳)', 'r3 (1p部分)'], pattern)):
            edge_type = "test" if bit == '1' else "train"
            meanings.append(f"{name}: {edge_type}")
        return "; ".join(meanings)
    
    elif query_type in ['ip', 'up']:
        # ip/up: ((e1 --r1--> ?) ∩/∪ (e2 --r2--> ?)) --r3--> ?
        meanings = []
        names = ['r1 (交/并集左分支)', 'r2 (交/并集右分支)', 'r3 (最后一跳)']
        for i, (name, bit) in enumerate(zip(names, pattern)):
            edge_type = "test" if bit == '1' else "train"
            meanings.append(f"{name}: {edge_type}")
        return "; ".join(meanings)
    
    return pattern


def main():
    base_path = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.dirname(base_path)
    
    datasets = ['FB15k-237+H', 'NELL995+H']
    
    for dataset in datasets:
        dataset_path = os.path.join(data_path, dataset)
        gensparql_path = os.path.join(base_path, dataset)
        output_path = os.path.join(base_path, dataset)  # 输出到同一目录
        
        if not os.path.exists(dataset_path) or not os.path.exists(gensparql_path):
            print(f"Skipping {dataset}: paths not found")
            continue
        
        print(f"\n{'='*60}")
        print(f"Converting {dataset} to readable format")
        print(f"{'='*60}")
        
        convert_pattern_to_readable(dataset_path, gensparql_path, output_path)
    
    print(f"\n{'='*60}")
    print("Done! Readable files saved as:")
    print("  - queries_readable.txt (human readable, first 100 queries)")
    print("  - queries_readable.json (complete data, machine readable)")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
