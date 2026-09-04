#!/usr/bin/env node
"use strict";

const fs=require("fs");
const path=require("path");

const [, , inputPath,outputPath,globalName="EMBEDDED_KG_DATA",label="Knowledge Graph"]=process.argv;
if(!inputPath||!outputPath){
  console.error("Usage: node scripts/build-ui-kg.js input.ttl output.js GLOBAL_NAME label");
  process.exit(1);
}

const text=fs.readFileSync(inputPath,"utf8");
const prefixes={};
text.replace(/@prefix\s+([A-Za-z][\w-]*):\s*<([^>]+)>\s*\./gi,(_,key,value)=>{prefixes[key]=value;return _;});
const source=text.replace(/@prefix\s+[A-Za-z][\w-]*:\s*<[^>]+>\s*\./gi," ").replace(/#[^\r\n]*/g," ");

function splitStatements(value){
  const result=[];let start=0,quoted=false,iri=false,escaped=false;
  for(let i=0;i<value.length;i++){
    const ch=value[i];
    if(quoted){if(escaped)escaped=false;else if(ch==="\\")escaped=true;else if(ch==='"')quoted=false;continue;}
    if(iri){if(ch===">")iri=false;continue;}
    if(ch==='"'){quoted=true;continue;}if(ch==="<"){iri=true;continue;}
    if(ch==="."){const part=value.slice(start,i).trim();if(part)result.push(part);start=i+1;}
  }
  return result;
}

function lex(value){
  const out=[];let i=0;
  while(i<value.length){
    if(/\s/.test(value[i])){i++;continue;}
    if(value[i]===";"||value[i]===","){out.push(value[i++]);continue;}
    if(value[i]==="<"){let j=i+1;while(j<value.length&&value[j]!==">")j++;out.push(value.slice(i,Math.min(j+1,value.length)));i=j+1;continue;}
    if(value[i]==='"'){
      let j=i+1,escaped=false;
      while(j<value.length){const ch=value[j];if(escaped)escaped=false;else if(ch==="\\")escaped=true;else if(ch==='"'){j++;break;}j++;}
      while(j<value.length&&!/[\s;,]/.test(value[j]))j++;
      out.push(value.slice(i,j));i=j;continue;
    }
    let j=i+1;while(j<value.length&&!/[\s;,]/.test(value[j]))j++;
    out.push(value.slice(i,j));i=j;
  }
  return out;
}

function iri(token){
  if(token==="a")return "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
  if(token.startsWith("<"))return token.slice(1,-1);
  const colon=token.indexOf(":");
  return colon>0&&prefixes[token.slice(0,colon)]?prefixes[token.slice(0,colon)]+token.slice(colon+1):token;
}
function literal(token){
  const end=token.lastIndexOf('"');
  return JSON.parse(token.slice(0,end+1));
}
function localName(value){return decodeURIComponent(String(value).split(/[\/#]/).pop()||value);}

const triples=[];
for(const statement of splitStatements(source)){
  const tokens=lex(statement);if(tokens.length<3)continue;
  const subject=iri(tokens[0]);let i=1,predicate=null;
  while(i<tokens.length){
    if(tokens[i]===";"){predicate=null;i++;continue;}if(tokens[i]===","){i++;continue;}
    if(!predicate)predicate=iri(tokens[i++]);if(i>=tokens.length)break;
    const token=tokens[i++];
    triples.push({s:subject,p:predicate,o:token.startsWith('"')?{literal:true,value:literal(token)}:{literal:false,value:iri(token)}});
    if(tokens[i]===",")i++;else if(tokens[i]===";"){predicate=null;i++;}
  }
}

const RDF_TYPE="http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
const RDFS_LABEL="http://www.w3.org/2000/01/rdf-schema#label";
const nodes=new Map();
const ensure=id=>{if(!nodes.has(id))nodes.set(id,{id,label:localName(id),aliases:[],type:"Entity"});return nodes.get(id);};
for(const triple of triples){
  const node=ensure(triple.s);
  if(triple.p===RDF_TYPE&&!triple.o.literal)node.type=localName(triple.o.value);
  else if(triple.p===RDFS_LABEL&&triple.o.literal){node.label=triple.o.value;if(!node.aliases.includes(triple.o.value))node.aliases.push(triple.o.value);}
  if(!triple.o.literal)ensure(triple.o.value);
}
for(const node of nodes.values())if(!node.aliases.length)node.aliases=[node.label];
const links=triples.filter(t=>!t.o.literal&&t.p!==RDF_TYPE).map(t=>({source:t.s,target:t.o.value,predicate:localName(t.p)}));
const data={label,source:path.basename(inputPath),nodes:[...nodes.values()],links};
fs.writeFileSync(outputPath,`window.${globalName} = ${JSON.stringify(data)};\n`);
console.log(`${outputPath}: ${data.nodes.length} nodes, ${data.links.length} edges`);
