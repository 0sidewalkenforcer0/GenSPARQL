#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
将知识图谱三元组转换为 Apache Jena 可读的 RDF 格式

支持的输出格式:
- N-Triples (.nt) - 简单、每行一个三元组
- Turtle (.ttl) - 更紧凑、使用前缀
- RDF/XML (.rdf) - XML 格式

使用方法:
    python convert_to_rdf.py
    
生成的文件可以直接被 Apache Jena 加载:
    # 使用 Jena 命令行工具
    riot --validate train.nt
    
    # 在 Java 代码中
    Model model = RDFDataMgr.loadModel("train.ttl");
"""

import pickle
import os
import re
from urllib.parse import quote


def load_pkl(filepath):
    with open(filepath, 'rb') as f:
        return pickle.load(f)


def sanitize_uri(name):
    """
    将名称转换为有效的 URI 组件
    """
    # 移除或替换不允许的字符
    # URI 中允许的字符: A-Z a-z 0-9 - . _ ~
    name = str(name)
    # 替换空格为下划线
    name = name.replace(' ', '_')
    # URL 编码特殊字符
    name = quote(name, safe='_-.')
    return name


def create_uri(namespace, local_name):
    """创建完整的 URI"""
    return f"<{namespace}{sanitize_uri(local_name)}>"


def create_literal(value):
    """创建字面量"""
    # 转义特殊字符
    value = str(value).replace('\\', '\\\\').replace('"', '\\"')
    return f'"{value}"'


def convert_to_ntriples(dataset_name, dataset_path, output_path, mid2name=None):
    """
    转换为 N-Triples 格式
    格式: <subject> <predicate> <object> .
    """
    print(f"\nConverting {dataset_name} to N-Triples...")
    
    # 加载映射
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    id2rel = load_pkl(os.path.join(dataset_path, 'id2rel.pkl'))
    
    # 对于 FB15k-237+H，加载 mid2name
    if mid2name is None and os.path.exists(os.path.join(dataset_path, 'mid2name.pkl')):
        mid2name = load_pkl(os.path.join(dataset_path, 'mid2name.pkl'))
    
    # 定义命名空间
    if 'FB15k' in dataset_name:
        entity_ns = "http://freebase.com/entity/"
        relation_ns = "http://freebase.com/relation/"
    else:  # NELL
        entity_ns = "http://nell.cs.cmu.edu/entity/"
        relation_ns = "http://nell.cs.cmu.edu/relation/"
    
    def get_entity_uri(ent_id):
        """获取实体 URI"""
        mid = id2ent.get(ent_id, f"entity_{ent_id}")
        if mid2name and mid in mid2name:
            # 使用可读名称，但保留 MID 作为 URI 的一部分以保证唯一性
            readable = mid2name[mid]
            # 清理 MID 格式 (/m/xxx -> m_xxx)
            mid_clean = mid.replace('/', '_').lstrip('_')
            local_name = f"{mid_clean}_{readable}"
        else:
            local_name = mid.replace('/', '_').lstrip('_')
        return create_uri(entity_ns, local_name)
    
    def get_relation_uri(rel_id):
        """获取关系 URI"""
        rel = id2rel.get(rel_id, f"relation_{rel_id}")
        # 移除方向前缀 +/- 并清理
        if rel.startswith('+') or rel.startswith('-'):
            direction = "forward" if rel.startswith('+') else "inverse"
            rel_clean = rel[1:].replace('/', '_').lstrip('_')
            local_name = f"{direction}_{rel_clean}"
        else:
            local_name = rel.replace('/', '_').replace(':', '_')
        return create_uri(relation_ns, local_name)
    
    os.makedirs(output_path, exist_ok=True)
    
    # 处理 train.txt (只处理正向关系，避免重复)
    input_file = os.path.join(dataset_path, 'train.txt')
    output_file = os.path.join(output_path, 'train.nt')
    
    if not os.path.exists(input_file):
        print(f"  train.txt not found")
        return
    
    print(f"  Converting train.txt -> train.nt")
    
    triple_count = 0
    seen_triples = set()
    
    with open(input_file, 'r') as f_in, open(output_file, 'w', encoding='utf-8') as f_out:
        for line in f_in:
            parts = line.strip().split('\t')
            if len(parts) == 3:
                h_id, r_id, t_id = int(parts[0]), int(parts[1]), int(parts[2])
                
                # 只保留正向关系 (偶数 ID)
                if r_id % 2 == 0:
                    subj = get_entity_uri(h_id)
                    pred = get_relation_uri(r_id)
                    obj = get_entity_uri(t_id)
                    
                    triple_str = f"{subj} {pred} {obj}"
                    if triple_str not in seen_triples:
                        f_out.write(f"{triple_str} .\n")
                        seen_triples.add(triple_str)
                        triple_count += 1
    
    print(f"    Written {triple_count} triples")
    return triple_count


def convert_to_turtle(dataset_name, dataset_path, output_path, mid2name=None):
    """
    转换为 Turtle 格式 (更紧凑，带前缀)
    """
    print(f"\nConverting {dataset_name} to Turtle...")
    
    # 加载映射
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    id2rel = load_pkl(os.path.join(dataset_path, 'id2rel.pkl'))
    
    if mid2name is None and os.path.exists(os.path.join(dataset_path, 'mid2name.pkl')):
        mid2name = load_pkl(os.path.join(dataset_path, 'mid2name.pkl'))
    
    # 定义命名空间
    if 'FB15k' in dataset_name:
        entity_ns = "http://freebase.com/entity/"
        relation_ns = "http://freebase.com/relation/"
        prefix = "fb"
    else:
        entity_ns = "http://nell.cs.cmu.edu/entity/"
        relation_ns = "http://nell.cs.cmu.edu/relation/"
        prefix = "nell"
    
    def get_entity_local(ent_id):
        mid = id2ent.get(ent_id, f"entity_{ent_id}")
        if mid2name and mid in mid2name:
            readable = mid2name[mid]
            mid_clean = mid.replace('/', '_').lstrip('_')
            return sanitize_uri(f"{mid_clean}_{readable}")
        return sanitize_uri(mid.replace('/', '_').lstrip('_'))
    
    def get_relation_local(rel_id):
        rel = id2rel.get(rel_id, f"relation_{rel_id}")
        if rel.startswith('+') or rel.startswith('-'):
            direction = "fwd" if rel.startswith('+') else "inv"
            rel_clean = rel[1:].replace('/', '_').lstrip('_')
            return sanitize_uri(f"{direction}_{rel_clean}")
        return sanitize_uri(rel.replace('/', '_').replace(':', '_'))
    
    os.makedirs(output_path, exist_ok=True)
    
    input_file = os.path.join(dataset_path, 'train.txt')
    output_file = os.path.join(output_path, 'train.ttl')
    
    if not os.path.exists(input_file):
        print(f"  train.txt not found")
        return
    
    print(f"  Converting train.txt -> train.ttl")
    
    triple_count = 0
    seen_triples = set()
    
    with open(input_file, 'r') as f_in, open(output_file, 'w', encoding='utf-8') as f_out:
        # 写入前缀声明
        f_out.write(f"@prefix {prefix}e: <{entity_ns}> .\n")
        f_out.write(f"@prefix {prefix}r: <{relation_ns}> .\n")
        f_out.write(f"@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n")
        f_out.write(f"@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n")
        f_out.write("\n")
        
        for line in f_in:
            parts = line.strip().split('\t')
            if len(parts) == 3:
                h_id, r_id, t_id = int(parts[0]), int(parts[1]), int(parts[2])
                
                # 只保留正向关系
                if r_id % 2 == 0:
                    subj = f"{prefix}e:{get_entity_local(h_id)}"
                    pred = f"{prefix}r:{get_relation_local(r_id)}"
                    obj = f"{prefix}e:{get_entity_local(t_id)}"
                    
                    triple_str = f"{subj} {pred} {obj}"
                    if triple_str not in seen_triples:
                        f_out.write(f"{triple_str} .\n")
                        seen_triples.add(triple_str)
                        triple_count += 1
    
    print(f"    Written {triple_count} triples")
    return triple_count


def create_jena_load_script(output_path):
    """创建 Jena 加载脚本示例"""
    script_content = '''#!/bin/bash
# Apache Jena 加载脚本示例

# 验证 RDF 文件
echo "Validating RDF files..."
riot --validate FB15k-237+H/train.nt
riot --validate NELL995+H/train.nt

# 创建 TDB2 数据库并加载数据
echo "Creating TDB2 database..."

# FB15k-237+H
mkdir -p tdb2/fb15k237
tdb2.tdbloader --loc=tdb2/fb15k237 FB15k-237+H/train.ttl

# NELL995+H  
mkdir -p tdb2/nell995
tdb2.tdbloader --loc=tdb2/nell995 NELL995+H/train.ttl

echo "Done! You can now query the databases using:"
echo "  tdb2.tdbquery --loc=tdb2/fb15k237 --query=query.sparql"
'''
    
    with open(os.path.join(output_path, 'load_to_jena.sh'), 'w') as f:
        f.write(script_content)
    
    # Java 示例代码
    java_content = '''// Apache Jena Java 示例代码

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.query.*;

public class LoadKnowledgeGraph {
    public static void main(String[] args) {
        // 加载 Turtle 文件
        Model model = RDFDataMgr.loadModel("FB15k-237+H/train.ttl");
        
        System.out.println("Loaded " + model.size() + " triples");
        
        // 示例 SPARQL 查询
        String queryString = """
            PREFIX fbe: <http://freebase.com/entity/>
            PREFIX fbr: <http://freebase.com/relation/>
            
            SELECT ?entity ?relation ?target
            WHERE {
                ?entity ?relation ?target .
            }
            LIMIT 10
        """;
        
        Query query = QueryFactory.create(queryString);
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.out(System.out, results, query);
        }
        
        model.close();
    }
}
'''
    
    with open(os.path.join(output_path, 'LoadKnowledgeGraph.java'), 'w') as f:
        f.write(java_content)
    
    # SPARQL 查询示例
    sparql_content = '''# 示例 SPARQL 查询

# 查询所有三元组 (限制10条)
PREFIX fbe: <http://freebase.com/entity/>
PREFIX fbr: <http://freebase.com/relation/>

SELECT ?subject ?predicate ?object
WHERE {
    ?subject ?predicate ?object .
}
LIMIT 10

# ---

# 查询特定实体的所有关系
# PREFIX fbe: <http://freebase.com/entity/>
# SELECT ?predicate ?object
# WHERE {
#     fbe:m_027rn_DVD ?predicate ?object .
# }

# ---

# 统计三元组数量
# SELECT (COUNT(*) AS ?count)
# WHERE {
#     ?s ?p ?o .
# }
'''
    
    with open(os.path.join(output_path, 'example_queries.sparql'), 'w') as f:
        f.write(sparql_content)


def main():
    base_path = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.dirname(base_path)
    
    datasets = [
        ('FB15k-237+H', os.path.join(data_path, 'FB15k-237+H'), os.path.join(base_path, 'FB15k-237+H')),
        ('NELL995+H', os.path.join(data_path, 'NELL995+H'), os.path.join(base_path, 'NELL995+H')),
    ]
    
    print("=" * 60)
    print("Converting KG to Apache Jena RDF Format")
    print("=" * 60)
    
    for dataset_name, dataset_path, output_path in datasets:
        if not os.path.exists(dataset_path):
            print(f"Skipping {dataset_name}: not found")
            continue
        
        # 生成 N-Triples 格式
        convert_to_ntriples(dataset_name, dataset_path, output_path)
        
        # 生成 Turtle 格式
        convert_to_turtle(dataset_name, dataset_path, output_path)
    
    # 创建 Jena 加载脚本和示例
    print("\nCreating Jena helper scripts...")
    create_jena_load_script(base_path)
    
    print("\n" + "=" * 60)
    print("Done! Generated files:")
    print("  - train.nt  (N-Triples format)")
    print("  - train.ttl (Turtle format)")
    print("  - load_to_jena.sh (Jena loading script)")
    print("  - LoadKnowledgeGraph.java (Java example)")
    print("  - example_queries.sparql (SPARQL examples)")
    print("=" * 60)
    print("\nUsage with Apache Jena:")
    print("  1. Validate: riot --validate train.nt")
    print("  2. Load to TDB2: tdb2.tdbloader --loc=mydb train.ttl")
    print("  3. Query: tdb2.tdbquery --loc=mydb --query=example_queries.sparql")


if __name__ == "__main__":
    main()
