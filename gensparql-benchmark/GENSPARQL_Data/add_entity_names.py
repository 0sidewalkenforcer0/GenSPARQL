#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
下载并创建 FB15k-237 的 MID 到人类可读名称的映射

Freebase MID (Machine ID) 如 /m/027rn 是 Freebase 的内部标识符，
需要转换为人类可读的名称。

此脚本会:
1. 尝试从公开资源下载 FB15k-237 的实体名称映射
2. 创建 mid2name.pkl 映射文件
3. 更新 queries_readable.txt 和 queries_readable.json
"""

import pickle
import os
import json
import urllib.request
import gzip
from collections import defaultdict


def load_pkl(filepath):
    with open(filepath, 'rb') as f:
        return pickle.load(f)


def save_pkl(data, filepath):
    with open(filepath, 'wb') as f:
        pickle.dump(data, f)


def download_fb15k237_entity_names():
    """
    尝试多种方式获取 FB15k-237 实体名称
    """
    # 首先尝试从关系中提取可读信息
    # FB15k-237 的关系本身就是可读的，如 /film/film/genre
    # 我们可以尝试从关系路径推断实体类型
    
    # 方法1: 尝试下载预先准备好的映射文件
    urls = [
        # 常见的 FB15k-237 实体名称资源
        "https://raw.githubusercontent.com/TimDettmers/ConvE/master/data/FB15k-237/entity_names.txt",
        "https://dl.fbaipublicfiles.com/starspace/fb15k-237/entity_names.txt",
    ]
    
    for url in urls:
        try:
            print(f"Trying to download from {url}...")
            response = urllib.request.urlopen(url, timeout=10)
            content = response.read().decode('utf-8')
            
            mid2name = {}
            for line in content.strip().split('\n'):
                parts = line.strip().split('\t')
                if len(parts) >= 2:
                    mid2name[parts[0]] = parts[1]
            
            if len(mid2name) > 0:
                print(f"  Downloaded {len(mid2name)} entity names")
                return mid2name
        except Exception as e:
            print(f"  Failed: {e}")
    
    return None


def extract_name_from_relation_context(mid, id2ent, relations_used):
    """
    从使用该实体的关系上下文推断名称
    例如：如果实体在 /film/film/genre 关系中出现，可能是电影或类型
    """
    # 这个方法需要更多上下文，暂时返回 None
    return None


def create_mid2name_from_wikidata():
    """
    尝试使用 Wikidata 的 Freebase ID 映射
    Wikidata 保留了许多 Freebase ID 的映射
    """
    # 这需要 SPARQL 查询，太慢了，暂不实现
    pass


def create_readable_mid(mid):
    """
    为 MID 创建一个相对可读的表示
    从 MID 本身无法得到含义，但可以缩短显示
    """
    # /m/027rn -> m_027rn
    if mid.startswith('/m/'):
        return f"fb:{mid[3:]}"
    return mid


def load_fb15k237_entity_strings():
    """
    加载预先存在的 FB15k-237 实体字符串映射
    这些通常来自原始 Freebase dump 或其他来源
    """
    # 尝试查找本地文件
    possible_paths = [
        "/Users/jingcheng/Documents/code/is-cqa-complex/data/FB15k-237+H/entity_strings.txt",
        "/Users/jingcheng/Documents/code/is-cqa-complex/data/entity2text.txt",
        "/Users/jingcheng/Documents/code/is-cqa-complex/data/mid2name.txt",
    ]
    
    for path in possible_paths:
        if os.path.exists(path):
            print(f"Found local mapping at {path}")
            mid2name = {}
            with open(path, 'r', encoding='utf-8') as f:
                for line in f:
                    parts = line.strip().split('\t')
                    if len(parts) >= 2:
                        mid2name[parts[0]] = parts[1]
            return mid2name
    
    return None


def download_and_create_mapping(dataset_path):
    """
    下载并创建 FB15k-237 的 MID 到名称映射
    """
    print("Attempting to create FB15k-237 MID to name mapping...")
    
    # 加载现有实体
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    all_mids = set(id2ent.values())
    print(f"Total entities in dataset: {len(all_mids)}")
    
    # 尝试多种方法获取映射
    mid2name = None
    
    # 方法1: 尝试本地文件
    mid2name = load_fb15k237_entity_strings()
    
    # 方法2: 尝试在线下载
    if mid2name is None:
        mid2name = download_fb15k237_entity_names()
    
    # 方法3: 如果都失败，尝试使用 Freebase 备份数据
    if mid2name is None:
        print("Could not find entity name mappings.")
        print("Will try to create a mapping from available sources...")
        
        # 尝试从 GitHub 上的 FB15k-237 相关项目获取
        try:
            # KG-BERT 项目有实体描述
            url = "https://raw.githubusercontent.com/yao8839836/KG-BERT/master/data/FB15k-237/entity2text.txt"
            print(f"Trying KG-BERT entity2text from {url}...")
            response = urllib.request.urlopen(url, timeout=30)
            content = response.read().decode('utf-8')
            
            mid2name = {}
            for line in content.strip().split('\n'):
                parts = line.strip().split('\t')
                if len(parts) >= 2:
                    mid2name[parts[0]] = parts[1]
            
            print(f"  Downloaded {len(mid2name)} entity descriptions from KG-BERT")
        except Exception as e:
            print(f"  Failed: {e}")
    
    if mid2name is None:
        # 最后方案：为每个 MID 创建格式化的占位符
        print("Creating placeholder names from MID structure...")
        mid2name = {}
        for mid in all_mids:
            mid2name[mid] = create_readable_mid(mid)
    
    # 统计覆盖率
    covered = sum(1 for mid in all_mids if mid in mid2name and mid2name[mid] != create_readable_mid(mid))
    print(f"Coverage: {covered}/{len(all_mids)} ({100*covered/len(all_mids):.1f}%)")
    
    # 保存映射
    mapping_file = os.path.join(dataset_path, 'mid2name.pkl')
    save_pkl(mid2name, mapping_file)
    print(f"Saved mapping to {mapping_file}")
    
    return mid2name


def update_readable_files_with_names(gensparql_path, mid2name, id2ent, id2rel):
    """
    使用人类可读的名称更新 queries_readable 文件
    """
    
    def get_readable_name(mid):
        """获取 MID 的可读名称"""
        if mid in mid2name:
            name = mid2name[mid]
            # 如果名称太长，截断
            if len(name) > 60:
                name = name[:57] + "..."
            return name
        return mid
    
    def get_readable_rel(rel):
        """获取关系的可读名称"""
        # 关系已经是可读的，如 +/film/film/genre
        # 可以进一步简化
        if rel.startswith('+/') or rel.startswith('-/'):
            direction = '→' if rel.startswith('+/') else '←'
            rel_name = rel[2:].replace('/', '.').replace('_', ' ')
            # 只取最后两级
            parts = rel_name.split('.')
            if len(parts) > 2:
                rel_name = '.'.join(parts[-2:])
            return f"{direction}{rel_name}"
        return rel
    
    # 遍历所有查询类型和模式
    query_types = ['2p', '3p', '4p', '2i', '3i', 'pi', 'ip', 'up']
    
    for query_type in query_types:
        query_type_path = os.path.join(gensparql_path, query_type)
        if not os.path.exists(query_type_path):
            continue
        
        print(f"  Updating {query_type}...")
        
        for pattern_dir in os.listdir(query_type_path):
            if not pattern_dir.startswith('pattern_'):
                continue
            
            pattern_path = os.path.join(query_type_path, pattern_dir)
            json_file = os.path.join(pattern_path, 'queries_readable.json')
            
            if not os.path.exists(json_file):
                continue
            
            # 读取 JSON
            with open(json_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            # 更新每个查询
            for query_item in data['queries']:
                # 解析原始查询以获取实体和关系 ID
                old_readable = query_item['query_readable']
                
                # 替换实体名称
                new_readable = old_readable
                for mid in mid2name:
                    if mid in new_readable:
                        readable_name = get_readable_name(mid)
                        new_readable = new_readable.replace(mid, readable_name)
                
                # 简化关系名称
                # 找到所有关系模式 --[xxx]-->
                import re
                rel_pattern = r'--\[([^\]]+)\]-->'
                
                def replace_rel(match):
                    rel = match.group(1)
                    return f'--[{get_readable_rel(rel)}]-->'
                
                new_readable = re.sub(rel_pattern, replace_rel, new_readable)
                query_item['query_readable_with_names'] = new_readable
                
                # 更新答案为可读名称
                query_item['answers_readable'] = [get_readable_name(a) for a in query_item['answers']]
            
            # 保存更新后的 JSON
            with open(json_file, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            
            # 重新生成 TXT 文件
            txt_file = os.path.join(pattern_path, 'queries_readable.txt')
            with open(txt_file, 'w', encoding='utf-8') as f:
                f.write(f"Query Type: {data['query_type']}\n")
                f.write(f"Pattern: {data['pattern']}\n")
                f.write(f"Pattern Meaning: {data['pattern_meaning']}\n")
                f.write(f"Total Queries: {data['total_queries']}\n")
                f.write(f"Total Answers: {data['total_answers']}\n")
                f.write("=" * 80 + "\n\n")
                
                for i, qa in enumerate(data['queries'][:100], 1):
                    f.write(f"[Query {i}]\n")
                    # 使用带名称的可读格式（如果有）
                    readable = qa.get('query_readable_with_names', qa['query_readable'])
                    f.write(f"  Query: {readable}\n")
                    
                    # 使用可读答案
                    answers = qa.get('answers_readable', qa['answers'])
                    f.write(f"  Answers ({qa['num_answers']}): ")
                    if qa['num_answers'] <= 5:
                        f.write(", ".join(answers) + "\n")
                    else:
                        f.write(", ".join(answers[:5]) + f", ... (+{qa['num_answers']-5} more)\n")
                    f.write("\n")
                
                if len(data['queries']) > 100:
                    f.write(f"\n... and {len(data['queries']) - 100} more queries\n")


def main():
    base_path = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.dirname(base_path)
    
    # 只处理 FB15k-237+H
    dataset = 'FB15k-237+H'
    dataset_path = os.path.join(data_path, dataset)
    gensparql_path = os.path.join(base_path, dataset)
    
    print(f"\n{'='*60}")
    print(f"Creating readable names for {dataset}")
    print(f"{'='*60}")
    
    # 下载/创建映射
    mid2name = download_and_create_mapping(dataset_path)
    
    # 加载实体和关系映射
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    id2rel = load_pkl(os.path.join(dataset_path, 'id2rel.pkl'))
    
    # 更新可读文件
    print("\nUpdating readable files...")
    update_readable_files_with_names(gensparql_path, mid2name, id2ent, id2rel)
    
    print(f"\n{'='*60}")
    print("Done! Files updated with readable entity names.")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
