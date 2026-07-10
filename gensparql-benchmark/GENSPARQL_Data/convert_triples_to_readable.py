#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
将 train.txt, valid.txt, test.txt 转换为人类可读格式

原格式: h_id \t r_id \t t_id
新格式: head_entity_name \t relation_name \t tail_entity_name
"""

import pickle
import os


def load_pkl(filepath):
    with open(filepath, 'rb') as f:
        return pickle.load(f)


def convert_triples_to_readable(dataset_name, dataset_path, output_path, mid2name=None):
    """
    将三元组文件转换为可读格式
    """
    print(f"\nConverting {dataset_name}...")
    
    # 加载映射
    id2ent = load_pkl(os.path.join(dataset_path, 'id2ent.pkl'))
    id2rel = load_pkl(os.path.join(dataset_path, 'id2rel.pkl'))
    
    print(f"  Entities: {len(id2ent)}, Relations: {len(id2rel)}")
    
    # 对于 FB15k-237+H，加载 mid2name 映射
    if mid2name is None and os.path.exists(os.path.join(dataset_path, 'mid2name.pkl')):
        mid2name = load_pkl(os.path.join(dataset_path, 'mid2name.pkl'))
        print(f"  Loaded mid2name mapping: {len(mid2name)} entries")
    
    def get_entity_name(ent_id):
        """获取实体的可读名称"""
        mid = id2ent.get(ent_id, f"entity_{ent_id}")
        if mid2name and mid in mid2name:
            return mid2name[mid]
        return mid
    
    def get_relation_name(rel_id):
        """获取关系的可读名称"""
        rel = id2rel.get(rel_id, f"relation_{rel_id}")
        # 简化关系名称
        if rel.startswith('+/') or rel.startswith('-/'):
            direction = '→' if rel.startswith('+/') else '←'
            rel_name = rel[2:].replace('_', ' ')
            return f"{direction}{rel_name}"
        return rel
    
    # 创建输出目录
    os.makedirs(output_path, exist_ok=True)
    
    # 处理每个文件
    files = ['train.txt', 'valid.txt', 'test.txt']
    
    for filename in files:
        input_file = os.path.join(dataset_path, filename)
        output_file = os.path.join(output_path, filename)
        
        if not os.path.exists(input_file):
            print(f"  Skipping {filename}: not found")
            continue
        
        print(f"  Converting {filename}...")
        
        with open(input_file, 'r') as f_in, open(output_file, 'w', encoding='utf-8') as f_out:
            # 写入头部说明
            f_out.write(f"# {dataset_name} - {filename}\n")
            f_out.write(f"# Format: head_entity \\t relation \\t tail_entity\n")
            f_out.write(f"# → = forward relation, ← = inverse relation\n")
            f_out.write("#" + "=" * 77 + "\n")
            
            count = 0
            for line in f_in:
                parts = line.strip().split('\t')
                if len(parts) == 3:
                    h_id, r_id, t_id = int(parts[0]), int(parts[1]), int(parts[2])
                    
                    h_name = get_entity_name(h_id)
                    r_name = get_relation_name(r_id)
                    t_name = get_entity_name(t_id)
                    
                    f_out.write(f"{h_name}\t{r_name}\t{t_name}\n")
                    count += 1
        
        print(f"    Written {count} triples to {output_file}")


def main():
    base_path = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.dirname(base_path)
    
    datasets = [
        ('FB15k-237+H', os.path.join(data_path, 'FB15k-237+H'), os.path.join(base_path, 'FB15k-237+H')),
        ('NELL995+H', os.path.join(data_path, 'NELL995+H'), os.path.join(base_path, 'NELL995+H')),
    ]
    
    print("=" * 60)
    print("Converting KG triples to readable format")
    print("=" * 60)
    
    for dataset_name, dataset_path, output_path in datasets:
        if not os.path.exists(dataset_path):
            print(f"Skipping {dataset_name}: dataset path not found")
            continue
        
        convert_triples_to_readable(dataset_name, dataset_path, output_path)
    
    print("\n" + "=" * 60)
    print("Done! Readable triple files created:")
    print("  - train.txt")
    print("  - valid.txt") 
    print("  - test.txt")
    print("=" * 60)


if __name__ == "__main__":
    main()
