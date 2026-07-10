#!/usr/bin/env python3
"""
Script to add PREFIX declarations to SPARQL query files and simplify URIs.
"""

import os
import re
import glob

# Define prefixes for Freebase
FB_PREFIXES = """PREFIX fb: <http://freebase.com/entity/>
PREFIX rel: <http://freebase.com/relation/>

"""

# Define prefixes for NELL
NELL_PREFIXES = """PREFIX nell: <http://nell.cs.cmu.edu/entity/>
PREFIX nrel: <http://nell.cs.cmu.edu/relation/>

"""

def simplify_query(content, is_nell=False):
    """Add prefixes and simplify URIs in the query."""
    
    # Find where the SELECT statement starts (after comments)
    lines = content.split('\n')
    comment_lines = []
    query_lines = []
    in_query = False
    
    for line in lines:
        # Skip existing PREFIX lines
        if line.strip().startswith('PREFIX'):
            continue
        if not in_query and (line.strip().startswith('#') or line.strip() == ''):
            comment_lines.append(line)
        else:
            in_query = True
            query_lines.append(line)
    
    query_part = '\n'.join(query_lines)
    
    if is_nell:
        # NELL URIs
        # Entity URIs: <http://nell.cs.cmu.edu/entity/...>
        query_part = re.sub(
            r'<http://nell\.cs\.cmu\.edu/entity/([^>]+)>',
            r'nell:\1',
            query_part
        )
        
        # Relation URIs: <http://nell.cs.cmu.edu/relation/...>
        query_part = re.sub(
            r'<http://nell\.cs\.cmu\.edu/relation/([^>]+)>',
            r'nrel:\1',
            query_part
        )
        
        prefixes = NELL_PREFIXES
    else:
        # Freebase URIs
        # Entity URIs: <http://freebase.com/entity/...>
        query_part = re.sub(
            r'<http://freebase\.com/entity/([^>]+)>',
            r'fb:\1',
            query_part
        )
        
        # Relation URIs: <http://freebase.com/relation/...>
        query_part = re.sub(
            r'<http://freebase\.com/relation/([^>]+)>',
            r'rel:\1',
            query_part
        )
        
        prefixes = FB_PREFIXES
    
    # Combine: comments + prefixes + query
    result = '\n'.join(comment_lines)
    if comment_lines and not result.endswith('\n'):
        result += '\n'
    result += '\n' + prefixes + query_part
    
    return result

def process_file(filepath):
    """Process a single SPARQL file."""
    # Determine if this is a NELL query based on path
    is_nell = 'NELL995' in filepath
    
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    new_content = simplify_query(content, is_nell)
    
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
    return True

def main():
    # Find all .sparql files in Queries-FB15k-237+H and Queries-NELL995+H
    base_dir = os.path.dirname(os.path.abspath(__file__))
    
    patterns = [
        os.path.join(base_dir, 'Queries-FB15k-237+H', '**', '*.sparql'),
        os.path.join(base_dir, 'Queries-NELL995+H', '**', '*.sparql'),
    ]
    
    total_processed = 0
    total_modified = 0
    
    for pattern in patterns:
        files = glob.glob(pattern, recursive=True)
        for filepath in files:
            total_processed += 1
            if process_file(filepath):
                total_modified += 1
                print(f"Modified: {filepath}")
    
    print(f"\nTotal processed: {total_processed}")
    print(f"Total modified: {total_modified}")

if __name__ == '__main__':
    main()
