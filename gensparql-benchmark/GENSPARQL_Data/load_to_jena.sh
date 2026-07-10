#!/bin/bash
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
