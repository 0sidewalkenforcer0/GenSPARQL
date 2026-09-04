(() => {
  "use strict";

  const CFG = window.GENSPARQL_ADAPTERS;
  const $ = id => document.getElementById(id);
  const pageMode = window.GENSPARQL_PAGE_MODE === "live" ? "live" : "demo";

  const state = {
    dbKey: localStorage.getItem("gensparql.database") || "fifa2026",
    scenarioIndex: 0,
    graph:null,simulation:null,zoom:null,zoomLayer:null,nodeSel:null,linkSel:null,
    resultIds:new Set(),contextIds:new Set(),selectedId:null,focusMode:false,
    compositionStageMeta:null,compositionStrategy:null,compositionStep:null,tooltipPinnedId:null,
    nobelExpandedField:null,
    liveLabelsMandatory:false,
    graphRenderFrame:0,thetaFrame:0,resizeTimer:0
  };
  if (!CFG.databases[state.dbKey]) state.dbKey = "fifa2026";

  const esc = s => String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
  const currentDb = () => CFG.databases[state.dbKey];
  const scenarioList = () => CFG.scenariosByDatabase?.[state.dbKey] || CFG.scenarios;
  const currentScenario = () => scenarioList()[state.scenarioIndex] || scenarioList()[0];
  const similarityLabel = method => String(method || "").trim().toLowerCase() === "text"
    ? "similarity"
    : `${String(method || "").trim()} similarity`.trim();
  const SIMILARITY_BOX_WIDTH=96;
  const SIMILARITY_BOX_HEIGHT=27;
  const SIMILARITY_BOX_GAP=5;

  function cloneGraph(graph){
    return {nodes:graph.nodes.map(n=>({...n})),links:graph.links.map(l=>({...l}))};
  }

  function graphForCurrentScenario(){
    // All tabs start from the complete active KG.
    // Ground entities narrows to generated candidates + relevant targets
    // only after Run.
    return cloneGraph(currentDb().graph);
  }

  function nobelOverviewGraph(){
    const full=currentDb().graph;
    const byId=new Map(full.nodes.map(node=>[node.id,node]));
    const root=full.nodes.find(node=>node.type==="NobelPrize");
    if(!root)return {...cloneGraph(full),nobelOverview:false};
    const fieldLinks=full.links.filter(link=>link.predicate==="hasField"&&link.source===root.id);
    const fields=fieldLinks.map(link=>byId.get(link.target)).filter(Boolean);
    const nodes=[{...root,nobelLevel:0},...fields.map(node=>({...node,nobelLevel:1}))];
    const links=fieldLinks.map(link=>({...link,nobelOverviewLink:true}));

    const expanded=state.nobelExpandedField;
    if(expanded&&byId.has(expanded)){
      const awards=full.links
        .filter(link=>link.predicate==="hasAward"&&link.source===expanded)
        .map(link=>byId.get(link.target))
        .filter(Boolean)
        .sort((a,b)=>nobelAwardYear(b)-nobelAwardYear(a)||String(a.id).localeCompare(String(b.id)))
        .slice(0,10);
      const awardIds=new Set(awards.map(node=>node.id));
      awards.forEach(award=>nodes.push({...award,label:String(nobelAwardYear(award)||"Award"),nobelLevel:2,nobelFieldId:expanded}));
      full.links.filter(link=>link.predicate==="hasAward"&&link.source===expanded&&awardIds.has(link.target))
        .forEach(link=>links.push({...link,nobelOverviewLink:true}));
      full.links.filter(link=>link.predicate==="winner"&&awardIds.has(link.source)).forEach(link=>{
        const winner=byId.get(link.target);
        if(winner&&!nodes.some(node=>node.id===winner.id))nodes.push({...winner,nobelLevel:3,nobelFieldId:expanded});
        if(winner)links.push({...link,nobelOverviewLink:true});
      });
    }
    return {nodes,links,nobelOverview:true};
  }

  function nobelAwardYear(node){
    return Number((String(node?.id||"").match(/_(\d{4})$/)||[])[1])||0;
  }

  function graphForInitialDisplay(){
    return state.dbKey==="nobelPrize"?compactNobelInitialGraph():graphForCurrentScenario();
  }

  function compactNobelInitialGraph(){
    const full=currentDb().graph;
    const root=full.nodes.find(node=>node.type==="NobelPrize");
    const fields=full.nodes.filter(node=>node.type==="NobelField");
    const fieldIds=new Set(fields.map(node=>node.id));
    const awardField=new Map(full.links.filter(link=>link.predicate==="field"&&fieldIds.has(link.target)).map(link=>[link.source,link.target]));
    const awards=[];
    fields.forEach(field=>{
      full.nodes.filter(node=>node.type==="NobelAward"&&awardField.get(node.id)===field.id)
        .sort((a,b)=>nobelAwardYear(b)-nobelAwardYear(a)||String(a.id).localeCompare(String(b.id)))
        .slice(0,24)
        .forEach(node=>awards.push(node));
    });
    const awardIds=new Set(awards.map(node=>node.id));
    const winnerIds=new Set(full.links.filter(link=>link.predicate==="winner"&&awardIds.has(link.source)).map(link=>link.target));
    const relatedIds=new Set();
    full.links.forEach(link=>{
      if(winnerIds.has(link.source)&&link.predicate==="citizenship")relatedIds.add(link.target);
    });
    const keep=new Set([root?.id,...fieldIds,...awardIds,...winnerIds,...relatedIds].filter(Boolean));
    return {
      nodes:full.nodes.filter(node=>keep.has(node.id)).map(node=>({...node})),
      links:full.links.filter(link=>keep.has(link.source)&&keep.has(link.target)).map(link=>({...link})),
      nobelCompactOverview:true,
      fullNodeCount:full.nodes.length,
      fullLinkCount:full.links.length
    };
  }

  function layoutNobelOverview(graph,width,height){
    if(!graph?.nobelOverview)return;
    const cx=width/2,cy=height/2;
    const root=graph.nodes.find(node=>node.nobelLevel===0);
    const fields=graph.nodes.filter(node=>node.nobelLevel===1).sort((a,b)=>String(a.label).localeCompare(String(b.label)));
    if(root){root.x=root.fx=cx;root.y=root.fy=cy;root.labelSide="right";}
    const fieldRadius=Math.min(width,height)*.25;
    fields.forEach((field,index)=>{
      const angle=-Math.PI/2+index*Math.PI*2/Math.max(fields.length,1);
      field._nobelAngle=angle;
      field.x=field.fx=cx+Math.cos(angle)*fieldRadius;
      field.y=field.fy=cy+Math.sin(angle)*fieldRadius;
      field.labelSide=Math.cos(angle)<0?"left":"right";
    });
    const expanded=fields.find(field=>field.id===state.nobelExpandedField);
    if(!expanded)return;
    const awards=graph.nodes.filter(node=>node.nobelLevel===2);
    const winners=graph.nodes.filter(node=>node.nobelLevel===3);
    const winnerByAward=new Map(graph.links.filter(link=>link.predicate==="winner").map(link=>[typeof link.source==="object"?link.source.id:link.source,typeof link.target==="object"?link.target.id:link.target]));
    const base=expanded._nobelAngle;
    awards.forEach((award,index)=>{
      const offset=(index-(awards.length-1)/2)*.075;
      const angle=base+offset;
      const radius=Math.min(width,height)*.39;
      award.x=award.fx=cx+Math.cos(angle)*radius;
      award.y=award.fy=cy+Math.sin(angle)*radius;
      award.labelSide=Math.cos(angle)<0?"left":"right";
      const winner=winners.find(node=>node.id===winnerByAward.get(award.id));
      if(winner){
        const outer=Math.min(width,height)*.49;
        winner.x=winner.fx=cx+Math.cos(angle)*outer;
        winner.y=winner.fy=cy+Math.sin(angle)*outer;
        winner.labelSide=Math.cos(angle)<0?"left":"right";
      }
    });
  }

  function layoutFullNobelGraph(graph,width,height){
    if(state.dbKey!=="nobelPrize"||graph?.nobelOverview||(!graph?.nobelCompactOverview&&graph.nodes.length<1000))return;
    const byId=new Map(graph.nodes.map(node=>[node.id,node]));
    const fields=graph.nodes.filter(node=>node.type==="NobelField").sort((a,b)=>String(a.label).localeCompare(String(b.label)));
    const awardField=new Map();
    const awardWinner=new Map();
    graph.links.forEach(link=>{
      const source=typeof link.source==="object"?link.source.id:link.source;
      const target=typeof link.target==="object"?link.target.id:link.target;
      if(link.predicate==="field")awardField.set(source,target);
      if(link.predicate==="winner")awardWinner.set(source,target);
    });
    const cols=3,rows=2,pad=18;
    const cellW=(width-pad*2)/cols,cellH=(height-pad*2)/rows;
    const winnerPoints=new Map();

    fields.forEach((field,fieldIndex)=>{
      const col=fieldIndex%cols,row=Math.floor(fieldIndex/cols);
      const cell={x:pad+col*cellW,y:pad+row*cellH,w:cellW,h:cellH};
      const clusterX=cell.x+cell.w*.5;
      const clusterY=cell.y+cell.h*.5;
      field.x=field.fx=clusterX;
      field.y=field.fy=clusterY;
      field.labelSide=field.x>width*.76?"left":"right";
      const awards=graph.nodes.filter(node=>node.type==="NobelAward"&&awardField.get(node.id)===field.id)
        .sort((a,b)=>nobelAwardYear(b)-nobelAwardYear(a)||String(a.id).localeCompare(String(b.id)));
      const maxRadius=Math.max(42,Math.min(cell.w*.43,cell.h*.43));
      let awardIndex=0,ringIndex=0;
      while(awardIndex<awards.length){
        const radius=Math.min(maxRadius,22+ringIndex*7.2);
        const capacity=Math.max(12,Math.floor(Math.PI*2*radius/7.2));
        const count=Math.min(capacity,awards.length-awardIndex);
        const offset=(ringIndex%2)*Math.PI/count;
        for(let position=0;position<count;position++){
          const award=awards[awardIndex+position];
          const angle=-Math.PI/2+offset+position*Math.PI*2/count;
          const ax=clusterX+Math.cos(angle)*(radius-1.9);
          const ay=clusterY+Math.sin(angle)*(radius-1.9);
          award.x=award.fx=ax;award.y=award.fy=ay;
          award.nobelDenseOverview=true;
          const winnerId=awardWinner.get(award.id);
          if(winnerId){
            if(!winnerPoints.has(winnerId))winnerPoints.set(winnerId,[]);
            winnerPoints.get(winnerId).push({x:clusterX+Math.cos(angle)*(radius+1.9),y:clusterY+Math.sin(angle)*(radius+1.9)});
          }
        }
        awardIndex+=count;
        ringIndex+=1;
      }
    });

    winnerPoints.forEach((points,id)=>{
      const winner=byId.get(id);if(!winner)return;
      winner.x=winner.fx=points.reduce((sum,p)=>sum+p.x,0)/points.length;
      winner.y=winner.fy=points.reduce((sum,p)=>sum+p.y,0)/points.length;
      winner.nobelDenseOverview=true;
    });

    const linkedPoints=new Map();
    graph.links.forEach(link=>{
      if(link.predicate!=="citizenship"&&link.predicate!=="gender")return;
      const source=byId.get(typeof link.source==="object"?link.source.id:link.source);
      const targetId=typeof link.target==="object"?link.target.id:link.target;
      if(!source||!Number.isFinite(source.x))return;
      if(!linkedPoints.has(targetId))linkedPoints.set(targetId,[]);
      linkedPoints.get(targetId).push({x:source.x,y:source.y});
    });
    linkedPoints.forEach((points,id)=>{
      const node=byId.get(id);if(!node)return;
      node.x=node.fx=points.reduce((sum,p)=>sum+p.x,0)/points.length;
      node.y=node.fy=points.reduce((sum,p)=>sum+p.y,0)/points.length;
      node.nobelDenseOverview=true;
    });
    const root=graph.nodes.find(node=>node.type==="NobelPrize");
    if(root){root.x=root.fx=width/2;root.y=root.fy=height/2;}
  }

  function hl(q){
    let t=esc(q);
    t=t.replace(/(#[^\n]*)/g,'<span class="cmt">$1</span>');
    t=t.replace(/\b(SELECT|WHERE|FILTER|OPTIONAL|UNION|a)\b/g,'<span class="kw">$1</span>');
    // GENOP only: it is the one generative construct written in a query. The similarity join
    // that grounds its output is chosen by the engine and has no surface syntax, so
    // highlighting it as a keyword would suggest a query could name it.
    t=t.replace(/\b(GENOP)\b/g,'<span class="gen">$1</span>');
    return t;
  }

  function detectMode(q){
    const m=q.match(/GENOP\s*\(\s*"([\s\S]*?)"/i);
    const prompt=m?m[1]:"";
    const vars=prompt.match(/\{\?([A-Za-z_][\w-]*)\}/g)||[];
    return {mode:vars.length?"context":"base",vars:vars.map(v=>v.slice(1,-1))};
  }

  function renderMode(q){
    const m=detectMode(q),base=$("baseMode"),context=$("contextMode");
    base.className="mode-option";context.className="mode-option";
    if(m.mode==="context"){
      context.classList.add("active-context");
      $("modeDetail").textContent="uses "+m.vars.join(", ");
      $("modeHelp").innerHTML='<strong>Context mode:</strong> the prompt uses bindings produced by earlier KG patterns.';
    }else{
      base.classList.add("active-base");
      $("modeDetail").textContent="constant prompt";
      $("modeHelp").innerHTML='<strong>Base mode:</strong> one constant prompt can generate multiple rows before grounding.';
    }
  }

  function renderTabs(force=false){
    const tabs=$("tabs");
    if(force)tabs.replaceChildren();
    if(!tabs.children.length){
      const fragment=document.createDocumentFragment();
      scenarioList().forEach((s,i)=>{
        const b=document.createElement("button");
        b.className="tab";b.type="button";b.textContent=s.tab;b.dataset.scenarioIndex=String(i);
        fragment.appendChild(b);
      });
      tabs.appendChild(fragment);
      tabs.onclick=event=>{
        const button=event.target.closest("button[data-scenario-index]");
        if(!button||button.parentElement!==tabs)return;
        const i=Number(button.dataset.scenarioIndex);
        if(i===state.scenarioIndex)return;
        state.scenarioIndex=i;
        renderTabs();
        renderScenario();
        clearMessage();
        $("attributeResult").hidden=true;
        scheduleGraphInit();
      };
    }
    scenarioList().forEach((s,i)=>{
      const b=tabs.children[i];
      b.setAttribute("aria-selected",i===state.scenarioIndex?"true":"false");
      b.tabIndex=i===state.scenarioIndex?0:-1;
    });
  }

  function scheduleGraphInit(){
    if(state.graphRenderFrame)cancelAnimationFrame(state.graphRenderFrame);
    if(state.simulation)state.simulation.stop();
    state.graphRenderFrame=requestAnimationFrame(()=>{
      state.graphRenderFrame=0;
      initGraph();
    });
  }

  function renderScenario(){
    const s=currentScenario();
    const q=s.query.replace("{theta}",Number($("theta").value).toFixed(2)).replace("<model>",liveOn()?"<model:live>":"<model:…>");
    $("queryName").textContent=s.name;
    $("query").innerHTML=hl(q);
    renderMode(q);
    $("thetaWrap").classList.toggle("off",!s.hero);

    const isComposition=s.id==="composition";
    const isAttribute=s.id==="attribute";
    $("defaultRunRow").hidden=isComposition;
    $("composeRun").hidden=!isComposition;

    if(!isComposition){
      $("executionResult").hidden=true;
      $("executionResult").className="execution-result";
    }
    if(!isAttribute){
      $("attributeResult").hidden=true;
    }
    $("attributeResultTitle").textContent=s.generatedTitle||"Generated player positions";
    $("attributeAccuracyLabel").textContent=s.metricLabel||"Accuracy vs P413";
    $("attributeResultNote").textContent=s.note||"Accuracy vs P413 is the recorded gold-label evaluation metric. SPARQL alone is 0 because player position is omitted from this KG. The rows above are a small displayed sample, not the full evaluation set.";

    const executionLabel=liveOn()&&s.id==="entity"?"live":"pre-recorded";
    $("runModeDefault").textContent=executionLabel;

    $("caption").textContent=
      isComposition
      ? "Compose + defer compares the same logical query under two physical execution orders: GENOP-first versus planner-deferred GENOP."
      : `${currentDb().label} uses the full ${currentDb().source} knowledge graph.`;
  }


  const BUILTIN_PREFIXES={
    ex:"http://example.org/",
    wd:"http://www.wikidata.org/entity/",
    rdf:"http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    rdfs:"http://www.w3.org/2000/01/rdf-schema#",
    xsd:"http://www.w3.org/2001/XMLSchema#"
  };

  function unescapeLiteral(token){
    const m=String(token).match(/^"((?:\\.|[^"\\])*)"/s);
    if(!m)return String(token);
    try{return JSON.parse('"'+m[1]+'"')}catch(_){return m[1].replace(/\\"/g,'"').replace(/\\n/g,'\n').replace(/\\t/g,'\t').replace(/\\\\/g,'\\')}
  }

  function localName(iri){
    const s=String(iri||"");
    const hash=s.lastIndexOf("#"),slash=s.lastIndexOf("/"),colon=s.lastIndexOf(":");
    const i=Math.max(hash,slash,colon);
    return i>=0?s.slice(i+1):s;
  }

  function resolveIriToken(token,prefixes={}){
    const t=String(token||"").trim();
    if(t==="a")return BUILTIN_PREFIXES.rdf+"type";
    if(/^<[^>]+>$/.test(t))return t.slice(1,-1);
    const m=t.match(/^([A-Za-z][\w-]*):(.+)$/);
    if(m){
      const base=prefixes[m[1]]||BUILTIN_PREFIXES[m[1]];
      if(base)return base+m[2];
    }
    return t;
  }

  function compactIri(iri,prefixes={}){
    const all={...BUILTIN_PREFIXES,...prefixes};
    for(const [prefix,base] of Object.entries(all)){
      if(String(iri).startsWith(base))return `${prefix}:${String(iri).slice(base.length)}`;
    }
    return String(iri);
  }

  function stripLineComments(text){
    return String(text).split(/\r?\n/).map(line=>{
      let quote=false,escape=false,angle=0;
      for(let i=0;i<line.length;i++){
        const c=line[i];
        if(quote){
          if(escape){escape=false;continue}
          if(c==="\\"){escape=true;continue}
          if(c==='"')quote=false;
          continue;
        }
        if(c==='"'){quote=true;continue}
        if(c==='<'){angle++;continue}
        if(c==='>'&&angle){angle--;continue}
        if(c==='#'&&!angle)return line.slice(0,i);
      }
      return line;
    }).join("\n");
  }

  function splitStatements(text){
    const out=[];
    let start=0,quote=false,escape=false,angle=0,paren=0,brace=0;
    const src=String(text);
    for(let i=0;i<src.length;i++){
      const c=src[i];
      if(quote){
        if(escape){escape=false;continue}
        if(c==="\\"){escape=true;continue}
        if(c==='"')quote=false;
        continue;
      }
      if(c==='"'){quote=true;continue}
      if(c==='<'){angle++;continue}
      if(c==='>'&&angle){angle--;continue}
      if(angle)continue;
      if(c==='(')paren++;
      else if(c===')'&&paren)paren--;
      else if(c==='{')brace++;
      else if(c==='}'&&brace)brace--;
      else if(c==='.'&&!paren&&!brace){
        const part=src.slice(start,i).trim();
        if(part)out.push(part);
        start=i+1;
      }
    }
    const tail=src.slice(start).trim();
    if(tail)out.push(tail);
    return out;
  }

  function turtleLex(text){
    const tokens=[];
    const src=String(text);
    let i=0;
    while(i<src.length){
      if(/\s/.test(src[i])){i++;continue}
      if(src[i]===';'||src[i]===','){tokens.push(src[i++]);continue}
      if(src[i]==='<'){
        let j=i+1;
        while(j<src.length&&src[j]!=='>')j++;
        tokens.push(src.slice(i,Math.min(j+1,src.length)));i=Math.min(j+1,src.length);continue;
      }
      if(src[i]==='"'){
        let j=i+1,escape=false;
        while(j<src.length){
          const c=src[j];
          if(escape){escape=false;j++;continue}
          if(c==='\\'){escape=true;j++;continue}
          if(c==='"'){j++;break}
          j++;
        }
        if(src[j]==='@'){
          j++;
          while(j<src.length&&/[A-Za-z0-9-]/.test(src[j]))j++;
        }else if(src.slice(j,j+2)==='^^'){
          j+=2;
          if(src[j]==='<'){
            j++;
            while(j<src.length&&src[j]!=='>')j++;
            if(src[j]==='>')j++;
          }else{
            while(j<src.length&&!/[\s;,]/.test(src[j]))j++;
          }
        }
        tokens.push(src.slice(i,j));i=j;continue;
      }
      let j=i+1;
      while(j<src.length&&!/[\s;,]/.test(src[j]))j++;
      tokens.push(src.slice(i,j));i=j;
    }
    return tokens;
  }

  function setEngineStatus(text,kind=""){
    const el=$("engineConnectionStatus");
    if(!el)return;
    el.textContent=text;
    el.className="engine-connection-status"+(kind?` ${kind}`:"");
  }

  function engineClient(){
    return window.GENSPARQL_ENGINE||null;
  }

  async function ensureJavaDataset(db){
    const engine=engineClient();
    if(!engine)throw new Error("engine-client.js failed to load.");
    if(db.engineDatasetId)return db.engineDatasetId;
    if(db.engineUploadPromise)return db.engineUploadPromise;

    setEngineStatus(`Loading ${db.label} into Java engine…`);
    db.engineUploadPromise=(async()=>{
      let info;
      if(db.sourceText!=null){
        const file=new File([db.sourceText],db.source||`${db.key||"dataset"}.ttl`,{type:"text/turtle"});
        info=await engine.uploadFile(file);
      }else if(db.source){
        info=await engine.uploadUrl(db.source,db.source.split("/").pop()||"dataset.ttl");
      }else{
        throw new Error(`No RDF source is available for ${db.label}.`);
      }
      db.engineDatasetId=info.datasetId;
      db.engineTripleCount=info.tripleCount??info.quadCount??info.size??null;
      const count=db.engineTripleCount==null?"":` · ${db.engineTripleCount} triples`;
      setEngineStatus(`Java engine dataset ready · ${db.label}${count}`,"ok");
      return db.engineDatasetId;
    })();

    try{return await db.engineUploadPromise}
    catch(err){
      setEngineStatus(`Java dataset load failed: ${err.message||err}`,"err");
      throw err;
    }finally{
      db.engineUploadPromise=null;
    }
  }

  function initEngineBackend(){
    const base=$("engineBase"),test=$("testEngineBtn");
    const engine=engineClient();
    if(!base)return;

    if(engine)base.value=engine.getApiBase()||base.value;

    const sync=()=>{
      setEngineStatus(engine?`Java engine URL · ${engine.getApiBase()}`:"engine-client.js is missing",engine?"":"err");
    };

    base.addEventListener("change",()=>{
      if(engine)engine.setApiBase(base.value);
      setEngineStatus(`Java engine URL · ${base.value.trim()}`);
    });
    base.addEventListener("blur",()=>{if(engine)engine.setApiBase(base.value)});

    if(test)test.onclick=async()=>{
      if(!engine){setEngineStatus("engine-client.js is missing.","err");return}
      engine.setApiBase(base.value);
      test.disabled=true;test.textContent="Testing…";
      setEngineStatus(`Connecting to ${engine.getApiBase()}…`);
      try{
        const data=await engine.health();
        const name=data.engine||data.name||"GenSPARQL";
        const version=data.version?` ${data.version}`:"";
        setEngineStatus(`Connected · ${name}${version}`,"ok");
      }catch(err){
        setEngineStatus(`Connection failed: ${err.message||err}`,"err");
      }finally{
        test.disabled=false;test.textContent="Test engine";
      }
    };
    sync();
  }

  function parseTurtleToDatabase(text,fileName){
    const clean=stripLineComments(text);
    const prefixes={...BUILTIN_PREFIXES};
    clean.replace(/@prefix\s+([A-Za-z][\w-]*):\s*<([^>]+)>\s*\./gi,(_,pfx,iri)=>{prefixes[pfx]=iri;return _});

    const withoutPrefixes=clean.replace(/@prefix\s+[A-Za-z][\w-]*:\s*<[^>]+>\s*\./gi," ");
    const triples=[];
    const tripleSeen=new Set();

    for(const statement of splitStatements(withoutPrefixes)){
      const tokens=turtleLex(statement);
      if(tokens.length<3)continue;
      let i=0;
      const subjectToken=tokens[i++];
      const subject=resolveIriToken(subjectToken,prefixes);
      let predicateToken=null;
      while(i<tokens.length){
        if(tokens[i]===';'){i++;predicateToken=null;continue}
        if(tokens[i]===','){i++;continue}
        if(!predicateToken)predicateToken=tokens[i++];
        if(i>=tokens.length)break;
        const objectToken=tokens[i++];
        if(objectToken===';'||objectToken===',')continue;
        const predicate=resolveIriToken(predicateToken,prefixes);
        const literal=objectToken.startsWith('"');
        const object=literal
          ? {kind:"literal",value:unescapeLiteral(objectToken),raw:objectToken}
          : {kind:"iri",value:resolveIriToken(objectToken,prefixes),raw:objectToken};
        const key=`${subject}\u0001${predicate}\u0001${object.kind}\u0001${object.value}`;
        if(!tripleSeen.has(key)){
          tripleSeen.add(key);
          triples.push({s:subject,p:predicate,o:object});
        }
        if(tokens[i]===','){
          i++;
        }else if(tokens[i]===';'){
          i++;predicateToken=null;
        }
      }
    }

    if(!triples.length)throw new Error("No Turtle triples could be parsed from this file.");

    const nodes=new Map();
    const ensureNode=id=>{
      if(!nodes.has(id))nodes.set(id,{id,label:compactIri(id,prefixes),aliases:[],type:"Entity"});
      return nodes.get(id);
    };
    const typePred=BUILTIN_PREFIXES.rdf+"type";
    const labelPred=BUILTIN_PREFIXES.rdfs+"label";

    triples.forEach(t=>{
      const node=ensureNode(t.s);
      if(t.p===typePred&&t.o.kind==="iri"){
        node.type=localName(t.o.value)||"Entity";
      }else if(t.p===labelPred&&t.o.kind==="literal"){
        if(!node.aliases.includes(t.o.value))node.aliases.push(t.o.value);
        if(node.label===compactIri(node.id,prefixes))node.label=t.o.value;
      }
      if(t.o.kind==="iri")ensureNode(t.o.value);
    });

    nodes.forEach(node=>{
      if(!node.aliases.length)node.aliases=[node.label];
      else if(!node.aliases.includes(node.label))node.aliases.unshift(node.label);
    });

    const links=[];
    const linkSeen=new Set();
    triples.forEach(t=>{
      if(t.o.kind!=="iri"||t.p===typePred) return;
      const key=`${t.s}\u0001${t.p}\u0001${t.o.value}`;
      if(linkSeen.has(key))return;
      linkSeen.add(key);
      links.push({source:t.s,target:t.o.value,predicate:localName(t.p)});
    });

    return {
      label:fileName.replace(/\.(ttl|turtle)$/i,"")||"Uploaded KG",
      source:fileName,
      graph:{nodes:[...nodes.values()],links},
      triples,
      prefixes,
      uploaded:true
    };
  }

  function initKgUpload(){
    const button=$("uploadKgBtn"),input=$("kgFileInput"),status=$("kgUploadStatus");
    if(!button||!input)return;
    button.onclick=()=>input.click();
    input.onchange=async()=>{
      const file=input.files&&input.files[0];
      if(!file)return;
      button.disabled=true;
      status.textContent="Reading…";
      try{
        const sourceText=await file.text();
        const db=parseTurtleToDatabase(sourceText,file.name);
        db.sourceText=sourceText;
        const key=`uploaded-${Date.now()}`;
        db.key=key;
        CFG.databases[key]=db;
        const option=document.createElement("option");
        option.value=key;option.textContent=`Uploaded · ${db.label}`;
        $("databaseSelect").appendChild(option);
        state.dbKey=key;
        $("databaseSelect").value=key;
        localStorage.setItem("gensparql.database",key);
        status.textContent=`${db.graph.nodes.length} nodes · ${db.graph.links.length} edges`;
        syncPreRecordedVisibility();
        initGraph();
        clearMessage();

        try{
            const engine=engineClient();
            if(!engine)throw new Error("engine-client.js failed to load.");
            engine.setApiBase($("engineBase")?.value||engine.getApiBase());
            setEngineStatus(`Uploading ${file.name} to Java engine…`);
            const info=await engine.uploadFile(file);
            db.engineDatasetId=info.datasetId;
            db.engineTripleCount=info.tripleCount??info.quadCount??info.size??null;
            const count=db.engineTripleCount==null?"":` · ${db.engineTripleCount} triples`;
            setEngineStatus(`Java engine dataset ready · ${db.label}${count}`,"ok");
            showMessage(`Loaded ${file.name} locally and in Java engine: ${db.triples.length} triples.`,"ok");
        }catch(engineErr){
            setEngineStatus(`Java upload failed: ${engineErr.message||engineErr}`,"err");
            showMessage(`Loaded ${file.name} locally, but Java engine upload failed: ${engineErr.message||engineErr}`,"err");
        }
      }catch(err){
        status.textContent="Upload failed";
        showMessage(`KG upload failed: ${err.message||err}`,"err");
      }finally{
        button.disabled=false;
        input.value="";
      }
    };
  }

  function nodeIdForIri(iri){
    const prefixes=currentDb().prefixes||BUILTIN_PREFIXES;
    const target=resolveIriToken(iri,prefixes);
    const node=currentDb().graph.nodes.find(n=>resolveIriToken(n.id,prefixes)===target);
    return node?.id||null;
  }

  function displayQueryValue(value,prefixes){
    if(!value)return "—";
    if(value.kind==="literal")return value.value;
    return compactIri(value.value,prefixes);
  }

  function renderLiveQueryResult(result){
    const panel=$("liveQueryResult"),meta=$("liveResultMeta"),body=$("liveResultBody");
    panel.hidden=false;
    const displayRows=result.rows.slice(0,250);
    const shown=displayRows.length;
    const plan=result.plan?` · ${result.plan}`:"";
    meta.textContent=`${result.total} row${result.total===1?"":"s"}${result.total>shown?` · showing first ${shown}`:""}${plan}`;
    if(!result.rows.length){
      body.innerHTML='<div class="live-result-empty">No matching rows.</div>';
      return;
    }
    const head=result.vars.map(v=>`<th>?${esc(v)}</th>`).join("");
    const rows=displayRows.map(row=>`<tr>${result.vars.map(v=>`<td>${esc(displayQueryValue(row[v],result.prefixes))}</td>`).join("")}</tr>`).join("");
    body.innerHTML=`<table class="live-result-table"><thead><tr>${head}</tr></thead><tbody>${rows}</tbody></table>`;
  }

  function stableUnit(text,salt=0){
    let h=(2166136261+salt)>>>0;
    const value=String(text||"");
    for(let i=0;i<value.length;i++){
      h^=value.charCodeAt(i);
      h=Math.imul(h,16777619)>>>0;
    }
    return (h%1000003)/1000003;
  }

  function liveRelatedContext(fullGraph,resultIds){
    const nodeById=new Map(fullGraph.nodes.map(n=>[n.id,n]));
    const outgoing=new Map();
    const incoming=new Map();

    fullGraph.links.forEach(link=>{
      const source=typeof link.source==="object"?link.source.id:link.source;
      const target=typeof link.target==="object"?link.target.id:link.target;
      if(!outgoing.has(source))outgoing.set(source,[]);
      if(!incoming.has(target))incoming.set(target,[]);
      outgoing.get(source).push({id:target,link});
      incoming.get(target).push({id:source,link});
    });

    const depth1=new Set();
    const depth2=new Set();

    // Prefer the KG's directed "parent" path. In this World Cup graph that
    // naturally gives Player -> Team -> Group and Venue -> City. When a result
    // has no outgoing parent (for example Group), fall back to a small set of
    // incoming structural neighbours instead of expanding the whole graph.
    resultIds.forEach(id=>{
      const parents=(outgoing.get(id)||[])
        .map(item=>item.id)
        .filter(other=>!resultIds.has(other));
      if(parents.length){
        parents.forEach(other=>depth1.add(other));
        return;
      }

      const fallback=(incoming.get(id)||[])
        .map(item=>item.id)
        .filter(other=>!resultIds.has(other))
        .sort((a,b)=>{
          const an=nodeById.get(a),bn=nodeById.get(b);
          const as=an?.type==="Athlete"?1:0;
          const bs=bn?.type==="Athlete"?1:0;
          return as-bs||String(an?.label||a).localeCompare(String(bn?.label||b));
        })
        .slice(0,16);
      fallback.forEach(other=>depth1.add(other));
    });

    // Second level follows the same directed structural path only. This avoids
    // exploding Team/Group context back into hundreds of unrelated athletes.
    depth1.forEach(id=>{
      (outgoing.get(id)||[]).forEach(item=>{
        const other=item.id;
        if(!resultIds.has(other)&&!depth1.has(other))depth2.add(other);
      });
    });

    return {depth1,depth2};
  }

  function liveUniformRadius(resultCount,totalCount){
    if(resultCount>900||totalCount>1050)return 1.35;
    if(resultCount>450||totalCount>600)return 1.65;
    if(resultCount>180||totalCount>280)return 2.0;
    if(resultCount>80||totalCount>140)return 2.55;
    if(resultCount>36||totalCount>75)return 3.25;
    if(resultCount>14||totalCount>34)return 4.1;
    return 5.0;
  }

  function liveVisualProfile(resultCount,totalCount){
    const radius=liveUniformRadius(resultCount,totalCount);
    if(resultCount>450||totalCount>600)return {radius,resultFont:5.2,contextFont:5.0,maxResultLabels:48};
    if(resultCount>180||totalCount>280)return {radius,resultFont:5.6,contextFont:5.2,maxResultLabels:64};
    if(resultCount>80||totalCount>140)return {radius,resultFont:6.1,contextFont:5.6,maxResultLabels:80};
    if(resultCount>36||totalCount>75)return {radius,resultFont:6.8,contextFont:6.1,maxResultLabels:120};
    if(resultCount>14||totalCount>34)return {radius,resultFont:7.6,contextFont:6.7,maxResultLabels:120};
    if(resultCount>6||totalCount>16)return {radius,resultFont:8.5,contextFont:7.2,maxResultLabels:120};
    return {radius,resultFont:9.6,contextFont:8.0,maxResultLabels:120};
  }

  function gridNodes(nodes,box,radius,salt=0){
    if(!nodes.length)return;
    const width=Math.max(20,box.w),height=Math.max(20,box.h);
    const aspect=width/height;
    const cols=Math.max(1,Math.ceil(Math.sqrt(nodes.length*aspect)));
    const rows=Math.max(1,Math.ceil(nodes.length/cols));
    const stepX=width/cols,stepY=height/rows;
    [...nodes].sort((a,b)=>stableUnit(a.id,salt)-stableUnit(b.id,salt)).forEach((node,i)=>{
      const c=i%cols,r=Math.floor(i/cols);
      const jitter=Math.min(1.6,Math.max(.25,radius*.55));
      const x=box.x+(c+.5)*stepX+(stableUnit(node.id,salt+17)-.5)*jitter;
      const y=box.y+(r+.5)*stepY+(stableUnit(node.id,salt+37)-.5)*jitter;
      node.x=node.fx=x;node.y=node.fy=y;
    });
  }

  function ringNodes(nodes,cx,cy,baseRadius,salt=0,angleOffset=-Math.PI/2){
    if(!nodes.length)return;
    const ordered=[...nodes].sort((a,b)=>stableUnit(a.id,salt)-stableUnit(b.id,salt));
    if(ordered.length===1){
      const node=ordered[0];
      node.x=node.fx=cx;node.y=node.fy=cy;
      node._liveAngle=angleOffset;
      return;
    }
    const perRing=Math.max(6,Math.min(14,Math.ceil(Math.sqrt(ordered.length))*2));
    let placed=0, ringIndex=0;
    while(placed<ordered.length){
      const count=Math.min(perRing+ringIndex*3,ordered.length-placed);
      const radius=baseRadius+ringIndex*Math.max(14,baseRadius*.42);
      for(let i=0;i<count;i++){
        const node=ordered[placed+i];
        const angle=angleOffset+(Math.PI*2*i/count)+(stableUnit(node.id,salt+31)-.5)*.16;
        node._liveAngle=angle;
        node.x=node.fx=cx+Math.cos(angle)*radius;
        node.y=node.fy=cy+Math.sin(angle)*radius;
      }
      placed+=count;
      ringIndex+=1;
    }
  }

  function placeResultCloud(nodes,anchor,box,anchorAngle,resultRadius,salt=0){
    if(!nodes.length)return;
    const ordered=[...nodes].sort((a,b)=>stableUnit(a.id,salt)-stableUnit(b.id,salt));
    const ux=Math.cos(anchorAngle||0), uy=Math.sin(anchorAngle||0);
    const spread=Math.max(22,Math.min(Math.min(box.w,box.h)*.18,68));
    const cx=Math.min(box.x+box.w-10,Math.max(box.x+10,anchor.x+ux*Math.max(14,spread*.35)));
    const cy=Math.min(box.y+box.h-10,Math.max(box.y+10,anchor.y+uy*Math.max(14,spread*.35)));
    if(ordered.length<=12){
      ringNodes(ordered,cx,cy,Math.max(10,spread*.28),salt,anchorAngle||-Math.PI/2);
      return;
    }
    const cloudW=Math.max(24,Math.min(box.w*.42,spread*1.9));
    const cloudH=Math.max(22,Math.min(box.h*.34,spread*1.55));
    gridNodes(ordered,{
      x:cx-cloudW/2,
      y:cy-cloudH/2,
      w:cloudW,
      h:cloudH
    },resultRadius,salt);
  }

  function layoutDisjointResultPairs(graph,results,box){
    if(results.length<2||results.length%2)return false;
    const resultIds=new Set(results.map(node=>node.id));
    const byId=new Map(results.map(node=>[node.id,node]));
    const pairs=[];
    const degree=new Map(results.map(node=>[node.id,0]));
    for(const link of graph.links||[]){
      const sourceId=typeof link.source==="object"?link.source.id:link.source;
      const targetId=typeof link.target==="object"?link.target.id:link.target;
      if(!resultIds.has(sourceId)||!resultIds.has(targetId))continue;
      pairs.push({source:byId.get(sourceId),target:byId.get(targetId),predicate:link.predicate||""});
      degree.set(sourceId,degree.get(sourceId)+1);
      degree.set(targetId,degree.get(targetId)+1);
    }
    if(pairs.length*2!==results.length||[...degree.values()].some(value=>value!==1))return false;

    pairs.sort((a,b)=>String(a.source.label||a.source.id).localeCompare(String(b.source.label||b.source.id)));
    const cols=Math.max(1,Math.ceil(Math.sqrt(pairs.length)));
    const rows=Math.ceil(pairs.length/cols);
    const cellW=box.w/cols,cellH=box.h/rows;
    pairs.forEach((pair,index)=>{
      const col=index%cols,row=Math.floor(index/cols);
      const cy=box.y+(row+.5)*cellH;
      const left=box.x+col*cellW+cellW*.22;
      const right=box.x+col*cellW+cellW*.78;
      pair.source.x=pair.source.fx=left;
      pair.source.y=pair.source.fy=cy;
      pair.source.labelSide="left";
      pair.target.x=pair.target.fx=right;
      pair.target.y=pair.target.fy=cy;
      pair.target.labelSide="right";
    });
    return true;
  }

  function layoutLiveRelatedGraph(graph,resultIds,depth1Ids,depth2Ids){
    const stage=$("graphStage");
    const width=stage.clientWidth||760;
    const height=stage.clientHeight||570;
    const pad=10;
    const inner={x:pad,y:pad,w:Math.max(120,width-pad*2),h:Math.max(100,height-pad*2)};
    const nodeById=new Map(graph.nodes.map(n=>[n.id,n]));
    const results=[...resultIds].map(id=>nodeById.get(id)).filter(Boolean);
    const depth1=[...depth1Ids].map(id=>nodeById.get(id)).filter(Boolean);
    const depth2=[...depth2Ids].map(id=>nodeById.get(id)).filter(Boolean);
    const visual=liveVisualProfile(results.length,graph.nodes.length);
    const resultRadius=visual.radius;
    const context1Radius=Math.max(.95,resultRadius*.8);
    const context2Radius=Math.max(.8,resultRadius*.62);

    results.forEach(node=>{
      node.liveQueryResult=true;
      node.liveResultRadius=resultRadius;
      node.liveGeneratedRadius=node.liveGeneratedPos?Math.max(1.2,resultRadius*.88):node.liveGeneratedRadius;
      node.liveVisualFont=visual.resultFont;
      node.liveShowLabel=false;
    });
    depth1.forEach(node=>{
      node.liveContextDepth=1;
      node.liveContextRadius=context1Radius;
      node.liveVisualFont=visual.contextFont;
      node.liveShowLabel=false;
    });
    depth2.forEach(node=>{
      node.liveContextDepth=2;
      node.liveContextRadius=context2Radius;
      node.liveVisualFont=Math.max(5,visual.contextFont-.4);
      node.liveShowLabel=false;
    });

    const links=graph.links;
    const connected=(a,b)=>links.some(link=>{
      const s=typeof link.source==="object"?link.source.id:link.source;
      const t=typeof link.target==="object"?link.target.id:link.target;
      return (s===a&&t===b)||(s===b&&t===a);
    });

    if(!depth1.length&&!depth2.length){
      if(layoutDisjointResultPairs(graph,results,inner))return;
      gridNodes(results,inner,resultRadius,101);
      enforceLiveNodeSeparation(graph,inner);
      return;
    }

    const roots=depth2.length?depth2:depth1;
    const rootIsDepth2=depth2.length>0;
    const orderedRoots=[...roots].sort((a,b)=>String(a.label||a.id).localeCompare(String(b.label||b.id)));
    const aspect=inner.w/inner.h;
    const rootCols=Math.max(1,Math.ceil(Math.sqrt(orderedRoots.length*aspect)));
    const rootRows=Math.max(1,Math.ceil(orderedRoots.length/rootCols));
    const cellW=inner.w/rootCols,cellH=inner.h/rootRows;

    orderedRoots.forEach((root,i)=>{
      const c=i%rootCols,r=Math.floor(i/rootCols);
      root._liveCell={x:inner.x+c*cellW,y:inner.y+r*cellH,w:cellW,h:cellH};
      root.x=root.fx=root._liveCell.x+cellW*.5;
      root.y=root.fy=root._liveCell.y+cellH*.5;
    });

    const depth1ByRoot=new Map(orderedRoots.map(root=>[root.id,[]]));
    if(rootIsDepth2){
      depth1.forEach(node=>{
        let root=orderedRoots.find(candidate=>connected(node.id,candidate.id));
        if(!root)root=orderedRoots[Math.floor(stableUnit(node.id,73)*orderedRoots.length)]||orderedRoots[0];
        if(root)depth1ByRoot.get(root.id).push(node);
      });
      depth1ByRoot.forEach((children,rootId)=>{
        const root=nodeById.get(rootId);
        if(!root)return;
        const ringBase=Math.min(root._liveCell.w,root._liveCell.h)*.23;
        ringNodes(children,root.x,root.y,ringBase,Math.floor(stableUnit(root.id,59)*10000));
        children.forEach(child=>child._liveRootId=root.id);
      });
    }

    const anchors=rootIsDepth2?depth1:orderedRoots;
    const resultsByAnchor=new Map(anchors.map(anchor=>[anchor.id,[]]));
    const rootDirect=new Map(orderedRoots.map(root=>[root.id,[]]));
    const orphanResults=[];

    results.forEach(node=>{
      let anchor=anchors.find(candidate=>connected(node.id,candidate.id));
      if(anchor){
        resultsByAnchor.get(anchor.id).push(node);
        return;
      }
      if(rootIsDepth2){
        const root=orderedRoots.find(candidate=>connected(node.id,candidate.id));
        if(root){
          rootDirect.get(root.id).push(node);
          return;
        }
      }
      orphanResults.push(node);
    });

    if(!rootIsDepth2){
      orderedRoots.forEach(root=>{
        const nodes=resultsByAnchor.get(root.id)||[];
        if(!nodes.length)return;
        const box=root._liveCell;
        const ringBase=Math.min(box.w,box.h)*.24;
        if(nodes.length<=18){
          ringNodes(nodes,root.x,root.y,ringBase,Math.floor(stableUnit(root.id,67)*10000));
        }else{
          gridNodes(nodes,{
            x:box.x+box.w*.12,
            y:box.y+box.h*.2,
            w:box.w*.76,
            h:box.h*.56
          },resultRadius,Math.floor(stableUnit(root.id,67)*10000));
        }
      });
    }else{
      resultsByAnchor.forEach((nodes,anchorId)=>{
        if(!nodes.length)return;
        const anchor=nodeById.get(anchorId);
        if(!anchor)return;
        const root=nodeById.get(anchor._liveRootId)||orderedRoots[0];
        const box=root?root._liveCell:inner;
        placeResultCloud(nodes,anchor,box,anchor._liveAngle||-Math.PI/2,resultRadius,Math.floor(stableUnit(anchor.id,91)*10000));
      });

      rootDirect.forEach((nodes,rootId)=>{
        if(!nodes.length)return;
        const root=nodeById.get(rootId);
        if(!root)return;
        const box=root._liveCell;
        if(nodes.length<=18)ringNodes(nodes,root.x,root.y,Math.min(box.w,box.h)*.33,Math.floor(stableUnit(root.id,111)*10000),Math.PI/2);
        else gridNodes(nodes,{
          x:box.x+box.w*.16,
          y:box.y+box.h*.24,
          w:box.w*.68,
          h:box.h*.52
        },resultRadius,Math.floor(stableUnit(root.id,111)*10000));
      });
    }

    if(orphanResults.length){
      gridNodes(orphanResults,{
        x:inner.x+inner.w*.12,
        y:inner.y+inner.h*.16,
        w:inner.w*.76,
        h:inner.h*.62
      },resultRadius,149);
    }

    compactLiveConnections(graph,inner);
    enforceLiveNodeSeparation(graph,inner);
    reduceLiveEdgeCrossings(graph);
  }

  function compactLiveConnections(graph,box){
    if(!graph?.nodes?.length||!graph?.links?.length)return;
    const byId=new Map(graph.nodes.map(node=>[node.id,node]));
    const adjacent=new Map(graph.nodes.map(node=>[node.id,[]]));
    graph.links.forEach(link=>{
      const a=typeof link.source==="object"?link.source.id:link.source;
      const b=typeof link.target==="object"?link.target.id:link.target;
      if(byId.has(a)&&byId.has(b)){adjacent.get(a).push(byId.get(b));adjacent.get(b).push(byId.get(a));}
    });
    const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
    for(let pass=0;pass<5;pass++){
      const next=new Map();
      graph.nodes.forEach(node=>{
        const neighbours=adjacent.get(node.id)||[];
        if(!neighbours.length)return;
        const cx=neighbours.reduce((sum,n)=>sum+n.x,0)/neighbours.length;
        const cy=neighbours.reduce((sum,n)=>sum+n.y,0)/neighbours.length;
        const weight=node.liveContextDepth===2?.07:node.liveContextDepth===1?.15:.30;
        const radius=nodeRadius(node)+2;
        next.set(node.id,{
          x:clamp(node.x+(cx-node.x)*weight,box.x+radius,box.x+box.w-radius),
          y:clamp(node.y+(cy-node.y)*weight,box.y+radius,box.y+box.h-radius)
        });
      });
      next.forEach((point,id)=>{const node=byId.get(id);node.x=node.fx=point.x;node.y=node.fy=point.y;});
    }
  }

  function reduceLiveEdgeCrossings(graph){
    if(!graph?.links?.length||graph.links.length>180)return;
    const byId=new Map((graph.nodes||[]).map(node=>[node.id,node]));
    const endpoint=value=>typeof value==="object"?value:byId.get(value);
    const layer=node=>node?.liveContextDepth===2?"context-root":node?.liveContextDepth===1?"context-child":node?.liveGeneratedPos?"generated":node?.type||"other";
    const ccw=(a,b,c)=>(c.y-a.y)*(b.x-a.x)>(b.y-a.y)*(c.x-a.x);
    const crosses=(a,b,c,d)=>ccw(a,c,d)!==ccw(b,c,d)&&ccw(a,b,c)!==ccw(a,b,d);
    const links=graph.links.map(link=>({a:endpoint(link.source),b:endpoint(link.target)})).filter(edge=>edge.a&&edge.b);
    const independent=(e1,e2)=>e1.a!==e2.a&&e1.a!==e2.b&&e1.b!==e2.a&&e1.b!==e2.b;
    const crossingCount=()=>{
      let count=0;
      for(let i=0;i<links.length;i++)for(let j=i+1;j<links.length;j++){
        if(independent(links[i],links[j])&&crosses(links[i].a,links[i].b,links[j].a,links[j].b))count++;
      }
      return count;
    };
    const swap=(a,b)=>{
      const x=a.x,y=a.y;
      a.x=a.fx=b.x;a.y=a.fy=b.y;
      b.x=b.fx=x;b.y=b.fy=y;
    };

    let current=crossingCount();
    for(let pass=0;pass<10&&current>0;pass++){
      const candidates=new Map();
      for(let i=0;i<links.length&&candidates.size<80;i++)for(let j=i+1;j<links.length&&candidates.size<80;j++){
        const e1=links[i],e2=links[j];
        if(!independent(e1,e2)||!crosses(e1.a,e1.b,e2.a,e2.b))continue;
        for(const [a,b] of [[e1.a,e2.a],[e1.a,e2.b],[e1.b,e2.a],[e1.b,e2.b]]){
          if(a===b||layer(a)!==layer(b))continue;
          const key=String(a.id)<String(b.id)?`${a.id}|${b.id}`:`${b.id}|${a.id}`;
          candidates.set(key,[a,b]);
        }
      }

      let best=null,bestCount=current;
      for(const pair of candidates.values()){
        swap(pair[0],pair[1]);
        const count=crossingCount();
        swap(pair[0],pair[1]);
        if(count<bestCount){best=pair;bestCount=count;if(count===0)break;}
      }
      if(!best)break;
      swap(best[0],best[1]);
      current=bestCount;
    }
  }


  function enforceLiveNodeSeparation(graph,box){
    if(!graph?.nodes?.length)return;
    const nodes=graph.nodes.filter(node=>Number.isFinite(node.x)&&Number.isFinite(node.y));
    if(nodes.length<2)return;

    // A hard post-layout packing pass. Structural context is placed first so
    // Group/Team anchors move as little as possible; result dots fill the
    // nearest available positions around them. No two circles are allowed to
    // overlap, even for dense 1,248-player result views.
    const clearance=nodes.length>1000?.7:nodes.length>500?.9:nodes.length>180?1.2:nodes.length>60?1.7:2.4;
    const maxR=Math.max(...nodes.map(node=>nodeRadius(node)),1);
    const cellSize=Math.max(4,(maxR*2)+clearance+1);
    const grid=new Map();
    const key=(cx,cy)=>`${cx},${cy}`;
    const cellFor=(x,y)=>[Math.floor((x-box.x)/cellSize),Math.floor((y-box.y)/cellSize)];
    const add=(node,x,y)=>{
      const [cx,cy]=cellFor(x,y);
      const k=key(cx,cy);
      if(!grid.has(k))grid.set(k,[]);
      grid.get(k).push({node,x,y,r:nodeRadius(node)});
    };
    const collides=(node,x,y)=>{
      const r=nodeRadius(node);
      const [cx,cy]=cellFor(x,y);
      const reach=Math.max(1,Math.ceil((r+maxR+clearance)/cellSize));
      for(let gx=cx-reach;gx<=cx+reach;gx++){
        for(let gy=cy-reach;gy<=cy+reach;gy++){
          for(const other of grid.get(key(gx,gy))||[]){
            const minDist=r+other.r+clearance;
            const dx=x-other.x,dy=y-other.y;
            if(dx*dx+dy*dy<minDist*minDist-1e-6)return true;
          }
        }
      }
      return false;
    };
    const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));

    const ordered=[...nodes].sort((a,b)=>{
      const ap=a.liveContextDepth===2?0:a.liveContextDepth===1?1:a.liveQueryResult?2:3;
      const bp=b.liveContextDepth===2?0:b.liveContextDepth===1?1:b.liveQueryResult?2:3;
      return ap-bp||String(a.id).localeCompare(String(b.id));
    });

    ordered.forEach((node,index)=>{
      const r=nodeRadius(node);
      const minX=box.x+r+1,maxX=box.x+box.w-r-1;
      const minY=box.y+r+1,maxY=box.y+box.h-r-1;
      const preferredX=clamp(node.x,minX,maxX);
      const preferredY=clamp(node.y,minY,maxY);
      let placedX=preferredX,placedY=preferredY,found=!collides(node,preferredX,preferredY);

      if(!found){
        const step=Math.max(1.35,r*1.1+clearance*.72);
        const diagonal=Math.hypot(box.w,box.h);
        const maxRing=Math.ceil(diagonal/step)+2;
        // Deterministic phase keeps layouts stable between repeated runs.
        const phase=stableUnit(node.id,index+211)*Math.PI*2;
        outer: for(let ring=1;ring<=maxRing;ring++){
          const dist=ring*step;
          const samples=Math.max(12,Math.ceil(Math.PI*2*dist/Math.max(3,(r*2)+clearance)));
          for(let i=0;i<samples;i++){
            const angle=phase+(Math.PI*2*i/samples);
            const x=preferredX+Math.cos(angle)*dist;
            const y=preferredY+Math.sin(angle)*dist;
            if(x<minX||x>maxX||y<minY||y>maxY)continue;
            if(!collides(node,x,y)){
              placedX=x;placedY=y;found=true;
              break outer;
            }
          }
        }
      }

      // The graph has far more drawable area than circle area, so this branch
      // should not be reached. If it ever is, scan the frame deterministically
      // rather than accepting an overlap.
      if(!found){
        const scanStep=Math.max(2,(r*2)+clearance);
        outerScan: for(let y=minY;y<=maxY;y+=scanStep){
          for(let x=minX;x<=maxX;x+=scanStep){
            if(!collides(node,x,y)){
              placedX=x;placedY=y;found=true;
              break outerScan;
            }
          }
        }
      }

      node.x=node.fx=placedX;
      node.y=node.fy=placedY;
      add(node,placedX,placedY);
    });
  }

  function liveLabelMetrics(node){
    if(Number.isFinite(node.liveLabelFont)){
      const font=node.liveLabelFont;
      return {font,height:font*1.38};
    }
    if(node.liveContextDepth===2)return {font:10,height:13};
    if(node.liveContextDepth===1)return {font:7.5,height:10.5};
    return {font:8.8,height:12};
  }

  function liveLabelEstimatedWidth(node,fontOverride=null){
    const font=fontOverride||liveLabelMetrics(node).font;
    const text=String(node.label||node.id||"");
    // Intentionally conservative: this is wider than the average rendered
    // sans-serif glyph, so a successful estimated layout stays separated after
    // the browser renders the actual text.
    return Math.max(18,text.length*font*.72+5);
  }

  function liveLabelBox(node,side,dy=0){
    const metrics=liveLabelMetrics(node);
    const width=liveLabelEstimatedWidth(node);
    if(Number.isFinite(node.liveLabelAbsX)&&Number.isFinite(node.liveLabelAbsY)){
      return {
        left:node.liveLabelAbsX,
        right:node.liveLabelAbsX+width,
        top:node.liveLabelAbsY-metrics.height*.78,
        bottom:node.liveLabelAbsY+metrics.height*.28
      };
    }
    const gap=Math.max(5,nodeRadius(node)+5);
    const cy=(Number.isFinite(node.y)?node.y:0)+dy;
    const left=side==="left"
      ? (Number.isFinite(node.x)?node.x:0)-gap-width
      : (Number.isFinite(node.x)?node.x:0)+gap;
    return {
      left,
      right:left+width,
      top:cy-metrics.height*.78,
      bottom:cy+metrics.height*.28
    };
  }

  function liveLabelBoxesOverlap(a,b,gap=3){
    return a.left<b.right+gap&&a.right+gap>b.left&&a.top<b.bottom+gap&&a.bottom+gap>b.top;
  }

  function packMandatoryLiveLabels(nodes,width,height){
    if(!nodes.length)return;
    const edgePad=6;
    const availableW=Math.max(120,width-edgePad*2);
    const availableH=Math.max(100,height-edgePad*2);
    const fonts=[8.6,8.1,7.6,7.1,6.6,6.2];
    let layout=null;

    for(const font of fonts){
      const maxTextW=Math.max(...nodes.map(node=>liveLabelEstimatedWidth(node,font)),28);
      const labelH=font*1.38;
      for(let cols=1;cols<=Math.min(12,nodes.length);cols++){
        const rows=Math.ceil(nodes.length/cols);
        const cellW=availableW/cols;
        const cellH=availableH/rows;
        if(cellW<maxTextW+8||cellH<labelH+5)continue;
        const score=Math.abs((cellW/cellH)-2.8)+rows*.002;
        if(!layout||score<layout.score)layout={font,cols,rows,cellW,cellH,score};
      }
      if(layout)break;
    }

    if(!layout){
      const font=6;
      let best=null;
      for(let cols=1;cols<=Math.min(14,nodes.length);cols++){
        const rows=Math.ceil(nodes.length/cols);
        const cellW=availableW/cols,cellH=availableH/rows;
        const maxTextW=Math.max(...nodes.map(node=>liveLabelEstimatedWidth(node,font)),28);
        const fit=Math.min(cellW/(maxTextW+6),cellH/(font*1.38+4));
        if(!best||fit>best.fit)best={font,cols,rows,cellW,cellH,fit};
      }
      layout=best;
    }

    const slots=[];
    for(let r=0;r<layout.rows;r++){
      for(let c=0;c<layout.cols;c++){
        if(slots.length>=nodes.length)break;
        const left=edgePad+c*layout.cellW;
        const top=edgePad+r*layout.cellH;
        slots.push({
          x:left+4,
          y:top+layout.cellH*.62,
          cx:left+layout.cellW*.5,
          cy:top+layout.cellH*.5
        });
      }
    }

    const remaining=[...slots];
    [...nodes]
      .sort((a,b)=>String(a.label||a.id).localeCompare(String(b.label||b.id)))
      .forEach(node=>{
        let bestIndex=0,bestDistance=Infinity;
        remaining.forEach((slot,index)=>{
          const dx=(node.x||0)-slot.cx,dy=(node.y||0)-slot.cy;
          const distance=dx*dx+dy*dy;
          if(distance<bestDistance){bestDistance=distance;bestIndex=index;}
        });
        const slot=remaining.splice(bestIndex,1)[0];
        node.liveShowLabel=true;
        node.liveLabelFont=layout.font;
        node.liveLabelAbsX=slot.x;
        node.liveLabelAbsY=slot.y;
        node.liveLabelDy=0;
        node.labelSide="right";
      });
  }

  function resolveLiveLabelCollisions(graph,resultCount){
    const stage=$("graphStage");
    const width=stage?.clientWidth||760;
    const height=stage?.clientHeight||570;
    const edgePad=4;
    const placed=[];

    graph.nodes.forEach(node=>{
      node.liveShowLabel=false;
      node.liveLabelDy=0;
      node.liveLabelAbsX=null;
      node.liveLabelAbsY=null;
      node.liveLabelFont=Number.isFinite(node.liveVisualFont)?node.liveVisualFont:null;
    });

    // Small graphs can keep every label. For denser result sets, readability
    // wins: labels that cannot be placed without a collision remain available
    // through the existing hover/inspect interaction.
    const allDisplayedMandatory=graph.nodes.length<=14;
    const resultLabelsMandatory=resultCount<=14;
    state.liveLabelsMandatory=allDisplayedMandatory||resultLabelsMandatory;

    const structural=graph.nodes
      .filter(node=>node.liveContextDepth&&node.type!=="Athlete")
      .sort((a,b)=>(b.liveContextDepth||0)-(a.liveContextDepth||0)||String(a.label||a.id).localeCompare(String(b.label||b.id)));

    let candidates;
    if(allDisplayedMandatory){
      candidates=[...graph.nodes].sort((a,b)=>String(a.label||a.id).localeCompare(String(b.label||b.id)));
    }else if(resultLabelsMandatory){
      const seen=new Set();
      candidates=[...structural,...graph.nodes.filter(node=>node.liveQueryResult)]
        .filter(node=>!seen.has(node.id)&&seen.add(node.id));
    }else{
      const profile=liveVisualProfile(resultCount,graph.nodes.length);
      const results=graph.nodes
        .filter(node=>node.liveQueryResult)
        .sort((a,b)=>String(a.label||a.id).localeCompare(String(b.label||b.id)))
        .slice(0,profile.maxResultLabels);
      const seen=new Set();
      candidates=[...structural,...results]
        .filter(node=>!seen.has(node.id)&&seen.add(node.id));
    }

    const mandatoryIds=new Set(state.liveLabelsMandatory?candidates.map(node=>node.id):[]);
    const sideOrder=node=>{
      if(node.labelSide==="left")return ["left","right"];
      if(node.labelSide==="right")return ["right","left"];
      return (node.x||0)>width/2?["right","left"]:["left","right"];
    };
    const dyAttempts=[0,-8,8,-16,16];
    const nodeObstacles=graph.nodes.map(node=>{
      const radius=nodeRadius(node)+2;
      return {id:node.id,left:node.x-radius,right:node.x+radius,top:node.y-radius,bottom:node.y+radius};
    });
    let mandatoryPlaced=0;

    candidates.forEach(node=>{
      let chosen=null;
      for(const side of sideOrder(node)){
        for(const dy of dyAttempts){
          const box=liveLabelBox(node,side,dy);
          if(box.left<edgePad||box.right>width-edgePad||box.top<edgePad||box.bottom>height-edgePad)continue;
          if(placed.some(other=>liveLabelBoxesOverlap(box,other,4)))continue;
          if(nodeObstacles.some(other=>other.id!==node.id&&liveLabelBoxesOverlap(box,other,1)))continue;
          chosen={box,side,dy};
          break;
        }
        if(chosen)break;
      }
      if(!chosen)return;
      node.liveShowLabel=true;
      node.labelSide=chosen.side;
      node.liveLabelDy=chosen.dy;
      placed.push(chosen.box);
      if(mandatoryIds.has(node.id))mandatoryPlaced+=1;
    });

    if(state.liveLabelsMandatory&&mandatoryPlaced<mandatoryIds.size){
      // Tiny graphs should retain every label. Use the compact slot layout
      // rather than knowingly drawing labels on top of one another.
      packMandatoryLiveLabels(candidates,width,height);
    }
  }

  function pruneRenderedLiveLabelOverlaps(){
    if(!state.nodeSel)return;
    // Under the <60 hard-label rule, hiding is forbidden. The conservative
    // pre-layout collision pass (and slot fallback when needed) owns spacing.
    if(state.liveLabelsMandatory)return;
    const items=[];
    state.nodeSel.each(function(d){
      if(!d.liveShowLabel)return;
      const text=this.querySelector("text");
      if(!text)return;
      let box;
      try{box=text.getBBox()}catch(_){return}
      if(!box||!box.width||!box.height)return;
      const priority=d.liveContextDepth===2?0:d.liveContextDepth===1?1:2;
      items.push({d,el:this,box:{left:box.x,right:box.x+box.width,top:box.y,bottom:box.y+box.height},priority});
    });
    items.sort((a,b)=>a.priority-b.priority||String(a.d.label||a.d.id).localeCompare(String(b.d.label||b.d.id)));
    const placed=[];
    items.forEach(item=>{
      if(placed.some(other=>liveLabelBoxesOverlap(item.box,other,4))){
        item.d.liveShowLabel=false;
        NativeGraph.select(item.el).classed("live-show-label",false);
        return;
      }
      placed.push(item.box);
    });
  }

  function highlightLiveQueryResult(result){
    const fullGraph=cloneGraph(currentDb().graph);
    const resultIds=new Set();
    const idsByLabel=new Map();
    fullGraph.nodes.forEach(node=>{
      const labels=[node.label,...(node.aliases||[])];
      labels.forEach(label=>{
        const key=String(label||"").trim().toLocaleLowerCase();
        if(!key)return;
        if(!idsByLabel.has(key))idsByLabel.set(key,new Set());
        idsByLabel.get(key).add(node.id);
      });
    });
    result.rows.forEach(row=>Object.values(row).forEach(value=>{
      if(value?.kind==="iri"){
        const id=nodeIdForIri(value.value);
        if(id)resultIds.add(id);
        return;
      }
      if(value?.kind==="literal"){
        const key=String(value.value||"").trim().toLocaleLowerCase();
        (idsByLabel.get(key)||[]).forEach(id=>resultIds.add(id));
      }
    }));

    state.resultIds=resultIds;
    state.selectedId=null;
    state.tooltipPinnedId=null;

    if(!resultIds.size){
      state.contextIds=new Set();
      state.focusMode=false;
      state.graph=fullGraph;
      rerenderCurrentGraphWithoutReset();
      $("graphStatus").textContent="No result value matched a KG entity · full graph retained";
      return;
    }

    const {depth1,depth2}=liveRelatedContext(fullGraph,resultIds);
    const contextIds=new Set([...depth1,...depth2]);
    state.contextIds=contextIds;
    state.focusMode=true;

    const keep=new Set([...resultIds,...contextIds]);
    state.graph={
      nodes:fullGraph.nodes.filter(n=>keep.has(n.id)),
      links:fullGraph.links.filter(link=>{
        const a=typeof link.source==="object"?link.source.id:link.source;
        const b=typeof link.target==="object"?link.target.id:link.target;
        return keep.has(a)&&keep.has(b);
      }).map(link=>({...link}))
    };

    const graphNodeById=new Map(state.graph.nodes.map(n=>[n.id,n]));
    state.graph.nodes.forEach(node=>{
      if(resultIds.has(node.id))node.liveQueryResult=true;
      else if(depth1.has(node.id))node.liveContextDepth=1;
      else if(depth2.has(node.id))node.liveContextDepth=2;
    });
    state.graph.links.forEach(link=>{
      const a=typeof link.source==="object"?link.source.id:link.source;
      const b=typeof link.target==="object"?link.target.id:link.target;
      const na=graphNodeById.get(a),nb=graphNodeById.get(b);
      if(resultIds.has(a)&&resultIds.has(b))link.liveQueryLinkDepth=0;
      else if(na?.liveContextDepth===2||nb?.liveContextDepth===2)link.liveQueryLinkDepth=2;
      else link.liveQueryLinkDepth=1;
    });

    layoutLiveRelatedGraph(state.graph,resultIds,depth1,depth2);
    resolveLiveLabelCollisions(state.graph,resultIds.size);
    rerenderCurrentGraphWithoutReset();
    tick();
    state.simulation.alpha(0).stop();
    applyClasses();
    pruneRenderedLiveLabelOverlaps();
    updateStatus();
    NativeGraph.select("#kgGraph").call(state.zoom.transform,NativeGraph.zoomIdentity);
    requestAnimationFrame(()=>{tick();fitRelevantGraph()});
  }

  function hashString(text){
    let h=2166136261;
    for(const ch of String(text)){h^=ch.charCodeAt(0);h=Math.imul(h,16777619);}
    return h>>>0;
  }

  function redistributeLiveQueryGraph(){
    if(!state.graph?.nodes?.length)return;
    const depth1=new Set(state.graph.nodes.filter(n=>n.liveContextDepth===1).map(n=>n.id));
    const depth2=new Set(state.graph.nodes.filter(n=>n.liveContextDepth===2).map(n=>n.id));
    const resultIds=new Set([...state.resultIds].filter(id=>state.graph.nodes.some(n=>n.id===id)));
    layoutLiveRelatedGraph(state.graph,resultIds,depth1,depth2);

    const generatedBySource=new Map();
    state.graph.nodes.filter(n=>n.liveGeneratedPos&&n.sourceAthleteId).forEach(node=>{
      const key=node.sourceAthleteId;
      if(!generatedBySource.has(key))generatedBySource.set(key,[]);
      generatedBySource.get(key).push(node);
    });
    generatedBySource.forEach((nodes,sourceId)=>{
      const source=state.graph.nodes.find(n=>n.id===sourceId);
      if(!source)return;
      const baseAngle=source._liveAngle||stableUnit(source.id,411)*Math.PI*2;
      const distance=Math.max(11,nodeRadius(source)+8);
      const ordered=[...nodes].sort((a,b)=>String(a.generatedVariable||a.id).localeCompare(String(b.generatedVariable||b.id))||String(a.label||a.id).localeCompare(String(b.label||b.id)));
      ordered.forEach((node,index)=>{
        const angle=baseAngle+((ordered.length===1?0:(index-(ordered.length-1)/2))*.58);
        node.x=node.fx=(source.x||0)+Math.cos(angle)*distance;
        node.y=node.fy=(source.y||0)+Math.sin(angle)*distance;
        node.labelSide=Math.cos(angle)<-.12?"left":"right";
        node.liveQueryResult=true;
        node.liveShowLabel=false;
      });
    });

    const stage=$("graphStage"),pad=10;
    arrangeFourReturnedPairs();
    enforceLiveNodeSeparation(state.graph,{x:pad,y:pad,w:Math.max(120,(stage?.clientWidth||760)-pad*2),h:Math.max(100,(stage?.clientHeight||570)-pad*2)});
    arrangeFourReturnedPairs();
    reduceLiveEdgeCrossings(state.graph);
    resolveLiveLabelCollisions(state.graph,state.resultIds.size);
    rerenderCurrentGraphWithoutReset();
    tick();
    state.simulation.alpha(0).stop();
    applyClasses();
    pruneRenderedLiveLabelOverlaps();
    requestAnimationFrame(()=>{tick();fitRelevantGraph()});
  }

  function addLiveGeneratedNodes(result){
    const generatedVars=result?.generatedVars||[];
    if(!generatedVars.length||!state.graph?.nodes?.length)return;
    const nodeById=new Map(state.graph.nodes.map(n=>[n.id,n]));
    const nodeByLabel=new Map();
    state.graph.nodes.forEach(node=>[node.label,...(node.aliases||[])].forEach(label=>{
      const key=String(label||"").trim().toLocaleLowerCase();
      if(key&&!nodeByLabel.has(key))nodeByLabel.set(key,node);
    }));
    const added=[],links=[],seen=new Set();
    result.rows.forEach((row,rowIndex)=>{
      let source=null;
      for(const value of Object.values(row)){
        if(value?.kind==="iri"){
          const id=nodeIdForIri(value.value);
          if(id&&nodeById.has(id)){source=nodeById.get(id);break;}
        }else if(value?.kind==="literal"){
          const key=String(value.value||"").trim().toLocaleLowerCase();
          if(nodeByLabel.has(key)){source=nodeByLabel.get(key);break;}
        }
      }
      if(!source)return;
      generatedVars.forEach((variable,varIndex)=>{
        const value=row[variable];
        if(!value)return;
        const key=`${source.id}|${variable}|${value.value}`;
        if(seen.has(key))return;
        seen.add(key);
        const angle=stableUnit(key,307)*Math.PI*2;
        const distance=Math.max(10,nodeRadius(source)+7);
        const id=`live:genop:${rowIndex}:${varIndex}:${hashString(key)}`;
        const node={
          id,label:String(value.value),aliases:[String(value.value)],type:"Generated",
          generatedAttribute:true,liveGeneratedPos:true,generatedVariable:variable,
          sourceAthleteId:source.id,sourceAthleteLabel:source.label,positionLabel:String(value.value),
          liveGeneratedRadius:3.0,liveQueryResult:true,
          x:(source.x||0)+Math.cos(angle)*distance,
          y:(source.y||0)+Math.sin(angle)*distance
        };
        node.fx=node.x;node.fy=node.y;
        added.push(node);
        links.push({source:source.id,target:id,predicate:`?${variable}`,generatedAttributeLink:true,hideGeneratedEdgeLabel:false,liveGeneratedLink:true,liveQueryLinkDepth:0});
      });
    });
    if(!added.length)return;
    state.graph.nodes.push(...added);
    state.graph.links.push(...links);
    added.forEach(node=>state.resultIds.add(node.id));
    redistributeLiveQueryGraph();
  }

  function initLiveQuery(){
    const editor=$("liveQuery");
    if(!editor)return;
    const livePrefixes=`PREFIX ex: <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

`;
    const samples={
      playerPositionAndGoals:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

# Ask the LLM for two player attributes in one call.
SELECT ?name ?position ?goals
WHERE {
  ?player a ex:Athlete ;
          rdfs:label ?name .

  FILTER(?name = "Lionel Messi")

  GENOP(
    "For {?name} at the FIFA World Cup 2026, return the player's football position and number of goals.",
    (?position, ?goals),
    <model>
  )
}`,
      argentinaPositions:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX wd:   <http://www.wikidata.org/entity/>

# Return every Argentina player and generate the position missing from the KG.
SELECT ?name ?position
WHERE {
  ?player a ex:Athlete ;
          ex:playsFor wd:Q79800 ;
          rdfs:label ?name .

  GENOP(
    "What football position does {?name} play for Argentina? Return only the position.",
    (?position),
    <model>
  )
}
ORDER BY ?position ?name`,
      topRankedTeams:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>

# Which players play for teams ranked in the top 10 of the current FIFA Men's World Ranking?
SELECT ?name ?teamName ?ranking
WHERE {
  ?player ex:playsFor ?team ;
          rdfs:label ?name .

  ?team rdfs:label ?teamName .

  GENOP(
    "What is the current FIFA men's world ranking of {?teamName}? Return only an integer.",
    (?ranking),
    <model>
  )

  FILTER(xsd:integer(?ranking) <= 10)
}`,
      worldCupGoals:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>

# List all players and show their goals if they scored at least once.
SELECT ?name ?teamName ?goals
WHERE {
  ?player ex:playsFor ?team ;
          rdfs:label ?name .

  ?team rdfs:label ?teamName .

  OPTIONAL {
    GENOP(
      "How many goals did {?name} score at the FIFA World Cup 2026? Return only an integer.",
      (?goals),
      <model>
    )

    FILTER(xsd:integer(?goals) > 0)
  }
}
ORDER BY DESC(xsd:integer(?goals))`,
      spainMatches:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

# Which teams did Spain play, and what was each result?
SELECT ?opponentName ?result
WHERE {
  ?opponent a ex:Team ;
            rdfs:label ?opponentName .

  FILTER(LCASE(STR(?opponentName)) != "spain")

  GENOP(
    "Did Spain play against {?opponentName} at the FIFA World Cup 2026? Return only Yes or No.",
    (?played),
    <model>
  )

  FILTER(?played = "Yes")

  GENOP(
    "What was the result of Spain against {?opponentName} at the FIFA World Cup 2026? Return only the score in the format Spain X-Y Opponent.",
    (?result),
    <model>
  )
}`,
      spainArgentinaScorers:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
PREFIX wd:   <http://www.wikidata.org/entity/>

# Who scored for Spain or Argentina at the 2026 FIFA World Cup?
SELECT ?name ?teamName ?goals
WHERE {
  {
    ?player a ex:Athlete ;
            ex:playsFor wd:Q42267 ;
            rdfs:label ?name .

    wd:Q42267 rdfs:label ?teamName .
  }
  UNION
  {
    ?player a ex:Athlete ;
            ex:playsFor wd:Q79800 ;
            rdfs:label ?name .

    wd:Q79800 rdfs:label ?teamName .
  }

  GENOP(
    "How many goals did {?name} score at the FIFA World Cup 2026? Return only an integer.",
    (?goals),
    <model>
  )

  FILTER(xsd:integer(?goals) > 0)
}
ORDER BY DESC(xsd:integer(?goals))`,
      nobelQuantumLaureates:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT DISTINCT ?laureate ?year
WHERE {
  {
    ?person a ex:NobelLaureate ;
            rdfs:label ?laureate ;
            ex:wonNobelPrize ?award .

    ?award ex:field <http://www.wikidata.org/entity/Q38104> ;
           ex:winningYear ?year .
  }

  {
    GENOP(
      "Nobel Prize in Physics {?laureate} who made foundational contributions to quantum mechanics. Return only the names ",
      (?laureate),
      <model>,
      0.0
    )
  }
}
ORDER BY ?year`
    };
    editor.value=samples.playerPositionAndGoals;
    editor.addEventListener("keydown",event=>{
      if(event.key==="Tab"){
        event.preventDefault();
        const a=editor.selectionStart,b=editor.selectionEnd;
        editor.setRangeText("  ",a,b,"end");
      }
      if((event.ctrlKey||event.metaKey)&&event.key==="Enter"){
        event.preventDefault();$("liveRunBtn").click();
      }
    });
    $("loadSampleQuery").onclick=()=>{
      const key=$("liveSampleSelect")?.value||"playerPositionAndGoals";
      editor.value=samples[key]||samples.playerPositionAndGoals;
      editor.focus();
    };
    $("clearLiveQuery").onclick=()=>{editor.value="";editor.focus();$("liveQueryResult").hidden=true};
    $("liveRunBtn").onclick=async()=>{
      clearMessage();
      const btn=$("liveRunBtn");
      btn.disabled=true;btn.textContent="Running…";
      try{
        const db=currentDb();
        const prefixes={...BUILTIN_PREFIXES,...(db.prefixes||{})};
        const engine=engineClient();
        if(!engine)throw new Error("engine-client.js failed to load.");
        engine.setApiBase($("engineBase")?.value||engine.getApiBase());
        const datasetId=await ensureJavaDataset(db);
        setEngineStatus(`Executing complete query with Java engine · dataset ${datasetId}`);
        const llm={
          apiKey:$("key")?.value||"",
          baseUrl:$("chatBase")?.value?.trim()||"",
          model:$("chatModel")?.value?.trim()||""
        };
        const similarity=$("groundEmbedding")?.checked?{
          method:"embedding",
          apiKey:$("key")?.value||"",
          baseUrl:$("embBase")?.value?.trim()||"",
          model:$("embModel")?.value?.trim()||""
        }:{method:"jaccard"};
        const result=await engine.query(editor.value,{datasetId,prefixes,includeTrace:true,llm,similarity});
        const executorLabel="Java engine";
        setEngineStatus(`Java query completed · ${result.total} row${result.total===1?"":"s"}`,"ok");

        renderLiveQueryResult(result);
        highlightLiveQueryResult(result);
        addLiveGeneratedNodes(result);
        const opText=result.operators?.length?` · operators: ${[...new Set(result.operators)].join(", ")}`:"";
        const genopText=result.genopCalls?` · ${result.genopCalls} GENOP call${result.genopCalls===1?"":"s"}`:"";
        showMessage(`${executorLabel} completed on ${db.label}: ${result.total} row${result.total===1?"":"s"}${opText}${genopText}. Matching entities are shown in the graph.`,"ok");
      }catch(err){
        $("liveQueryResult").hidden=true;
        showMessage(`Query failed: ${err.message||err}`,"err");
      }finally{
        btn.disabled=false;btn.textContent="Run query ▶";
      }
    };
  }

  function initTheme(){
    $("themeBtn").onclick=()=>{
      const r=document.documentElement;
      const now=r.getAttribute("data-theme")||(matchMedia("(prefers-color-scheme:dark)").matches?"dark":"light");
      r.setAttribute("data-theme",now==="dark"?"light":"dark");
    };
  }

  function liveOn(){return $("liveBox").open&&$("key").value.trim().length>0}

  function initLive(){
    $("chatBase").value=CFG.live.chatBase;
    $("chatModel").value=CFG.live.chatModel;
    $("embBase").value=CFG.live.embBase;
    CFG.live.embModels.forEach(m=>{const o=document.createElement("option");o.value=o.textContent=m;$("embModel").appendChild(o)});
    $("key").addEventListener("input",renderScenario);
    $("liveBox").addEventListener("toggle",renderScenario);
    // Both radios need their own handler: a change event only fires on the
    // input that became checked.
    const onGroundingMethodChange=async()=>{
      syncGrounding();
      if(currentScenario()?.id==="entity"&&state.graph?.nodes.some(n=>n.generatedCandidate)){
        // No second Run required: recompute pass/fail and redraw relation edges live.
        try{
          await buildGroundEntityView({preserveViewport:true});
        }catch(err){
          showMessage(runFailureMessage(err),"err");
        }
      }
    };
    $("groundText").onchange=onGroundingMethodChange;
    $("groundEmbedding").onchange=onGroundingMethodChange;
    syncGrounding();
  }

  function syncGrounding(){
    const on=$("groundEmbedding").checked;
    $("embeddingSettings").classList.toggle("is-disabled",!on);
    $("embBase").disabled=!on;$("embModel").disabled=!on;
  }

  function showMessage(text,kind="ok"){
    $("message").textContent=text;$("message").className="message show "+kind;
    const live=$("liveRunFeedback");
    if(live){live.textContent=text;live.className="live-run-feedback "+kind;}
  }
  function clearMessage(){
    $("message").className="message";$("message").textContent="";
    const live=$("liveRunFeedback");
    if(live){live.textContent="";live.className="live-run-feedback";}
  }

  function initGraph(){
    if(typeof NativeGraph==="undefined"){
      $("graphStatus").textContent="Native graph renderer failed to load.";return;
    }
    if(state.simulation)state.simulation.stop();

    state.graph=graphForInitialDisplay();
    state.resultIds.clear();state.contextIds.clear();state.selectedId=null;state.focusMode=false;state.tooltipPinnedId=null;
    state.compositionStageMeta=null;state.compositionStrategy=null;state.compositionStep=null;

    const svg=NativeGraph.select("#kgGraph");svg.selectAll("*").remove();
    const width=$("graphStage").clientWidth||760,height=$("graphStage").clientHeight||570;
    layoutNobelOverview(state.graph,width,height);
    layoutFullNobelGraph(state.graph,width,height);
    state.zoomLayer=svg.append("g");
    const linkLayer=state.zoomLayer.append("g").attr("class","links");
    const labelLayer=state.zoomLayer.append("g").attr("class","edge-labels");
    const nodeLayer=state.zoomLayer.append("g").attr("class","nodes");

    state.linkSel=linkLayer.selectAll("line")
      .data(state.graph.links)
      .join("line")
      .attr("class",d=>linkClass(d));

    state.generatedLabelSel=labelLayer.selectAll("text")
      .data(state.graph.links.filter(d=>d.generatedAttributeLink&&!d.hideGeneratedEdgeLabel))
      .join("text")
      .attr("class","generated-edge-label")
      .text(d=>d.predicate||"?generated");

    state.nodeSel=nodeLayer.selectAll("g")
      .data(state.graph.nodes,d=>d.id)
      .join("g")
      .attr("class",d=>nodeClass(d));

    state.nodeSel.append("circle").attr("r",nodeRadius);
    styleNodeCircles(state.nodeSel);
    // Large built-in KGs use different leaf types (Athlete in FIFA,
    // NobelLaureate/NobelAward in Nobel). Keep only structural labels in the
    // overview; focused query results rebuild a smaller graph with result labels.
    const overviewGraph=state.graph.nodes.length>300&&!state.focusMode;
    const staticNobelOverview=state.dbKey==="nobelPrize"&&(state.graph.nobelCompactOverview||state.graph.nodes.length>1000)&&!state.focusMode;
    const overviewLeafTypes=new Set(["Athlete","NobelLaureate","NobelAward","CountryOrCitizenship"]);
    state.nodeSel.filter(d=>!overviewGraph||!overviewLeafTypes.has(d.type)).append("text")
      .attr("x",d=>nodeLabelX(d))
      .attr("text-anchor",d=>nodeLabelAnchor(d))
      .attr("y",nodeLabelY)
      .style("font-size",d=>Number.isFinite(d.liveLabelFont)?`${d.liveLabelFont}px`:null,"important")
      .text(d=>d.label);

    appendSimilarityBoxes(state.nodeSel);

    state.zoom=NativeGraph.zoom().scaleExtent([.12,6]).on("zoom",e=>state.zoomLayer.attr("transform",e.transform));
    svg.call(state.zoom).on("dblclick.zoom",null);

    state.nodeSel.call(NativeGraph.drag()
      .on("start",(e,d)=>{if(!staticNobelOverview&&!e.active)state.simulation.alphaTarget(.22).restart();d.fx=d.x;d.fy=d.y})
      .on("drag",(e,d)=>{d.fx=e.x;d.fy=e.y})
      .on("end",(e,d)=>{if(staticNobelOverview){state.simulation.stop();tick();return}if(!e.active)state.simulation.alphaTarget(0);if(!state.focusMode){d.fx=null;d.fy=null}}))
      .on("mouseenter",(e,d)=>showTip(e,d))
      .on("mousemove",e=>{if(!state.tooltipPinnedId)moveTip(e)})
      .on("mouseleave",()=>{if(!state.tooltipPinnedId)$("tooltip").hidden=true})
      .on("click",(e,d)=>{
        e.stopPropagation();
        if(state.graph?.nobelOverview&&d.type==="NobelField"){
          state.nobelExpandedField=state.nobelExpandedField===d.id?null:d.id;
          initGraph();
          return;
        }
        if(d.liveGeneratedPos||d.compositionPosNode){
          state.tooltipPinnedId=state.tooltipPinnedId===d.id?null:d.id;
          if(state.tooltipPinnedId){
            showTip(e,d);
          }else{
            $("tooltip").hidden=true;
          }
          return;
        }
        state.tooltipPinnedId=null;
        $("tooltip").hidden=true;
        state.selectedId=state.selectedId===d.id?null:d.id;
        applyClasses();
      });

    svg.on("click",()=>{state.tooltipPinnedId=null;$("tooltip").hidden=true;state.selectedId=null;applyClasses()});

    state.simulation=NativeGraph.forceSimulation(state.graph.nodes)
      .force("link",NativeGraph.forceLink(state.graph.links).id(d=>d.id).distance(d=>d.source?.type==="Athlete"||d.target?.type==="Athlete"?20:40).strength(.78))
      .force("charge",NativeGraph.forceManyBody().strength(state.graph.nodes.length>300?-42:-125))
      .force("center",NativeGraph.forceCenter(width/2,height/2))
      .force("collision",NativeGraph.forceCollide().radius(d=>nodeRadius(d)+3).iterations(1))
      .on("tick",tick);

    // A dense overview does not need
    // that level of precision and was keeping the main thread busy for several seconds.
    if(staticNobelOverview){
      // The circular Nobel overview already has fixed coordinates. Thousands
      // of additional force ticks only repeat SVG writes without changing it.
      state.simulation.alpha(0).stop();
      tick();
    }else if(overviewGraph){
      state.simulation.alphaDecay(.12).alphaMin(.03).velocityDecay(.5);
    }

    updateStatus();
    // Dense graphs settle in a short, aggressively decayed native simulation.
    // Fit as soon as that layout is useful instead of making the UI feel blocked.
    setTimeout(()=>fitGraph(false),staticNobelOverview?0:(overviewGraph?240:600));
  }

  function cssSafe(s){return String(s||"Entity").replace(/[^A-Za-z0-9_-]/g,"-")}
  function nodeClass(d){
    const parts=["node","type-"+cssSafe(d.type)];
    if(d.generatedAttribute){
      parts.push("generated-attribute");
      if(d.evaluationState==="wrong")parts.push("generated-wrong");
      if(d.evaluationState==="abstain")parts.push("generated-abstain");
    }
    if(d.generatedCandidate){
      parts.push("generated-candidate");
      parts.push(d.grounded?"candidate-grounded":"candidate-dropped");
    }
    if(d.outerCountry)parts.push("outer-country");
    if(d.groundedTarget)parts.push("grounded-target");
    else if(d.potentialGroundTarget)parts.push("potential-ground-target");
    if(d.hasGeneratedAttribute)parts.push("attribute-source");
    if(d.compositionDefender)parts.push("composition-defender");
    if(d.compositionSource)parts.push("composition-source");
    if(d.compositionTeam)parts.push("composition-team");
    if(d.compositionGroup)parts.push("composition-group");
    if(d.compositionPosNode)parts.push("composition-pos-node");
    if(d.compositionBindingPos)parts.push("composition-binding-pos");
    if(d.compositionDense)parts.push("composition-dense");
    if(d.compositionBindingCloud)parts.push("composition-binding-cloud");
    if(d.compositionBindingGroup)parts.push("composition-binding-group");
    if(d.compositionDropped)parts.push("composition-dropped");
    if(d.compositionSurvivor)parts.push("composition-survivor");
    if(d.liveGeneratedPos)parts.push("live-generated-pos");
    if(d.liveQueryResult)parts.push("live-query-result-node");
    if(d.liveContextDepth===1)parts.push("live-context-1");
    if(d.liveContextDepth===2)parts.push("live-context-2");
    if(d.liveShowLabel)parts.push("live-show-label");
    if(d.liveDenseResult)parts.push("live-dense-result");
    if(d.liveDenseContext)parts.push("live-dense-context");
    return parts.join(" ");
  }

  function linkClass(d){
    if(d.generatedAttributeLink){
      let cls="link generated-link";
      if(d.compositionDropLink)cls+=" composition-drop-link";
      if(d.compositionBindingPosLink)cls+=" composition-binding-pos-link";
      if(d.evaluationState==="wrong")cls+=" wrong";
      if(d.evaluationState==="abstain")cls+=" abstain";
      return cls;
    }
    if(d.groundingLink)return"link grounding-link";
    if(d.potentialGroundingLink)return"link potential-grounding-link";
    if(d.compositionBindingLink)return"link composition-binding-link";
    if(d.compositionPathLink)return"link composition-path-link";
    return"link";
  }

  function nodeRadius(d){
    if(d.nobelDenseOverview)return 1.45;
    if(d.type==="NobelPrize")return 10;
    if(d.type==="NobelField")return 7.5;
    if(d.type==="NobelAward")return 4.2;
    if(d.type==="NobelLaureate")return 3.8;
    if(d.liveGeneratedPos)return d.liveGeneratedRadius||3.1;
    if(d.liveQueryResult)return d.liveResultRadius||4;
    if(d.liveContextDepth)return d.liveContextRadius||(d.liveContextDepth===1?2.8:2.0);
    if(d.liveDenseResult)return d.liveDenseRadius||1.7;
    if(d.liveDenseContext)return d.type==="Group"?6:d.type==="Team"?5:4.4;
    if(d.compositionBindingGroup)return 7.5;
    if(d.compositionBindingPos)return 1.45;
    if(d.compositionBindingCloud)return 1.65;
    if(d.compositionDense&&d.compositionPosNode)return 2.35;
    if(d.compositionDense&&d.compositionSource)return 2.7;
    if(d.compositionPosNode)return d.compositionDefender?6.3:4.6;
    if(d.compositionGroup)return 9;
    if(d.compositionTeam)return 7.2;
    if(d.compositionSource)return 4.4;
    if(d.compositionDefender)return 6.3;
    if(d.generatedAttribute)return 5.8;
    if(d.generatedCandidate)return 7.5;
    if(d.outerCountry)return 4.8;
    if(d.hasGeneratedAttribute&&d.type==="Athlete")return 4.1;
    return d.type==="Team"?6.5:d.type==="Group"?7:d.type==="Venue"?5.5:d.type==="City"?5:d.type==="Athlete"?3.2:4.5;
  }

  function styleNodeCircles(selection){
    selection.select("circle")
      .style("fill",d=>{
        if(d.liveGeneratedPos)return "#f59e0b";
        return null;
      })
      .style("stroke",d=>{
        if(d.liveGeneratedPos)return "#fde68a";
        return null;
      })
      .style("stroke-width",d=>d.liveGeneratedPos?1.6:null)
      .style("filter",d=>d.liveGeneratedPos?"drop-shadow(0 0 4px rgba(245,158,11,.35))":null);
  }

  function arrangeFourReturnedPairs(){
    if(!state.graph?.nodes?.length)return false;
    const sources=state.graph.nodes.filter(n=>state.resultIds.has(n.id)&&!n.liveGeneratedPos);
    const generated=state.graph.nodes.filter(n=>n.liveGeneratedPos&&state.resultIds.has(n.id));
    if(sources.length!==4||generated.length!==4)return false;
    const bySource=new Map(generated.map(n=>[n.sourceAthleteId,n]));
    if(sources.some(src=>!bySource.has(src.id)))return false;

    const stage=$("graphStage");
    const width=stage?.clientWidth||760;
    const height=stage?.clientHeight||570;
    const slots=[
      {x:width*.33,y:height*.34},
      {x:width*.67,y:height*.34},
      {x:width*.33,y:height*.68},
      {x:width*.67,y:height*.68}
    ];
    const sourceIds=new Set(sources.map(n=>n.id));
    const generatedIds=new Set(generated.map(n=>n.id));
    const nodeById=new Map(state.graph.nodes.map(n=>[n.id,n]));

    function ccw(ax,ay,bx,by,cx,cy){
      return (cy-ay)*(bx-ax)>(by-ay)*(cx-ax);
    }
    function segmentsCross(a,b,c,d){
      if(!a||!b||!c||!d)return false;
      if(a.id===c.id||a.id===d.id||b.id===c.id||b.id===d.id)return false;
      return ccw(a.x,a.y,c.x,c.y,d.x,d.y)!==ccw(b.x,b.y,c.x,c.y,d.x,d.y)
          && ccw(a.x,a.y,b.x,b.y,c.x,c.y)!==ccw(a.x,a.y,b.x,b.y,d.x,d.y);
    }
    function getGeneratedPos(slot){
      return {x:slot.x+(slot.x>width/2?-20:20),y:slot.y};
    }
    function* permutations(items,start=0){
      if(start>=items.length-1){ yield items.slice(); return; }
      for(let i=start;i<items.length;i++){
        [items[start],items[i]]=[items[i],items[start]];
        yield* permutations(items,start+1);
        [items[start],items[i]]=[items[i],items[start]];
      }
    }

    let best=null;
    const sourceOrder=[...sources].sort((a,b)=>{
      const ay=Number.isFinite(a.y)?a.y:0, by=Number.isFinite(b.y)?b.y:0;
      return ay-by || (Number.isFinite(a.x)?a.x:0)-(Number.isFinite(b.x)?b.x:0) || String(a.label||a.id).localeCompare(String(b.label||b.id));
    });

    for(const perm of permutations([0,1,2,3])){
      const pos=new Map();
      let moveCost=0;
      sourceOrder.forEach((source,idx)=>{
        const slot=slots[perm[idx]];
        const gen=bySource.get(source.id);
        pos.set(source.id,{id:source.id,x:slot.x,y:slot.y});
        const gp=getGeneratedPos(slot);
        pos.set(gen.id,{id:gen.id,x:gp.x,y:gp.y});
        const ox=Number.isFinite(source.x)?source.x:slot.x;
        const oy=Number.isFinite(source.y)?source.y:slot.y;
        moveCost+=(ox-slot.x)*(ox-slot.x)+(oy-slot.y)*(oy-slot.y);
      });
      state.graph.nodes.forEach(node=>{
        if(pos.has(node.id))return;
        pos.set(node.id,{id:node.id,x:Number.isFinite(node.x)?node.x:0,y:Number.isFinite(node.y)?node.y:0});
      });
      const segs=[];
      state.graph.links.forEach(link=>{
        const s=typeof link.source==="object"?link.source.id:link.source;
        const t=typeof link.target==="object"?link.target.id:link.target;
        const sp=pos.get(s), tp=pos.get(t);
        if(!sp||!tp)return;
        // Ignore unchanged context-only segments when scoring; we only optimize
        // crossings caused by the moved query-result pairs.
        if(!sourceIds.has(s)&&!sourceIds.has(t)&&!generatedIds.has(s)&&!generatedIds.has(t))return;
        segs.push({a:sp,b:tp,s,t});
      });
      let crossings=0;
      for(let i=0;i<segs.length;i++){
        for(let j=i+1;j<segs.length;j++){
          if(segmentsCross(segs[i].a,segs[i].b,segs[j].a,segs[j].b))crossings++;
        }
      }
      const score=crossings*1000000+moveCost;
      if(!best||score<best.score)best={score,perm:perm.slice()};
    }

    sourceOrder.forEach((source,idx)=>{
      const slot=slots[(best?.perm||[0,1,2,3])[idx]];
      const gen=bySource.get(source.id);
      source.x=source.fx=slot.x;
      source.y=source.fy=slot.y;
      source.labelSide=slot.x>width/2?"left":"right";
      source._liveAngle=slot.x>width/2?Math.PI:0;
      if(gen){
        const gp=getGeneratedPos(slot);
        gen.x=gen.fx=gp.x;
        gen.y=gen.fy=gp.y;
        gen.labelSide=slot.x>width/2?"left":"right";
        gen._liveAngle=slot.x>width/2?Math.PI:0;
      }
    });
    return true;
  }

  function nodeLabelX(d){
    if(Number.isFinite(d.liveLabelAbsX))return d.liveLabelAbsX-(Number.isFinite(d.x)?d.x:0);
    if(d.compositionGroup)return 0;
    const sideAware=d.liveQueryResult||d.liveContextDepth||d.generatedCandidate||d.generatedAttribute||d.hasGeneratedAttribute||d.compositionSource||d.compositionTeam||d.outerCountry;
    if(sideAware&&d.labelSide==="left")return -(nodeRadius(d)+7);
    return nodeRadius(d)+7;
  }

  function nodeLabelAnchor(d){
    if(Number.isFinite(d.liveLabelAbsX))return"start";
    if(d.compositionGroup)return"middle";
    const sideAware=d.liveQueryResult||d.liveContextDepth||d.generatedCandidate||d.generatedAttribute||d.hasGeneratedAttribute||d.compositionSource||d.compositionTeam||d.outerCountry;
    return sideAware&&d.labelSide==="left"?"end":"start";
  }

  function nodeLabelY(d){
    if(Number.isFinite(d.liveLabelAbsY))return d.liveLabelAbsY-(Number.isFinite(d.y)?d.y:0);
    if(d.compositionGroup)return -(nodeRadius(d)+5);
    if(d.liveQueryResult||d.liveContextDepth)return 3.5+(d.liveLabelDy||0);
    return d.generatedAttribute?-1:3.5;
  }

  function similarityBoxesOverlap(a,b){
    return a.left<b.right+SIMILARITY_BOX_GAP
      &&a.right+SIMILARITY_BOX_GAP>b.left
      &&a.top<b.bottom+SIMILARITY_BOX_GAP
      &&a.bottom+SIMILARITY_BOX_GAP>b.top;
  }

  function layoutSimilarityBoxes(candidates,cx,cy){
    const placed=[];
    const tangentShifts=[0,34,-34,68,-68,102,-102,136,-136,170,-170];
    const radialShifts=[0,24,48,72,96,-12];

    [...candidates]
      .sort((a,b)=>(a.radialAngle||0)-(b.radialAngle||0))
      .forEach(candidate=>{
        const angle=Number.isFinite(candidate.radialAngle)
          ? candidate.radialAngle
          : Math.atan2(candidate.y-cy,candidate.x-cx);
        const ux=Math.cos(angle),uy=Math.sin(angle);
        const tx=-uy,ty=ux;
        const halfExtent=Math.abs(ux)*SIMILARITY_BOX_WIDTH/2
          +Math.abs(uy)*SIMILARITY_BOX_HEIGHT/2;
        const baseDistance=nodeRadius(candidate)+12+halfExtent;

        const attempts=[];
        radialShifts.forEach(radialShift=>{
          tangentShifts.forEach(tangentShift=>{
            attempts.push({
              radialShift,
              tangentShift,
              cost:Math.abs(tangentShift)+Math.max(radialShift,0)*1.15+Math.abs(Math.min(radialShift,0))*2
            });
          });
        });
        attempts.sort((a,b)=>a.cost-b.cost);

        let chosen=null;
        for(const attempt of attempts){
          const distance=baseDistance+attempt.radialShift;
          const centerX=candidate.x-ux*distance+tx*attempt.tangentShift;
          const centerY=candidate.y-uy*distance+ty*attempt.tangentShift;
          const rect={
            left:centerX-SIMILARITY_BOX_WIDTH/2,
            right:centerX+SIMILARITY_BOX_WIDTH/2,
            top:centerY-SIMILARITY_BOX_HEIGHT/2,
            bottom:centerY+SIMILARITY_BOX_HEIGHT/2
          };
          if(!placed.some(other=>similarityBoxesOverlap(rect,other))){
            chosen=rect;
            break;
          }
        }

        if(!chosen){
          // Guaranteed final escape route: continue walking along the tangent
          // until the new similarity box clears every previously placed box.
          let fallbackStep=placed.length+1;
          do{
            const fallback=fallbackStep*38;
            const centerX=candidate.x-ux*baseDistance+tx*fallback;
            const centerY=candidate.y-uy*baseDistance+ty*fallback;
            chosen={
              left:centerX-SIMILARITY_BOX_WIDTH/2,
              right:centerX+SIMILARITY_BOX_WIDTH/2,
              top:centerY-SIMILARITY_BOX_HEIGHT/2,
              bottom:centerY+SIMILARITY_BOX_HEIGHT/2
            };
            fallbackStep+=1;
          }while(placed.some(other=>similarityBoxesOverlap(chosen,other)));
        }

        candidate.similarityBoxX=chosen.left-candidate.x;
        candidate.similarityBoxY=chosen.top-candidate.y;
        placed.push(chosen);
      });
  }

  function appendSimilarityBoxes(nodeSelection){
    const boxes=nodeSelection.filter(d=>d.generatedCandidate)
      .append("g")
      .attr("class",d=>"similarity-box "+(d.grounded?"pass":"fail"))
      .attr("transform",d=>{
        if(Number.isFinite(d.similarityBoxX)&&Number.isFinite(d.similarityBoxY)){
          return `translate(${d.similarityBoxX},${d.similarityBoxY})`;
        }
        return d.labelSide==="left"
          ? `translate(${nodeRadius(d)+12},-14)`
          : `translate(${-nodeRadius(d)-SIMILARITY_BOX_WIDTH-12},-14)`;
      });

    boxes.append("rect")
      .attr("width",SIMILARITY_BOX_WIDTH)
      .attr("height",SIMILARITY_BOX_HEIGHT)
      .attr("rx",0)
      .attr("ry",0);

    boxes.append("text")
      .attr("x",SIMILARITY_BOX_WIDTH/2)
      .attr("y",10)
      .attr("text-anchor","middle")
      .attr("class","similarity-box-title")
      .text(d=>similarityLabel(d.similarityMethod));

    boxes.append("text")
      .attr("x",SIMILARITY_BOX_WIDTH/2)
      .attr("y",22)
      .attr("text-anchor","middle")
      .attr("class","similarity-box-score")
      .text(d=>d.score.toFixed(2));
  }
  function tick(){
    state.linkSel
      .attr("x1",d=>d.source.x)
      .attr("y1",d=>d.source.y)
      .attr("x2",d=>d.target.x)
      .attr("y2",d=>d.target.y);

    if(state.generatedLabelSel){
      state.generatedLabelSel
        .attr("x",d=>(d.source.x+d.target.x)/2)
        .attr("y",d=>(d.source.y+d.target.y)/2-6);
    }

    state.nodeSel.attr("transform",d=>`translate(${d.x},${d.y})`);
  }

  function showTip(e,d){
    const tip=$("tooltip");tip.hidden=false;
    if(d.compositionPosNode){
      const code=d.positionCode?` (${esc(d.positionCode)})`:"";
      const filterLine=d.filterApplied
        ? `<div>FILTER: ${d.compositionDropped?"dropped":"kept"}</div>`
        : "";
      tip.innerHTML=
        `<strong>${esc(d.sourceAthleteLabel||"Generated position")}</strong>`+
        `<div>Position: ${esc(d.positionLabel||d.label||"?pos")}${code}</div>`+
        `<div>GENERATED by GENOP</div>`+
        filterLine+
        `<code>${esc(d.id)}</code>`;
    }else if(d.generatedAttribute){
      const attributeLabel=d.generatedAttributeLabel||currentScenario()?.generatedPredicate||"generated attribute";
      tip.innerHTML=
        `<strong>${esc(d.label)}</strong>`+
        `<div>${esc(attributeLabel)} · GENERATED by GENOP</div>`+
        `<div>evaluation: ${esc(d.evaluationState||"—")}</div>`+
        `<code>${esc(d.id)}</code>`;
    }else if(d.generatedCandidate){
      const groundingDetail=!d.bestId
        ? "no similar entity in the KG"
        : d.grounded
          ? "grounded to: "+esc(d.bestLabel)
          : "dropped below θ = "+Number($("theta").value).toFixed(2);
      tip.innerHTML=
        `<strong>${esc(d.label)}</strong>`+
        `<div>LLM candidate · similarity ${d.score.toFixed(2)}</div>`+
        `<div>${groundingDetail}</div>`;
    }else{
      tip.innerHTML=`<strong>${esc(d.label)}</strong><div>${esc(d.type)}</div><code>${esc(d.id)}</code>`;
    }
    moveTip(e);
  }
  function moveTip(e){
    const r=$("graphStage").getBoundingClientRect(),tip=$("tooltip");
    tip.style.left=Math.min(e.clientX-r.left+14,r.width-280)+"px";
    tip.style.top=Math.max(e.clientY-r.top-8,8)+"px";
  }

  function neighborIds(ids){
    const out=new Set();
    state.graph.links.forEach(l=>{
      const s=typeof l.source==="object"?l.source.id:l.source,t=typeof l.target==="object"?l.target.id:l.target;
      if(ids.has(s)&&!ids.has(t))out.add(t);if(ids.has(t)&&!ids.has(s))out.add(s);
    });
    return out;
  }

  function applyClasses(){
    const inspect=new Set();
    if(state.selectedId){inspect.add(state.selectedId);neighborIds(new Set([state.selectedId])).forEach(x=>inspect.add(x))}
    state.nodeSel
      .classed("query-result",d=>state.resultIds.has(d.id))
      .classed("context",d=>state.contextIds.has(d.id)&&!state.resultIds.has(d.id))
      .classed("inspect",d=>state.selectedId===d.id)
      .classed("faded",d=>{
        if(state.focusMode)return !state.resultIds.has(d.id)&&!state.contextIds.has(d.id);
        if(state.selectedId)return !inspect.has(d.id);
        return false;
      });
    state.linkSel.attr("class",d=>{
      const s=typeof d.source==="object"?d.source.id:d.source;
      const t=typeof d.target==="object"?d.target.id:d.target;

      if(d.generatedAttributeLink){
        let cls=linkClass(d);
        if(state.focusMode && !state.resultIds.has(s) && !state.resultIds.has(t) && !state.contextIds.has(s) && !state.contextIds.has(t)){
          cls+=" faded";
        }
        return cls;
      }

      if(d.groundingLink)return"link grounding-link";
      if(d.potentialGroundingLink)return"link potential-grounding-link";
      if(d.compositionBindingLink)return"link composition-binding-link";
    if(d.compositionPathLink)return"link composition-path-link";

      if(state.focusMode){
        const liveDepthClass=d.liveQueryLinkDepth===2?" live-context-link-2":d.liveQueryLinkDepth===1?" live-context-link-1":"";
        if(state.resultIds.has(s)&&state.resultIds.has(t))return"link query-link live-result-link";
        if((state.resultIds.has(s)&&state.contextIds.has(t))||(state.resultIds.has(t)&&state.contextIds.has(s)))return"link context-link"+liveDepthClass;
        if(state.contextIds.has(s)&&state.contextIds.has(t))return"link context-link"+liveDepthClass;
        return"link faded";
      }
      if(state.selectedId)return(s===state.selectedId||t===state.selectedId)?"link context-link":"link faded";
      return"link";
    });
  }

  function resetGraph(){
    $("attributeResult").hidden=true;
    clearMessage();
    initGraph();
  }

  function updateStatus(){
    const db=currentDb();
    const scenario=currentScenario()?.id;

    if(scenario==="entity"){
      if(state.graph?.nobelCompactOverview){
        $("graphTitle").textContent="Nobel Prize Overview";
        $("graphStatus").textContent=`${state.graph.nodes.length} representative nodes · recent 24 awards per field · Query uses all ${state.graph.fullNodeCount} nodes`;
        return;
      }
      if(state.graph?.nobelOverview){
        const expanded=state.graph.nodes.find(node=>node.id===state.nobelExpandedField);
        $("graphTitle").textContent="Nobel Prize Overview";
        $("graphStatus").textContent=expanded
          ? `${expanded.label} · 10 most recent awards and laureates · click the field to collapse`
          : "6 Nobel fields · click a field to expand recent awards and laureates";
        return;
      }
      const targetType=currentScenario()?.targetType||"Team";
      const countryNodes=state.graph.nodes.filter(n=>n.type===targetType);
      const remainingCountries=countryNodes.filter(n=>n.outerCountry).length;
      const matchedCountries=countryNodes.length-remainingCountries;
      const generated=state.graph.nodes.filter(n=>n.generatedCandidate).length;
      const grounded=state.graph.nodes.filter(n=>n.generatedCandidate&&n.grounded).length;

      $("graphTitle").textContent=generated?"Grounding Result":"Knowledge Graph";
      const similarityMethod=state.graph.nodes.find(n=>n.generatedCandidate)?.similarityMethod;
      $("graphStatus").textContent=generated
        ? `${similarityLabel(similarityMethod)} · ${matchedCountries} matched KG targets · ${remainingCountries} remaining targets · ${generated} generated candidates · ${grounded} grounded`
        : `${db.label} · full KG · ${state.graph.nodes.length} nodes · ${state.graph.links.length} edges`;
      return;
    }

    if(scenario==="attribute"){
      const generated=state.graph.nodes.filter(n=>n.generatedAttribute).length;
      $("graphTitle").textContent=generated?"KG + Generated Attributes":"Knowledge Graph";
      $("graphStatus").textContent=generated
        ? `${state.graph.nodes.filter(n=>n.hasGeneratedAttribute).length} affected source entities · ${generated} generated attributes · unrelated KG nodes hidden`
        : `${db.label} · ${state.graph.nodes.length} nodes · ${state.graph.links.length} edges · ${db.source}`;
      return;
    }

    if(scenario==="composition"){
      if(state.compositionStageMeta){
        $("graphTitle").textContent=state.compositionStageMeta.title;
        $("graphStatus").textContent=state.compositionStageMeta.status;
        return;
      }
      const defenderNodes=state.graph.nodes.filter(n=>n.compositionDefender).length;
      const playerBindings=state.graph.nodes.filter(n=>n.compositionSource).length;
      if(defenderNodes){
        $("graphTitle").textContent="Defender Results";
        const teams=state.graph.nodes.filter(n=>n.compositionTeam).length;
        const groups=state.graph.nodes.filter(n=>n.compositionGroup).length;
        $("graphStatus").textContent=`${defenderNodes} Defender nodes · ${playerBindings} named players · ${teams} teams · ${groups?"Group A":"no group"}`;
        return;
      }
      if(state.graph.nodes.length<currentDb().graph.nodes.length){
        $("graphTitle").textContent="Query-touched Subgraph";
        $("graphStatus").textContent=
          `${state.graph.nodes.length} relevant nodes · ${state.graph.links.length} relevant edges · unrelated KG nodes hidden`;
        return;
      }
    }

    if(state.focusMode){
      $("graphTitle").textContent="Query Result";
      const oneHop=state.graph.nodes.filter(n=>n.liveContextDepth===1).length;
      const twoHop=state.graph.nodes.filter(n=>n.liveContextDepth===2).length;
      $("graphStatus").textContent=`${state.resultIds.size} result nodes · ${oneHop} 1-hop · ${twoHop} 2-hop · unrelated KG nodes hidden`;
    }else{
      $("graphTitle").textContent="Knowledge Graph";
      $("graphStatus").textContent=`${db.label} · ${state.graph.nodes.length} nodes · ${state.graph.links.length} edges · ${db.source}`;
    }
  }

  function fitGraph(focusOnly){
    if(!state.graph?.nodes?.length||typeof NativeGraph==="undefined"||!state.zoom)return;
    const nodes=state.graph.nodes.filter(n=>{
      if(!Number.isFinite(n.x)||!Number.isFinite(n.y))return false;
      return !focusOnly||!state.focusMode||state.resultIds.has(n.id)||state.contextIds.has(n.id);
    });
    if(!nodes.length)return;
    const w=$("graphStage").clientWidth,h=$("graphStage").clientHeight;
    const minX=NativeGraph.min(nodes,d=>d.x)-35,maxX=NativeGraph.max(nodes,d=>d.x)+35,minY=NativeGraph.min(nodes,d=>d.y)-35,maxY=NativeGraph.max(nodes,d=>d.y)+35;
    const bw=Math.max(maxX-minX,100),bh=Math.max(maxY-minY,100),scale=Math.max(.12,Math.min(2.8,.88/Math.max(bw/w,bh/h)));
    const tx=w/2-scale*(minX+maxX)/2,ty=h/2-scale*(minY+maxY)/2;
    NativeGraph.select("#kgGraph").transition().duration(400).call(state.zoom.transform,NativeGraph.zoomIdentity.translate(tx,ty).scale(scale));
  }

  function fitRelevantGraph(){
    const nodes=state.graph.nodes.filter(n=>Number.isFinite(n.x)&&Number.isFinite(n.y));
    if(!nodes.length)return;

    const w=$("graphStage").clientWidth;
    const h=$("graphStage").clientHeight;

    // Smaller padding + higher maximum zoom because unrelated nodes have
    // already been removed from this Run-result view.
    const pad=3;
    const minX=NativeGraph.min(nodes,d=>d.x)-pad;
    const maxX=NativeGraph.max(nodes,d=>d.x)+pad;
    const minY=NativeGraph.min(nodes,d=>d.y)-pad;
    const maxY=NativeGraph.max(nodes,d=>d.y)+pad;

    const bw=Math.max(maxX-minX,70);
    const bh=Math.max(maxY-minY,70);
    const scale=Math.max(.28,Math.min(6.2,.995/Math.max(bw/w,bh/h)));
    const tx=w/2-scale*(minX+maxX)/2;
    const ty=h/2-scale*(minY+maxY)/2;

    NativeGraph.select("#kgGraph")
      .transition()
      .duration(420)
      .call(state.zoom.transform,NativeGraph.zoomIdentity.translate(tx,ty).scale(scale));
  }

  function fitGroundingGraph(){
    // Fit only the original grounding result. The added, unlinked country
    // context deliberately remains outside this framing as a peripheral ring.
    // Similarity boxes are included so collision-avoidance offsets stay visible.
    const nodes=state.graph.nodes.filter(n=>
      !n.outerCountry&&Number.isFinite(n.x)&&Number.isFinite(n.y)
    );
    if(!nodes.length)return;

    const w=$("graphStage").clientWidth;
    const h=$("graphStage").clientHeight;
    const pad=42;
    const left=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxX)
      ? Math.min(d.x,d.x+d.similarityBoxX)
      : d.x;
    const right=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxX)
      ? Math.max(d.x,d.x+d.similarityBoxX+SIMILARITY_BOX_WIDTH)
      : d.x;
    const top=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxY)
      ? Math.min(d.y,d.y+d.similarityBoxY)
      : d.y;
    const bottom=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxY)
      ? Math.max(d.y,d.y+d.similarityBoxY+SIMILARITY_BOX_HEIGHT)
      : d.y;

    const minX=NativeGraph.min(nodes,left)-pad;
    const maxX=NativeGraph.max(nodes,right)+pad;
    const minY=NativeGraph.min(nodes,top)-pad;
    const maxY=NativeGraph.max(nodes,bottom)+pad;

    const bw=Math.max(maxX-minX,160);
    const bh=Math.max(maxY-minY,160);
    const scale=Math.max(.14,Math.min(2.85,.86/Math.max(bw/w,bh/h)));
    const tx=w/2-scale*(minX+maxX)/2;
    const ty=h/2-scale*(minY+maxY)/2;

    NativeGraph.select("#kgGraph")
      .transition()
      .duration(420)
      .call(state.zoom.transform,NativeGraph.zoomIdentity.translate(tx,ty).scale(scale));
  }

  function fitAttributeGraph(){
    const nodes=state.graph.nodes.filter(n=>Number.isFinite(n.x)&&Number.isFinite(n.y));
    if(!nodes.length)return;

    const w=$("graphStage").clientWidth;
    const h=$("graphStage").clientHeight;

    // Keep a little more whitespace than grounding so all player/position
    // labels remain comfortably visible.
    const padX=112;
    const padY=58;
    const minX=NativeGraph.min(nodes,d=>d.x)-padX;
    const maxX=NativeGraph.max(nodes,d=>d.x)+padX;
    const minY=NativeGraph.min(nodes,d=>d.y)-padY;
    const maxY=NativeGraph.max(nodes,d=>d.y)+padY;

    const bw=Math.max(maxX-minX,220);
    const bh=Math.max(maxY-minY,150);
    const scale=Math.max(.16,Math.min(2.55,.86/Math.max(bw/w,bh/h)));
    const tx=w/2-scale*(minX+maxX)/2;
    const ty=h/2-scale*(minY+maxY)/2;

    NativeGraph.select("#kgGraph")
      .transition()
      .duration(420)
      .call(state.zoom.transform,NativeGraph.zoomIdentity.translate(tx,ty).scale(scale));
  }

  function norm(s){return String(s).toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g,"").replace(/[^a-z0-9]/g,"")}
  function tri(s){s=norm(s);const z=new Set();if(s.length<3){if(s)z.add(s);return z}for(let i=0;i<=s.length-3;i++)z.add(s.slice(i,i+3));return z}
  function jac(a,b){const A=tri(a),B=tri(b),U=new Set([...A,...B]);if(!U.size)return 0;let k=0;A.forEach(x=>{if(B.has(x))k++});return k/U.size}

  function findByLabel(label,type){
    const n=norm(label);
    return state.graph.nodes.find(x=>(!type||x.type===type)&&[x.label,...(x.aliases||[])].some(a=>norm(a)===n));
  }

  function textGroundingMatches(){
    const s=currentScenario();
    const teams=currentDb().graph.nodes.filter(n=>n.type===(s.targetType||"Team"));
    return (s.generated||[]).map(name=>{
      // Start at zero, not below it: a candidate sharing nothing with any KG label has no
      // best match, and reporting the first team examined as one drew a grounding link to an
      // arbitrary country. "Italy" scores 0.000 against all 48 teams, which is what the old
      // per-name exception for it was hiding.
      let best=null,score=0;
      teams.forEach(team=>{
        const aliases=[team.label,...(team.aliases||[])];
        const candidateScore=Math.max(...aliases.map(alias=>jac(name,alias)));
        if(candidateScore>score){
          score=candidateScore;
          best=team;
        }
      });
      return {name,best,score};
    });
  }

  async function embeddingGroundingMatches(){
    const key=$("key").value.trim();
    if(!key)throw new Error("Embedding grounding requires an API key in Live demo.");

    const s=currentScenario();
    const teams=currentDb().graph.nodes.filter(n=>n.type===(s.targetType||"Team"));
    const names=s.generated||[];
    const labels=teams.map(t=>t.label);
    const vectors=await embedForGrounding([...names,...labels]);
    const nameVectors=vectors.slice(0,names.length);
    const teamVectors=vectors.slice(names.length);

    return names.map((name,i)=>{
      // As in the text case, no similarity at all means no best match.
      let best=null,score=0;
      teams.forEach((team,j)=>{
        const v=cosine(nameVectors[i],teamVectors[j]);
        if(v>score){score=v;best=team}
      });
      return {name,best,score};
    });
  }

  async function embedForGrounding(texts){
    const base=$("embBase").value.replace(/\/$/,"");
    const model=$("embModel").value;
    const key=$("key").value.trim();

    const response=await fetch(base+"/embeddings",{
      method:"POST",
      headers:{
        "Content-Type":"application/json",
        "Authorization":"Bearer "+key
      },
      body:JSON.stringify({model,input:texts})
    });
    if(!response.ok)throw new Error(`Embedding endpoint returned HTTP ${response.status}`);
    const json=await response.json();
    return json.data.map(item=>item.embedding);
  }

  function cosine(a,b){
    let dot=0,na=0,nb=0;
    for(let i=0;i<a.length;i++){
      dot+=a[i]*b[i];
      na+=a[i]*a[i];
      nb+=b[i]*b[i];
    }
    return dot/(Math.sqrt(na)*Math.sqrt(nb)||1);
  }

  function updateGroundingThresholdOnly(){
    if(currentScenario()?.id!=="entity"||!state.graph)return;

    const theta=Number($("theta").value);
    const candidates=state.graph.nodes.filter(n=>n.generatedCandidate);
    if(!candidates.length)return;

    const byCandidateId=new Map(candidates.map(n=>[n.id,n]));
    const groundedTargets=new Set();
    const potentialTargets=new Set();

    candidates.forEach(candidate=>{
      candidate.grounded=Boolean(candidate.bestId&&candidate.score>=theta);
      if(candidate.bestId){
        potentialTargets.add(candidate.bestId);
        if(candidate.grounded)groundedTargets.add(candidate.bestId);
      }
    });

    state.graph.nodes.forEach(node=>{
      if(node.generatedCandidate)return;
      node.groundedTarget=groundedTargets.has(node.id);
      node.potentialGroundTarget=!node.groundedTarget&&potentialTargets.has(node.id);
    });

    state.graph.links.forEach(link=>{
      if(!link.groundingLink&&!link.potentialGroundingLink)return;

      const sourceId=typeof link.source==="object"?link.source.id:link.source;
      const candidate=byCandidateId.get(sourceId);
      if(!candidate)return;

      link.groundingLink=candidate.grounded;
      link.potentialGroundingLink=!candidate.grounded;
      link.predicate=candidate.grounded?"SIMJOIN":"best match";
    });

    // Update style only: no node rerender, no simulation, no auto-fit.
    state.nodeSel.attr("class",d=>nodeClass(d));
    state.linkSel.attr("class",d=>linkClass(d));
    state.nodeSel.selectAll(".similarity-box")
      .attr("class",d=>"similarity-box "+(d.grounded?"pass":"fail"));

    const grounded=candidates.filter(n=>n.grounded).length;
    const potential=candidates.length-grounded;
    const method=candidates[0]?.similarityMethod||"Text";

    updateStatus();
    showMessage(
      `${similarityLabel(method)} · θ ${theta.toFixed(2)} · ${grounded} grounded · ${potential} potential · only relation edges changed.`,
      "ok"
    );
  }

  async function buildGroundEntityView(options={}){
    const preserveViewport=Boolean(options.preserveViewport);
    const theta=Number($("theta").value);

    const svgSelection=NativeGraph.select("#kgGraph");
    const svgNode=svgSelection.node();
    const previousTransform=(preserveViewport&&svgNode)
      ? NativeGraph.zoomTransform(svgNode)
      : null;

    // Preserve every visible node position when recomputing threshold/method.
    const oldPositions=new Map(
      (state.graph?.nodes||[]).map(n=>[
        n.id,
        {x:n.x,y:n.y,fx:n.fx,fy:n.fy}
      ])
    );

    // Recompute against the full database, then render only relevant targets.
    const fullTeamGraph=graphForCurrentScenario();

    let matches;
    let method;
    if($("groundEmbedding").checked){
      matches=await embeddingGroundingMatches();
      method="Embedding";
    }else{
      matches=textGroundingMatches();
      method="Text";
    }

    const generatedNodes=[];
    const matchLinks=[];
    const groundedTargets=new Set();
    const potentialTargets=new Set();

    const stage=$("graphStage");
    const width=stage.clientWidth||760;
    const height=stage.clientHeight||570;
    const cx=width/2,cy=height/2;
    const radius=Math.min(width,height)*.24;

    matches.forEach((match,index)=>{
      const grounded=Boolean(match.best&&match.score>=theta);
      const id=`generated:team:${state.dbKey}:${index}:${norm(match.name)}`;
      const old=oldPositions.get(id);

      const generatedNode={
        id,
        label:match.name,
        aliases:[match.name],
        type:"GeneratedCandidate",
        generatedCandidate:true,
        score:match.score,
        grounded,
        similarityMethod:method,
        bestId:match.best?.id||null,
        bestLabel:match.best?.label||"—"
      };

      if(old){
        generatedNode.x=old.x;
        generatedNode.y=old.y;
        generatedNode.fx=old.fx;
        generatedNode.fy=old.fy;
      }else{
        const a=-Math.PI/2+(2*Math.PI*index/Math.max(matches.length,1));
        generatedNode.x=cx+Math.cos(a)*radius;
        generatedNode.y=cy+Math.sin(a)*radius;
      }

      generatedNodes.push(generatedNode);

      // A candidate with no best match keeps its node and its score, and simply has no
      // relation to draw.
      if(match.best){
        potentialTargets.add(match.best.id);

        if(grounded){
          groundedTargets.add(match.best.id);
          matchLinks.push({
            source:id,
            target:match.best.id,
            predicate:"SIMJOIN",
            groundingLink:true,
            potentialGroundingLink:false,
            score:match.score
          });
        }else{
          matchLinks.push({
            source:id,
            target:match.best.id,
            predicate:"best match",
            groundingLink:false,
            potentialGroundingLink:true,
            score:match.score
          });
        }
      }
    });

    // Keep the existing grounding result intact, then add every remaining
    // country as unlinked KG context on a separate outer ring.
    const relevantTargetIds=new Set(
      generatedNodes
        .map(n=>n.bestId)
        .filter(Boolean)
    );
    const allCountries=fullTeamGraph.nodes.filter(n=>n.type===(currentScenario().targetType||"Team"));

    const relevantCountries=allCountries
      .filter(n=>relevantTargetIds.has(n.id))
      .map(n=>{
        const copy={...n};
        const p=oldPositions.get(copy.id);
        if(p){
          copy.x=p.x;copy.y=p.y;
          copy.fx=p.fx;copy.fy=p.fy;
        }
        copy.groundedTarget=groundedTargets.has(copy.id);
        copy.potentialGroundTarget=potentialTargets.has(copy.id);
        return copy;
      });

    const remainingCountries=allCountries
      .filter(n=>!relevantTargetIds.has(n.id))
      .map(n=>({
        ...n,
        outerCountry:true,
        groundedTarget:false,
        potentialGroundTarget:false
      }))
      .sort((a,b)=>a.label.localeCompare(b.label));

    state.graph={
      nodes:[...relevantCountries,...generatedNodes,...remainingCountries],
      links:matchLinks
    };

    // Two-ring radial grounding layout:
    //   inner ring = original KG country nodes
    //   outer ring = generated candidates
    //
    // Threshold affects the generated ring:
    //   grounded candidate   -> closer to its target's radial ray
    //   below-threshold      -> farther out and more dispersed
    const targetOrder=[];
    const targetSeen=new Set();

    const candidateLayoutKey=candidate=>candidate.bestId||`unmatched:${candidate.id}`;

    generatedNodes.forEach(candidate=>{
      const key=candidateLayoutKey(candidate);
      if(!targetSeen.has(key)){
        targetOrder.push(key);
        targetSeen.add(key);
      }
    });
    relevantCountries.forEach(country=>{
      if(!targetSeen.has(country.id)){
        targetOrder.push(country.id);
        targetSeen.add(country.id);
      }
    });

    const innerRadius=Math.min(width,height)*.185;
    const groundedRadius=Math.min(width,height)*.345;
    const potentialRadius=Math.min(width,height)*.415;

    const targetAngle=new Map();
    targetOrder.forEach((id,i)=>{
      const angle=-Math.PI/2+(2*Math.PI*i/Math.max(targetOrder.length,1));
      targetAngle.set(id,angle);

      const country=relevantCountries.find(n=>n.id===id);
      if(country){
        const x=cx+Math.cos(angle)*innerRadius;
        const y=cy+Math.sin(angle)*innerRadius;
        country.x=x;country.y=y;
        country.fx=x;country.fy=y;
      }
    });

    const groupedCandidates=new Map();
    generatedNodes.forEach(candidate=>{
      const key=candidateLayoutKey(candidate);
      if(!groupedCandidates.has(key))groupedCandidates.set(key,[]);
      groupedCandidates.get(key).push(candidate);
    });

    groupedCandidates.forEach((candidates,targetId)=>{
      candidates.sort((a,b)=>a.label.localeCompare(b.label));
      const base=targetAngle.has(targetId)
        ? targetAngle.get(targetId)
        : -Math.PI/2;

      candidates.forEach((candidate,i)=>{
        const centered=i-(candidates.length-1)/2;

        // Passing candidates cluster closely on the same ray.
        // Failing candidates spread farther around the outer ring.
        const angularStep=candidate.grounded?.075:.15;
        const angle=base+centered*angularStep;
        const radius=candidate.grounded?groundedRadius:potentialRadius;

        const x=cx+Math.cos(angle)*radius;
        const y=cy+Math.sin(angle)*radius;

        candidate.x=x;candidate.y=y;
        candidate.fx=x;candidate.fy=y;
        candidate.radialAngle=angle;
        candidate.labelSide=Math.cos(angle)<-.12?"left":"right";
      });
    });

    // Place similarity boxes after the radial node layout. A deterministic
    // collision pass offsets nearby boxes tangentially/radially as needed.
    layoutSimilarityBoxes(generatedNodes,cx,cy);

    // Remaining KG countries form a stable, unlinked halo beyond the original
    // result. The initial camera still fits only the original result nodes.
    const outerCountryRadius=Math.max(
      potentialRadius+Math.min(width,height)*.15,
      Math.min(width,height)*.60
    );
    remainingCountries.forEach((country,i)=>{
      const angle=-Math.PI/2+Math.PI/Math.max(remainingCountries.length,1)
        +(2*Math.PI*i/Math.max(remainingCountries.length,1));
      const x=cx+Math.cos(angle)*outerCountryRadius;
      const y=cy+Math.sin(angle)*outerCountryRadius;
      country.x=x;country.y=y;
      country.fx=x;country.fy=y;
      country.radialAngle=angle;
      country.labelSide=Math.cos(angle)<0?"left":"right";
    });

    state.resultIds.clear();
    state.contextIds.clear();
    state.focusMode=false;
    state.selectedId=null;

    rerenderCurrentGraphWithoutReset();

    if(preserveViewport&&previousTransform){
      // Keep exactly the user's current zoom / pan.
      NativeGraph.select("#kgGraph").call(state.zoom.transform,previousTransform);

      // Threshold-dependent radial positions are already fixed in fx/fy; keep viewport unchanged.
      state.simulation.alpha(0).stop();
    }else{
      // Initial Run enlarges the relevant-only result. Threshold changes
      // preserve the user's current zoom/pan and never refit.
      setTimeout(()=>fitGroundingGraph(),520);
    }

    // The graph status line already reports grounded / dropped counts, so the
    // message area is cleared rather than filled with a duplicate summary.
    clearMessage();
  }

  function compositionContext(){
    const s=currentScenario();
    const result=s.result||{defenders:35,total:104,defenderIds:[]};
    const cost=s.cost||{genopFirst:1248,planned:104,factor:"12.0×"};
    return {s,result,cost};
  }

  function compositionSteps(strategy){
    const {s,result,cost}=compositionContext();
    const bindings=s.bindingLabel||"Athlete bindings";
    const selection=s.selectionLabel||"Group A only";
    const generated=s.generatedVariable||"?pos";
    const filter=s.filterLabel||'?pos = "Defender"';
    const matches=result.matches??result.defenders;
    const genopFirstSteps=[
          {title:bindings,detail:`${cost.genopFirst} bindings`,kind:"kg"},
          {title:"GENOP",detail:`${cost.genopFirst} LLM calls`,kind:"genop"},
          {title:"KG selection",detail:selection,kind:"kg"},
        ];
    const plannerSteps=[
          {title:"KG selection",detail:selection,kind:"kg"},
          {title:"GENOP",detail:`${cost.planned} LLM calls`,kind:"genop"},
        ];
    if(s.hasFilter!==false){genopFirstSteps.push({title:"FILTER",detail:filter,kind:"filter"});plannerSteps.push({title:"FILTER",detail:filter,kind:"filter"});}
    genopFirstSteps.push({title:"Result",detail:`${matches} / ${result.total} recorded`,kind:"result"});
    plannerSteps.push({title:"Result",detail:`${matches} / ${result.total} recorded`,kind:"result"});
    return strategy==="genop-first"?genopFirstSteps:plannerSteps;
  }

  function setActiveExecutionStep(index){
    const order=$("executionOrder");
    if(!order)return;
    order.querySelectorAll(".execution-step").forEach((node,i)=>{
      node.classList.toggle("is-active",i===index);
      node.classList.toggle("is-complete",i<index);
      node.setAttribute("aria-current",i===index?"step":"false");
    });
  }

  function renderExecutionResult(strategy){
    const {s,result,cost}=compositionContext();
    const panel=$("executionResult");
    const genopFirst=strategy==="genop-first";
    const calls=genopFirst?cost.genopFirst:cost.planned;

    panel.hidden=false;
    panel.className="execution-result "+(genopFirst?"genop-first":"planner");
    panel.dataset.strategy=strategy;

    $("executionTitle").textContent=genopFirst?"GENOP first":"Planner · deferred GENOP";
    $("executionBadge").textContent=`${calls} LLM calls`;
    $("executionCalls").textContent=String(calls);
    const matches=result.matches??result.defenders;
    $("executionQueryResult").textContent=`${matches} / ${result.total}`;
    $("executionReduction").textContent=genopFirst?"—":cost.factor;

    const steps=compositionSteps(strategy);
    $("executionOrder").innerHTML=steps.map((step,i)=>{
      const block=
        `<button type="button" class="execution-step ${step.kind==="genop"?"genop-step":""}" data-composition-step="${i}">
           <span class="step-no">Step ${i+1}</span>
           <strong>${esc(step.title)}</strong>
           <small>${esc(step.detail)}</small>
         </button>`;
      return i<steps.length-1?block+'<div class="execution-arrow" aria-hidden="true">→</div>':block;
    }).join("");

    $("executionOrder").querySelectorAll("[data-composition-step]").forEach(button=>{
      button.onclick=()=>showCompositionStep(strategy,Number(button.dataset.compositionStep));
    });

    const bindings=(s.bindingLabel||"athlete bindings").toLowerCase();
    $("executionExplanation").textContent=genopFirst
      ? `GENOP runs before selective KG patterns, so the LLM is invoked for all ${cost.genopFirst} ${bindings} before later operators discard irrelevant rows. Click any execution step to inspect the execution order.`
      : `The planner evaluates ${s.selectionLabel||"selective KG patterns"} first and defers GENOP until only ${cost.planned} bindings remain. This reduces LLM calls from ${cost.genopFirst} to ${cost.planned} (${cost.factor} fewer) while preserving the recorded logical query result.`;
  }

  function compositionData(){
    const source=cloneGraph(currentDb().graph);
    const nodeById=new Map(source.nodes.map(node=>[node.id,node]));
    // Pre-recorded GENOP visualization values. They stay outside the KG so
    // position remains an intentionally missing attribute in the database.
    const positionById=window.FIFA2026_POSITIONS||{};
    const allAthletes=source.nodes
      .filter(node=>node.type==="Athlete")
      .sort((a,b)=>(a.label||a.id).localeCompare(b.label||b.id,undefined,{sensitivity:"base"}));
    const group=source.nodes.find(node=>node.type==="Group"&&node.label==="Group A");
    if(!group){
      return {source,nodeById,positionById,allAthletes,group:null,teamIds:[],teamsWithPlayers:[],groupEntries:[],defenderIds:new Set(),allGroups:[],athleteGroupEntries:[]};
    }

    const teamIds=[];
    source.links.forEach(link=>{
      const s=typeof link.source==="object"?link.source.id:link.source;
      const t=typeof link.target==="object"?link.target.id:link.target;
      if(link.predicate==="inGroup"&&t===group.id&&!teamIds.includes(s))teamIds.push(s);
    });
    teamIds.sort((a,b)=>(nodeById.get(a)?.label||a).localeCompare(nodeById.get(b)?.label||b));

    const teamsWithPlayers=teamIds.map(teamId=>{
      const playerIds=[];
      source.links.forEach(link=>{
        const s=typeof link.source==="object"?link.source.id:link.source;
        const t=typeof link.target==="object"?link.target.id:link.target;
        if(link.predicate==="playsFor"&&t===teamId&&nodeById.has(s))playerIds.push(s);
      });
      return {
        teamId,
        team:nodeById.get(teamId),
        players:playerIds
          .map(id=>nodeById.get(id))
          .filter(Boolean)
          .sort((a,b)=>(a.label||a.id).localeCompare(b.label||b.id,undefined,{sensitivity:"base"}))
      };
    }).filter(entry=>entry.team);

    const groupEntries=[];
    teamsWithPlayers.forEach(entry=>entry.players.forEach(player=>groupEntries.push({player,teamId:entry.teamId})));

    // Positions remain intentionally absent from the KG. The recorded Compose
    // result therefore stores FIFA's official POS=DF identities separately
    // from the graph and uses them only to visualize the pre-recorded GENOP output.
    const recordedDefenderIds=compositionContext().result.defenderIds||[];
    const groupPlayerIds=new Set(groupEntries.map(entry=>entry.player.id));
    const defenderIds=new Set(recordedDefenderIds.filter(id=>groupPlayerIds.has(id)));

    // Physical Step 1 (Athlete bindings) shows every athlete as a compact
    // binding cloud connected directly to the World Cup group reached through
    // Player -> Team -> Group. This is a visualization-only shortcut: the
    // recorded query and its logical operators are unchanged.
    const allGroups=source.nodes
      .filter(node=>node.type==="Group")
      .sort((a,b)=>(a.label||a.id).localeCompare(b.label||b.id,undefined,{sensitivity:"base"}));
    const teamToGroup=new Map();
    source.links.forEach(link=>{
      const src=typeof link.source==="object"?link.source.id:link.source;
      const dst=typeof link.target==="object"?link.target.id:link.target;
      if(link.predicate==="inGroup")teamToGroup.set(src,dst);
    });
    const athleteToTeam=new Map();
    source.links.forEach(link=>{
      const src=typeof link.source==="object"?link.source.id:link.source;
      const dst=typeof link.target==="object"?link.target.id:link.target;
      if(link.predicate==="playsFor")athleteToTeam.set(src,dst);
    });
    const athleteGroupEntries=allAthletes.map(player=>{
      const teamId=athleteToTeam.get(player.id)||null;
      return {player,teamId,groupId:teamId?teamToGroup.get(teamId)||null:null};
    });

    return {source,nodeById,positionById,allAthletes,group,teamIds,teamsWithPlayers,groupEntries,defenderIds,allGroups,athleteGroupEntries};
  }

  function compositionStageSize(){
    const stage=$("graphStage");
    return {
      width:stage?.clientWidth||760,
      height:stage?.clientHeight||570
    };
  }

  function fixedNode(node,x,y){
    node.x=x;node.y=y;node.fx=x;node.fy=y;
    return node;
  }

  function compositionHash01(value,salt=0){
    // Deterministic pseudo-random value in [0,1). The cloud looks random but
    // stays stable when users replay Step 1.
    let h=(2166136261 ^ (salt*16777619))>>>0;
    const text=String(value||"");
    for(let i=0;i<text.length;i++){
      h^=text.charCodeAt(i);
      h=Math.imul(h,16777619)>>>0;
    }
    h^=h>>>13;h=Math.imul(h,1274126177)>>>0;h^=h>>>16;
    return (h>>>0)/4294967296;
  }

  function buildAthleteBindingCloudStage(data,{withPos=false}={}){
    const {width,height}=compositionStageSize();
    const groups=data.allGroups||[];
    const outerPadX=14,outerPadY=14;
    const usableW=Math.max(120,width-outerPadX*2);
    const usableH=Math.max(120,height-outerPadY*2);
    const aspect=usableW/usableH;
    const cols=Math.max(1,Math.ceil(Math.sqrt(Math.max(groups.length,1)*aspect)));
    const rows=Math.max(1,Math.ceil(Math.max(groups.length,1)/cols));
    const cellW=usableW/cols,cellH=usableH/rows;

    const groupNodes=[],groupCenters=new Map();
    groups.forEach((group,index)=>{
      const col=index%cols,row=Math.floor(index/cols);
      const gx=outerPadX+(col+.5)*cellW,gy=outerPadY+(row+.5)*cellH;
      const node=fixedNode({...group,compositionGroup:true,compositionBindingGroup:true},gx,gy);
      groupNodes.push(node);groupCenters.set(group.id,{x:gx,y:gy});
    });

    const playerNodes=[],posNodes=[],links=[];
    (data.athleteGroupEntries||[]).forEach(entry=>{
      const center=groupCenters.get(entry.groupId);if(!center)return;
      const halfW=Math.max(14,cellW*.47),halfH=Math.max(14,cellH*.44);
      let dx=0,dy=0;
      for(let attempt=0;attempt<5;attempt++){
        const u=compositionHash01(entry.player.id,attempt*2+1)*2-1;
        const v=compositionHash01(entry.player.id,attempt*2+2)*2-1;
        dx=u*halfW;dy=v*halfH;
        const normalized=(dx*dx)/(halfW*halfW)+(dy*dy)/(halfH*halfH);
        if(normalized<=1&&Math.hypot(dx,dy)>12)break;
      }
      const len=Math.hypot(dx,dy)||1;if(Math.hypot(dx,dy)<=12){dx=dx/len*13;dy=dy/len*13;}
      const px=center.x+dx,py=center.y+dy;
      const player=fixedNode({...entry.player,compositionSource:true,compositionBindingCloud:true,compositionGroupId:entry.groupId},px,py);
      playerNodes.push(player);
      links.push({source:player.id,target:entry.groupId,predicate:"binding → group",compositionBindingLink:true});

      if(withPos){
        const info=data.positionById?.[entry.player.id]||{code:"?",label:"?pos"};
        const angle=compositionHash01(entry.player.id,91)*Math.PI*2,offset=3.6;
        const pos=fixedNode({
          id:`generated:composition:pos:${entry.player.id}`,label:info.label||"?pos",aliases:[info.label||"?pos"],
          type:"Generated",generatedAttribute:true,compositionPosNode:true,compositionBindingPos:true,compositionBindingCloud:true,
          sourceAthleteId:entry.player.id,sourceAthleteLabel:entry.player.label,positionCode:info.code||"?",positionLabel:info.label||"?pos",
          evaluationState:"generated"
        },px+Math.cos(angle)*offset,py+Math.sin(angle)*offset);
        posNodes.push(pos);
        links.push({source:player.id,target:pos.id,predicate:"?pos",generatedAttributeLink:true,compositionResultLink:true,compositionBindingPosLink:true,hideGeneratedEdgeLabel:true,evaluationState:"generated"});
      }
    });
    return {nodes:[...groupNodes,...playerNodes,...posNodes],links,playerNodes,posNodes,groupNodes};
  }

  function layoutDenseCompositionEntries(entries,{withPos=false}={}){
    const {width,height}=compositionStageSize();
    const padX=34,padY=30;
    const aspect=Math.max(.7,(width-2*padX)/Math.max(height-2*padY,1));
    const cols=Math.max(1,Math.ceil(Math.sqrt(entries.length*aspect)));
    const rows=Math.max(1,Math.ceil(entries.length/cols));
    const stepX=cols<=1?0:(width-2*padX)/(cols-1);
    const stepY=rows<=1?0:(height-2*padY)/(rows-1);

    entries.forEach((entry,index)=>{
      const col=index%cols,row=Math.floor(index/cols);
      const cx=padX+col*stepX,cy=padY+row*stepY;
      if(withPos){
        fixedNode(entry.player,cx-3.2,cy);
        fixedNode(entry.pos,cx+4.2,cy);
      }else{
        fixedNode(entry.player,cx,cy);
      }
    });
  }

  function buildDenseBindingStage(data,{withPos=false,groupOnly=false}={}){
    const baseEntries=groupOnly?data.groupEntries:data.allAthletes.map(player=>({player,teamId:null}));
    const entries=baseEntries.map(entry=>{
      const player={
        ...entry.player,
        compositionSource:true,
        compositionDense:true,
        compositionSurvivor:groupOnly,
        compositionTeamId:entry.teamId||null
      };
      let pos=null;
      if(withPos){
        const info=data.positionById?.[entry.player.id]||{code:"?",label:"?pos"};
        pos={
          id:`generated:composition:pos:${entry.player.id}`,label:info.label||"?pos",aliases:[info.label||"?pos"],type:"Generated",
          generatedAttribute:true,compositionPosNode:true,compositionDense:true,sourceAthleteId:entry.player.id,sourceAthleteLabel:entry.player.label,
          positionCode:info.code||"?",positionLabel:info.label||"?pos",evaluationState:"generated"
        };
      }
      return {player,pos};
    });

    layoutDenseCompositionEntries(entries,{withPos});
    const nodes=[];const links=[];
    entries.forEach(entry=>{
      nodes.push(entry.player);
      if(entry.pos){
        nodes.push(entry.pos);
        links.push({
          source:entry.player.id,
          target:entry.pos.id,
          predicate:"?pos",
          generatedAttributeLink:true,
          compositionResultLink:true,
          hideGeneratedEdgeLabel:true,
          evaluationState:"pending"
        });
      }
    });
    return {nodes,links};
  }

  function buildGroupAStage(data,{withPos=false,filterStage=false,finalOnly=false}={}){
    const {width,height}=compositionStageSize();
    const cx=width/2,cy=height/2;
    const base=Math.min(width,height);
    const teamRadius=base*.115;
    const playerRings=[base*.245,base*.31];
    const posRings=[base*.405,base*.47];

    const entriesByTeam=new Map(data.teamIds.map(id=>[id,[]]));
    data.groupEntries.forEach(entry=>{
      if(finalOnly&&!data.defenderIds.has(entry.player.id))return;
      entriesByTeam.get(entry.teamId)?.push(entry);
    });
    const activeTeams=data.teamsWithPlayers.filter(entry=>(entriesByTeam.get(entry.teamId)||[]).length>0);
    const sectorSize=2*Math.PI/Math.max(activeTeams.length,1);
    const sectorGap=.20;

    const groupNode=fixedNode({...data.group,compositionGroup:true},cx,cy);
    const teamNodes=[];const playerNodes=[];const posNodes=[];
    const structuralLinks=[];const generatedLinks=[];

    activeTeams.forEach((entry,teamIndex)=>{
      const centerAngle=-Math.PI/2+teamIndex*sectorSize;
      const teamNode=fixedNode({
        ...entry.team,
        compositionTeam:true,
        labelSide:Math.cos(centerAngle)<0?"left":"right"
      },cx+Math.cos(centerAngle)*teamRadius,cy+Math.sin(centerAngle)*teamRadius);
      teamNodes.push(teamNode);
      structuralLinks.push({source:teamNode.id,target:groupNode.id,predicate:"inGroup",compositionPathLink:true,compositionHop:2});

      const entries=entriesByTeam.get(entry.teamId)||[];
      const usableArc=Math.max(.35,sectorSize-sectorGap);
      entries.forEach((item,position)=>{
        const fraction=entries.length<=1?.5:(position+.5)/entries.length;
        const angle=centerAngle-usableArc/2+usableArc*fraction;
        const ringIndex=position%2;
        const labelSide=Math.cos(angle)<0?"left":"right";
        const isDefender=data.defenderIds.has(item.player.id);
        const dropped=filterStage&&!isDefender;
        const playerNode=fixedNode({
          ...item.player,
          compositionSource:true,
          compositionSurvivor:true,
          compositionTeamId:teamNode.id,
          compositionDropped:dropped,
          labelSide
        },cx+Math.cos(angle)*playerRings[ringIndex],cy+Math.sin(angle)*playerRings[ringIndex]);
        playerNodes.push(playerNode);
        structuralLinks.push({source:playerNode.id,target:teamNode.id,predicate:"playsFor",compositionPathLink:true,compositionHop:1});

        if(withPos){
          const info=data.positionById?.[item.player.id]||{code:isDefender?"DF":"?",label:isDefender?"Defender":"?pos"};
          const posNode=fixedNode({
            id:`generated:composition:pos:${item.player.id}`,label:info.label||"?pos",aliases:[info.label||"?pos"],type:"Generated",
            generatedAttribute:true,compositionPosNode:true,compositionDefender:(filterStage||finalOnly)&&isDefender,compositionDropped:dropped,
            sourceAthleteId:item.player.id,sourceAthleteLabel:item.player.label,positionCode:info.code||"?",positionLabel:info.label||"?pos",
            filterApplied:Boolean(filterStage||finalOnly),evaluationState:dropped?"dropped":((filterStage||finalOnly)&&isDefender?"kept":"generated"),labelSide
          },cx+Math.cos(angle)*posRings[ringIndex],cy+Math.sin(angle)*posRings[ringIndex]);
          posNodes.push(posNode);
          generatedLinks.push({source:playerNode.id,target:posNode.id,predicate:"?pos",generatedAttributeLink:true,compositionResultLink:true,compositionDropLink:dropped,hideGeneratedEdgeLabel:true,evaluationState:dropped?"wrong":"result"});
        }
      });
    });

    return {
      nodes:[groupNode,...teamNodes,...playerNodes,...posNodes],
      links:[...structuralLinks,...generatedLinks],
      playerNodes,posNodes,teamNodes,groupNode
    };
  }

  function applyCompositionStageGraph(graph,meta,{resultIds=[],contextIds=[]}={}){
    state.graph={nodes:graph.nodes,links:graph.links};
    state.resultIds=new Set(resultIds);
    state.contextIds=new Set(contextIds);
    state.selectedId=null;
    state.focusMode=false;
    state.tooltipPinnedId=null;
    $("tooltip").hidden=true;
    state.compositionStageMeta=meta;

    rerenderCurrentGraphWithoutReset();
    tick();
    state.simulation.alpha(0).stop();
    applyClasses();
    updateStatus();

    NativeGraph.select("#kgGraph").call(state.zoom.transform,NativeGraph.zoomIdentity);
    requestAnimationFrame(()=>{
      tick();
      fitRelevantGraph();
    });
  }

  function showCompositionStep(strategy,index){
    const data=compositionData();
    if(!data.group){
      state.graph={nodes:[],links:[]};
      state.compositionStageMeta={title:"Compose step",status:"Group A is not present in the active KG."};
      rerenderCurrentGraphWithoutReset();
      updateStatus();
      return;
    }

    const {result,cost}=compositionContext();
    const steps=compositionSteps(strategy);
    const safeIndex=Math.max(0,Math.min(index,steps.length-1));
    state.compositionStrategy=strategy;
    state.compositionStep=safeIndex;
    setActiveExecutionStep(safeIndex);

    let graph,meta,resultIds=[],contextIds=[];

    if(strategy==="genop-first"){
      if(safeIndex===0){
        graph=buildAthleteBindingCloudStage(data);
        contextIds=graph.groupNodes.map(n=>n.id);
        meta={
          title:"Step 1 · Athlete bindings",
          status:`${cost.genopFirst} Athlete bindings · compact random layout · each player is linked to its World Cup group`
        };
      }else if(safeIndex===1){
        graph=buildAthleteBindingCloudStage(data,{withPos:true});
        contextIds=graph.groupNodes.map(n=>n.id);
        meta={
          title:"Step 2 · GENOP",
          status:`Step 1 layout retained · ${cost.genopFirst} players + ${cost.genopFirst} yellow Position nodes · ${cost.genopFirst} LLM calls`
        };
      }else if(safeIndex===2){
        graph=buildGroupAStage(data,{withPos:true});
        meta={
          title:"Step 3 · KG selection",
          status:`Group A only · ${cost.planned} player bindings and their yellow Position nodes survive · ${cost.genopFirst-cost.planned} player bindings dropped`
        };
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
      }else if(safeIndex===3){
        graph=buildGroupAStage(data,{withPos:true,filterStage:true});
        const keptPlayers=graph.playerNodes.filter(n=>!n.compositionDropped);
        const keptPos=graph.posNodes.filter(n=>!n.compositionDropped);
        resultIds=[...keptPlayers.map(n=>n.id),...keptPos.map(n=>n.id)];
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
        meta={
          title:'Step 4 · FILTER ?pos = "Defender"',
          status:`${result.defenders} Defender bindings kept · ${cost.planned-result.defenders} non-Defender Group A bindings shown as dropped`
        };
      }else{
        graph=buildGroupAStage(data,{withPos:true,finalOnly:true});
        resultIds=[...graph.playerNodes.map(n=>n.id),...graph.posNodes.map(n=>n.id)];
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
        meta={
          title:"Step 5 · Result",
          status:`${result.defenders} Defender results · original player nodes + generated yellow Defender nodes · Group A context retained`
        };
      }
    }else{
      if(safeIndex===0){
        graph=buildGroupAStage(data,{withPos:false});
        resultIds=graph.playerNodes.map(n=>n.id);
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
        meta={
          title:"Step 1 · KG selection",
          status:`Group A only · ${cost.planned} of ${cost.genopFirst} Athlete bindings survive before GENOP`
        };
      }else if(safeIndex===1){
        // GENOP is applied directly on top of the selected Group A subgraph.
        // Keep the Group A / Team / Player structure from Step 1 and add one
        // yellow generated ?pos node for each surviving player binding.
        graph=buildGroupAStage(data,{withPos:true});
        resultIds=graph.playerNodes.map(n=>n.id);
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
        meta={
          title:"Step 2 · GENOP",
          status:`Group A selection retained · ${cost.planned} players + ${cost.planned} generated yellow Position nodes · ${cost.planned} LLM calls`
        };
      }else if(safeIndex===2){
        graph=buildGroupAStage(data,{withPos:true,filterStage:true});
        const keptPlayers=graph.playerNodes.filter(n=>!n.compositionDropped);
        const keptPos=graph.posNodes.filter(n=>!n.compositionDropped);
        resultIds=[...keptPlayers.map(n=>n.id),...keptPos.map(n=>n.id)];
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
        meta={
          title:'Step 3 · FILTER ?pos = "Defender"',
          status:`${result.defenders} Defender bindings kept · ${cost.planned-result.defenders} non-Defender bindings shown as dropped`
        };
      }else{
        graph=buildGroupAStage(data,{withPos:true,finalOnly:true});
        resultIds=[...graph.playerNodes.map(n=>n.id),...graph.posNodes.map(n=>n.id)];
        contextIds=[graph.groupNode.id,...graph.teamNodes.map(n=>n.id)];
        meta={
          title:"Step 4 · Result",
          status:`${result.defenders} Defender results · same recorded logical result as GENOP-first`
        };
      }
    }

    applyCompositionStageGraph(graph,meta,{resultIds,contextIds});
  }

  async function executeCompositionStrategy(strategy){
    if(state.dbKey==="nobelPrize")return executeNobelCompositionStrategy(strategy);
    clearMessage();
    const {result,cost}=compositionContext();
    const btnA=$("runGenopFirst");
    const btnB=$("runPlanner");
    btnA.disabled=true;
    btnB.disabled=true;

    const isPlanner=strategy==="planner";
    const active=isPlanner?btnB:btnA;
    const original=active.querySelector("strong").textContent;
    active.querySelector("strong").textContent="Running…";

    try{
      renderExecutionResult(strategy);
      const steps=compositionSteps(strategy);
      for(let i=0;i<steps.length;i++){
        showCompositionStep(strategy,i);
        // Let each physical operator stage remain visible long enough to read.
        if(i<steps.length-1)await new Promise(resolve=>setTimeout(resolve,520));
      }

      const calls=isPlanner?cost.planned:cost.genopFirst;
      const order=isPlanner
        ? "KG patterns → GENOP → FILTER"
        : "GENOP → KG patterns → FILTER";

      showMessage(
        `${isPlanner?"Planner":"GENOP-first"} completed · ${calls} LLM calls · ${order} · recorded result ${result.defenders}/${result.total}. Click any Step above to replay that intermediate KG state.`,
        "ok"
      );
    }finally{
      active.querySelector("strong").textContent=original;
      btnA.disabled=false;
      btnB.disabled=false;
    }
  }

  function nobelCompositionData(){
    const {s}=compositionContext();
    const full=currentDb().graph,byId=new Map(full.nodes.map(node=>[node.id,node]));
    const physicsId="http://www.wikidata.org/entity/Q38104",sinceYear=s.sinceYear||2020;
    const awardIds=new Set(full.links.filter(link=>link.predicate==="field"&&link.target===physicsId&&nobelAwardYear({id:link.source})>=sinceYear).map(link=>link.source));
    const selectedIds=[...new Set(full.links.filter(link=>link.predicate==="winner"&&awardIds.has(link.source)).map(link=>link.target))];
    return {s,full,byId,physicsId,sinceYear,selectedIds,allIds:full.nodes.filter(node=>node.type==="NobelLaureate").map(node=>node.id)};
  }

  function buildNobelCompositionStage({selected=false,withGenerated=false}={}){
    const data=nobelCompositionData(),ids=selected?data.selectedIds:data.allIds;
    const stage=$("graphStage"),width=stage.clientWidth||760,height=stage.clientHeight||570,cx=width/2,cy=height/2;
    const dense=ids.length>200,nodes=[],links=[];
    let field=null;
    if(selected){
      field={...data.byId.get(data.physicsId),compositionGroup:true};
      field.x=field.fx=cx;field.y=field.fy=cy;nodes.push(field);
    }
    const sources=ids.map(id=>({...data.byId.get(id),compositionSource:true,compositionDense:dense,liveDenseResult:dense,liveDenseRadius:1.45}));
    if(dense){
      gridNodes(sources,{x:18,y:18,w:width-36,h:height-36},1.45,221);
    }else{
      sources.forEach((node,index)=>{
        const angle=-Math.PI/2+index*Math.PI*2/Math.max(sources.length,1),radius=Math.min(width,height)*.29;
        node.x=node.fx=cx+Math.cos(angle)*radius;node.y=node.fy=cy+Math.sin(angle)*radius;
        node.labelSide=Math.cos(angle)<0?"left":"right";
        links.push({source:field.id,target:node.id,predicate:`Physics ${data.sinceYear}+`,compositionPathLink:true});
      });
    }
    nodes.push(...sources);
    const generated=[];
    if(withGenerated)sources.forEach((source,index)=>{
      const angle=dense?stableUnit(source.id,331)*Math.PI*2:-Math.PI/2+index*Math.PI*2/Math.max(sources.length,1);
      const distance=dense?3.4:14;
      const node={
        id:`generated:motivation:${source.id}`,label:data.s.generatedLabel||"Award motivation",aliases:[data.s.generatedLabel||"Award motivation"],type:"Generated",
        generatedAttribute:true,generatedAttributeLabel:data.s.generatedVariable||"?motivation",sourceAthleteId:source.id,sourceAthleteLabel:source.label,
        compositionDense:dense,liveDenseResult:dense,liveDenseRadius:1.35,
        x:source.x+Math.cos(angle)*distance,y:source.y+Math.sin(angle)*distance
      };
      node.fx=node.x;node.fy=node.y;node.labelSide=source.labelSide;generated.push(node);
      links.push({source:source.id,target:node.id,predicate:data.s.generatedVariable||"?motivation",generatedAttributeLink:true,hideGeneratedEdgeLabel:dense,evaluationState:"correct"});
    });
    nodes.push(...generated);
    return {nodes,links,sources,generated,field,data};
  }

  function showNobelCompositionStep(strategy,index){
    const {s,result,cost}=compositionContext(),steps=compositionSteps(strategy);
    const safeIndex=Math.max(0,Math.min(index,steps.length-1));
    state.compositionStrategy=strategy;state.compositionStep=safeIndex;setActiveExecutionStep(safeIndex);
    let selected=false,withGenerated=false,title,status;
    if(strategy==="genop-first"){
      if(safeIndex===0){title="Step 1 · Nobel laureate bindings";status=`${cost.genopFirst} laureate bindings before GENOP`;}
      else if(safeIndex===1){withGenerated=true;title="Step 2 · GENOP";status=`${cost.genopFirst} generated motivation nodes · ${cost.genopFirst} LLM calls`;}
      else {selected=true;withGenerated=true;title=safeIndex===2?"Step 3 · KG selection":"Step 4 · Result";status=`Physics ${s.sinceYear}+ leaves ${cost.planned} laureates and ${cost.planned} generated motivations`;}
    }else{
      selected=true;
      if(safeIndex===0){title="Step 1 · KG selection";status=`Physics ${s.sinceYear}+ reduces ${cost.genopFirst} laureates to ${cost.planned} bindings before GENOP`;}
      else {withGenerated=true;title=safeIndex===1?"Step 2 · GENOP":"Step 3 · Result";status=`Only ${cost.planned} generated motivation nodes · ${cost.planned} LLM calls`;}
    }
    const graph=buildNobelCompositionStage({selected,withGenerated});
    const finalStep=safeIndex===steps.length-1;
    const resultIds=finalStep?[...graph.sources.map(node=>node.id),...graph.generated.map(node=>node.id)]:graph.sources.map(node=>node.id);
    applyCompositionStageGraph(graph,{title,status},{resultIds,contextIds:graph.field?[graph.field.id]:[]});
  }

  async function executeNobelCompositionStrategy(strategy){
    clearMessage();
    const {s,result,cost}=compositionContext(),btnA=$("runGenopFirst"),btnB=$("runPlanner");
    const active=strategy==="planner"?btnB:btnA,original=active.querySelector("strong").textContent;
    btnA.disabled=true;btnB.disabled=true;active.querySelector("strong").textContent="Running…";
    try{
      renderExecutionResult(strategy);
      $("executionOrder").querySelectorAll("[data-composition-step]").forEach(button=>button.onclick=()=>showNobelCompositionStep(strategy,Number(button.dataset.compositionStep)));
      const steps=compositionSteps(strategy);
      for(let index=0;index<steps.length;index++){
        showNobelCompositionStep(strategy,index);
        if(index<steps.length-1)await new Promise(resolve=>setTimeout(resolve,620));
      }
      const calls=strategy==="planner"?cost.planned:cost.genopFirst,matches=result.matches??result.defenders;
      showMessage(`${strategy==="planner"?"Planner-first":"GENOP-first"} completed · ${calls} LLM calls · ${matches}/${result.total} ${s.resultLabel}. Click a Step to replay its intermediate KG.`,"ok");
    }finally{
      active.querySelector("strong").textContent=original;btnA.disabled=false;btnB.disabled=false;
    }
  }


  function renderAttributeResult(rows,stat){
    $("attributeResult").hidden=false;
    $("attributeAnswerRate").textContent=stat?.a||"—";
    $("attributeAccuracy").textContent=stat?.b||"—";

    const counts={correct:0,wrong:0,abstain:0};
    rows.forEach(r=>{if(counts[r.state]!==undefined)counts[r.state]++});

    $("attributeSampleSummary").textContent=
      `Displayed sample · ${counts.correct} correct · ${counts.wrong} wrong · ${counts.abstain} abstain`;

    $("attributeRows").innerHTML=rows.map(r=>`
      <div class="attribute-row">
        <span class="attribute-player">${esc(r.name)}</span>
        <span class="attribute-generated-value">${esc(r.gen)}</span>
        <span class="attribute-state ${esc(r.state)}">${esc(r.state)}</span>
      </div>
    `).join("");
  }

  function addGeneratedAttributeNodes(rows){
    const fullGraph=cloneGraph(currentDb().graph);
    const scenario=currentScenario();
    const sourceType=scenario.sourceType||"Athlete";
    const generatedPredicate=scenario.generatedPredicate||"?pos";

    // Preserve current coordinates for the affected players where possible.
    const previousPositions=new Map(
      (state.graph?.nodes||[]).map(n=>[n.id,{x:n.x,y:n.y}])
    );

    const playerNodes=[];
    const generatedNodes=[];
    const generatedLinks=[];
    const athleteIds=[];
    const generatedIds=[];

    rows.forEach((row,index)=>{
      const sourceAthlete=fullGraph.nodes.find(
        n=>n.type===sourceType &&
        [n.label,...(n.aliases||[])].some(a=>norm(a)===norm(row.name))
      );
      if(!sourceAthlete)return;

      const athlete={...sourceAthlete,hasGeneratedAttribute:true};
      const previous=previousPositions.get(athlete.id);
      if(previous){
        athlete.x=previous.x;
        athlete.y=previous.y;
      }

      const generatedId=`generated:attribute:${state.dbKey}:${index}:${norm(row.name)}`;

      // Seed the new attribute near its source player; forceLink will keep it
      // attached without turning the visualization into rows.
      const angle=(index%8)*(Math.PI/4);
      const baseX=Number.isFinite(athlete.x)?athlete.x:0;
      const baseY=Number.isFinite(athlete.y)?athlete.y:0;

      const position={
        id:generatedId,
        label:row.gen,
        aliases:[row.gen],
        type:"Generated",
        generatedAttribute:true,
        generatedAttributeLabel:generatedPredicate,
        evaluationState:row.state,
        sourceAthleteId:athlete.id,
        x:baseX+Math.cos(angle)*23,
        y:baseY+Math.sin(angle)*23
      };

      playerNodes.push(athlete);
      generatedNodes.push(position);
      generatedLinks.push({
        source:athlete.id,
        target:generatedId,
        predicate:generatedPredicate,
        generatedAttributeLink:true,
        evaluationState:row.state
      });

      athleteIds.push(athlete.id);
      generatedIds.push(generatedId);
    });

    // Run-result graph contains only nodes that participated in this query
    // plus the attributes GENOP created.
    state.graph={
      nodes:[...playerNodes,...generatedNodes],
      links:generatedLinks
    };

    // Two-ring radial attribute layout:
    //   inner ring = existing KG Players
    //   outer ring = GENOP-generated Position attributes
    const stage=$("graphStage");
    const width=stage.clientWidth||760;
    const height=stage.clientHeight||570;
    const cx=width/2;
    const cy=height/2;
    const innerRadius=Math.min(width,height)*.205;
    const outerRadius=Math.min(width,height)*.355;
    const count=Math.max(playerNodes.length,1);

    playerNodes.forEach((player,i)=>{
      const angle=-Math.PI/2+(2*Math.PI*i/count);
      const px=cx+Math.cos(angle)*innerRadius;
      const py=cy+Math.sin(angle)*innerRadius;

      player.x=px;player.y=py;
      player.fx=px;player.fy=py;
      player.radialAngle=angle;
      // Player names face inward, away from the generated Position label.
      player.labelSide=Math.cos(angle)>=0?"left":"right";

      const position=generatedNodes.find(n=>n.sourceAthleteId===player.id);
      if(position){
        // Position stays on the same radial ray as its Player.
        const qx=cx+Math.cos(angle)*outerRadius;
        const qy=cy+Math.sin(angle)*outerRadius;
        position.x=qx;position.y=qy;
        position.fx=qx;position.fy=qy;
        position.radialAngle=angle;
        position.labelSide=Math.cos(angle)<-.12?"left":"right";
      }
    });

    state.resultIds.clear();
    state.contextIds.clear();
    state.selectedId=null;
    state.focusMode=false;

    rerenderCurrentGraphWithoutReset();

    // Render the fixed radial coordinates immediately. Without this explicit
    // tick, the DOM can briefly keep every node at SVG (0,0), which appears as
    // a pile in the upper-left corner.
    tick();
    state.simulation.alpha(0).stop();

    // Reset any zoom transform inherited from the previous full-KG view, then
    // fit the expanded attribute result on the next frame.
    NativeGraph.select("#kgGraph").call(state.zoom.transform,NativeGraph.zoomIdentity);
    requestAnimationFrame(()=>{
      tick();
      fitAttributeGraph();
    });

    return {
      athleteIds,
      generatedIds,
      missing:rows.length-generatedNodes.length
    };
  }

  function rerenderCurrentGraphWithoutReset(){
    if(typeof NativeGraph==="undefined")return;
    if(state.simulation)state.simulation.stop();

    const graphSnapshot=state.graph;
    const svg=NativeGraph.select("#kgGraph");
    svg.selectAll("*").remove();

    const width=$("graphStage").clientWidth||760;
    const height=$("graphStage").clientHeight||570;

    state.zoomLayer=svg.append("g");
    const linkLayer=state.zoomLayer.append("g").attr("class","links");
    const labelLayer=state.zoomLayer.append("g").attr("class","edge-labels");
    const nodeLayer=state.zoomLayer.append("g").attr("class","nodes");

    state.linkSel=linkLayer
      .selectAll("line")
      .data(graphSnapshot.links)
      .join("line")
      .attr("class",d=>linkClass(d));

    state.generatedLabelSel=labelLayer
      .selectAll("text")
      .data(graphSnapshot.links.filter(d=>d.generatedAttributeLink&&!d.hideGeneratedEdgeLabel))
      .join("text")
      .attr("class",d=>{
        let cls="generated-edge-label";
        if(d.compositionResultLink)cls+=" composition-generated-edge";
        if(d.evaluationState==="wrong")cls+=" wrong";
        if(d.evaluationState==="abstain")cls+=" abstain";
        return cls;
      })
      .text(d=>d.predicate||"?generated");

    state.nodeSel=nodeLayer
      .selectAll("g")
      .data(graphSnapshot.nodes,d=>d.id)
      .join("g")
      .attr("class",d=>nodeClass(d));

    state.nodeSel.append("circle").attr("r",nodeRadius);
    styleNodeCircles(state.nodeSel);

    const denseCompositionSnapshot=graphSnapshot.nodes.length>300&&graphSnapshot.nodes.some(node=>node.compositionDense);
    state.nodeSel.filter(node=>!denseCompositionSnapshot||node.compositionGroup).append("text")
      .attr("x",d=>nodeLabelX(d))
      .attr("text-anchor",d=>nodeLabelAnchor(d))
      .attr("y",nodeLabelY)
      .style("font-size",d=>Number.isFinite(d.liveLabelFont)?`${d.liveLabelFont}px`:null,"important")
      .text(d=>d.label);

    appendSimilarityBoxes(state.nodeSel);

    state.zoom=NativeGraph.zoom()
      .scaleExtent([.12,6])
      .on("zoom",e=>state.zoomLayer.attr("transform",e.transform));
    svg.call(state.zoom).on("dblclick.zoom",null);

    state.nodeSel.call(NativeGraph.drag()
      .on("start",(e,d)=>{if(!e.active)state.simulation.alphaTarget(.22).restart();d.fx=d.x;d.fy=d.y})
      .on("drag",(e,d)=>{d.fx=e.x;d.fy=e.y})
      .on("end",(e,d)=>{if(!e.active)state.simulation.alphaTarget(0);if(!state.focusMode){d.fx=null;d.fy=null}}))
      .on("mouseenter",(e,d)=>showTip(e,d))
      .on("mousemove",e=>{if(!state.tooltipPinnedId)moveTip(e)})
      .on("mouseleave",()=>{if(!state.tooltipPinnedId)$("tooltip").hidden=true})
      .on("click",(e,d)=>{
        e.stopPropagation();
        if(d.compositionPosNode){
          state.tooltipPinnedId=state.tooltipPinnedId===d.id?null:d.id;
          if(state.tooltipPinnedId){
            showTip(e,d);
          }else{
            $("tooltip").hidden=true;
          }
          return;
        }
        state.tooltipPinnedId=null;
        $("tooltip").hidden=true;
        state.selectedId=state.selectedId===d.id?null:d.id;
        applyClasses();
      });

    svg.on("click",()=>{state.tooltipPinnedId=null;$("tooltip").hidden=true;state.selectedId=null;applyClasses()});

    state.simulation=NativeGraph.forceSimulation(graphSnapshot.nodes)
      .force("link",NativeGraph.forceLink(graphSnapshot.links).id(d=>d.id)
        .distance(d=>d.generatedAttributeLink?20:(d.groundingLink?36:(d.potentialGroundingLink?42:(d.source?.type==="Athlete"||d.target?.type==="Athlete"?13:25))))
        .strength(d=>d.generatedAttributeLink?.97:(d.groundingLink?.92:(d.potentialGroundingLink?.16:.82))))
      .force("charge",NativeGraph.forceManyBody().strength(graphSnapshot.nodes.length>300?-34:-62))
      .force("center",NativeGraph.forceCenter(width/2,height/2))
      .force("collision",NativeGraph.forceCollide().radius(d=>nodeRadius(d)+.8).iterations(1))
      .on("tick",tick);

    updateStatus();
  }


  function executeAttribute(){
    const s=currentScenario();
    const rows=s.rows||[];
    renderAttributeResult(rows,s.stat||{});

    const result=addGeneratedAttributeNodes(rows);

    const stat=s.stat||{};
    const messageParts=[
      `GENOP generated ${result.generatedIds.length} attribute nodes`,
      `answer rate ${stat.a||"—"}`,
      `accuracy ${stat.b||"—"}`
    ];
    if(result.missing){
      messageParts.push(`${result.missing} displayed source entit${result.missing===1?"y is":"ies are"} not present in this database`);
    }
    showMessage(messageParts.join(" · ")+".","ok");
  }

  function runFailureMessage(err){
    const detail=err&&err.message?err.message:String(err);
    // A rejected cross-origin fetch surfaces as a bare TypeError with no status,
    // which in practice almost always means CORS or an unreachable endpoint.
    return err instanceof TypeError
      ? `Run failed: ${detail}. The embedding endpoint is unreachable or blocks browser requests (CORS). Check the endpoint URL, or switch back to Text (Jaccard) grounding.`
      : `Run failed: ${detail}`;
  }

  async function execute(){
    clearMessage();
    const s=currentScenario();
    $("runBtn").disabled=true;$("runBtn").textContent="Running…";
    try{
      if(s.id==="entity"){
        await buildGroundEntityView();
      }else if(s.id==="attribute"){
        executeAttribute();
      }else{
        await executeCompositionStrategy("planner");
      }
    }catch(err){
      // Surface embedding-endpoint failures in the message area.
      showMessage(runFailureMessage(err),"err");
    }finally{
      $("runBtn").disabled=false;$("runBtn").textContent="Run ▶";
    }
  }

  function changeDb(){
    const nextKey=$("databaseSelect").value;
    if(!CFG.databases[nextKey]){
      showMessage(`Database ${nextKey} is unavailable. Reload the page to refresh its data adapter.`,"err");
      $("databaseSelect").value=state.dbKey;
      return;
    }
    if(state.graphRenderFrame){cancelAnimationFrame(state.graphRenderFrame);state.graphRenderFrame=0;}
    if(state.thetaFrame){cancelAnimationFrame(state.thetaFrame);state.thetaFrame=0;}
    if(state.simulation)state.simulation.stop();
    state.dbKey=nextKey;
    state.scenarioIndex=0;
    state.nobelExpandedField=null;
    localStorage.setItem("gensparql.database",state.dbKey);
    $("liveQueryResult").hidden=true;
    $("attributeResult").hidden=true;
    $("executionResult").hidden=true;
    $("kgUploadStatus").textContent=`${currentDb().graph.nodes.length} nodes · ${currentDb().graph.links.length} edges`;
    setEngineStatus(`${currentDb().label} selected · run a query to load it into the Java engine`);
    clearMessage();
    if(pageMode==="demo"){
      renderTabs(true);
      renderScenario();
      syncPreRecordedVisibility();
    }
    initGraph();
  }

  function syncPreRecordedVisibility(){
    const panel=$("prerecordedBox");
    if(!panel)return;
    const uploaded=Boolean(currentDb()?.uploaded);
    panel.hidden=uploaded;
    if(uploaded){
      $("caption").textContent=`${currentDb().label} is an uploaded KG. Use Query and the graph explorer; pre-recorded workflows are available only for the built-in databases.`;
    }
  }

  function init(){
    initTheme();
    if(pageMode==="live"){
      initLive();
      initEngineBackend();
      initKgUpload();
      initLiveQuery();
    }
    $("databaseSelect").value=state.dbKey;$("databaseSelect").onchange=changeDb;
    if(pageMode==="demo"){
      $("theta").oninput=()=>{
        $("thetaVal").textContent=Number($("theta").value).toFixed(2);
        renderScenario();
        if(state.thetaFrame)cancelAnimationFrame(state.thetaFrame);
        state.thetaFrame=requestAnimationFrame(()=>{
          state.thetaFrame=0;
          if(currentScenario()?.id==="entity"&&state.graph?.nodes.some(n=>n.generatedCandidate)){
            updateGroundingThresholdOnly();
          }
        });
      };
      $("runBtn").onclick=execute;
      $("runGenopFirst").onclick=()=>executeCompositionStrategy("genop-first");
      $("runPlanner").onclick=()=>executeCompositionStrategy("planner");
    }
    $("resetGraph").onclick=resetGraph;
    $("fitGraph").onclick=()=>fitGraph(state.focusMode);
    $("zoomIn").onclick=()=>NativeGraph.select("#kgGraph").transition().duration(160).call(state.zoom.scaleBy,1.25);
    $("zoomOut").onclick=()=>NativeGraph.select("#kgGraph").transition().duration(160).call(state.zoom.scaleBy,.8);
    window.addEventListener("resize",()=>{
      clearTimeout(state.resizeTimer);
      state.resizeTimer=setTimeout(()=>fitGraph(state.focusMode),120);
    },{passive:true});
    if(pageMode==="demo"){
      renderTabs();
      renderScenario();
      syncPreRecordedVisibility();
    }
    initGraph();
  }
  init();
})();
