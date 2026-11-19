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
    liveLabelScale:1,
    graphRenderFrame:0,thetaFrame:0,resizeTimer:0,
    view:0,playbackTimer:0
  };
  // The address keeps the database, the scenario, θ and the similarity (writeUrl), so a link
  // opens the same view, as the T-MIGRANTS explorer keeps its focus and filters in its address.
  const URL_STATE=new URLSearchParams(location.search);
  if (CFG.databases[URL_STATE.get("db")]) state.dbKey = URL_STATE.get("db");
  if (!CFG.databases[state.dbKey]) state.dbKey = "fifa2026";

  const esc = s => String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
  const currentDb = () => CFG.databases[state.dbKey];
  const scenarioList = () => CFG.scenariosByDatabase?.[state.dbKey] || CFG.scenarios;
  const currentScenario = () => scenarioList()[state.scenarioIndex] || scenarioList()[0];
  const similarityLabel = method => String(method || "").trim().toLowerCase() === "text"
    ? "Jaccard similarity"
    : `${String(method || "").trim()} similarity`.trim();
  // ?print (page-mode.js) lays the graph out for a figure: a similarity box shows only its
  // score and is smaller, and the fit leaves room for the larger labels of styles.css.
  const PRINT_LAYOUT=document.documentElement.hasAttribute("data-print");
  // Outside the figure layout, d3-force drives the layout (native-graph.js), and the graph reacts
  // to dragging and hovering as the explorer of the Circular Factory demo does.
  const INTERACTIVE=!PRINT_LAYOUT&&window.NativeGraph?.physics==="d3";
  // A box shows the similarity of a name to its closest entity; the method is in the status line
  // of the graph and in the tooltip of the name, so the box holds only the score.
  const SIMILARITY_BOX_WIDTH=PRINT_LAYOUT?62:34;
  const SIMILARITY_BOX_HEIGHT=PRINT_LAYOUT?28:15;
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
        newView();
        state.scenarioIndex=i;
        renderTabs();
        renderScenario();
        clearMessage();
        $("attributeResult").hidden=true;
        scheduleGraphInit();
        writeUrl();
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
    const q=s.query.replace("{theta}",Number($("theta").value).toFixed(2)).replace("<model>",liveOn()?"<model:live>":(s.modelRef||"<model:…>"));
    $("queryName").textContent=s.name;
    $("query").innerHTML=hl(q);
    renderMode(q);
    $("thetaWrap").classList.toggle("inactive",!s.hero);
    $("theta").disabled=pageMode==="demo"&&!s.hero;

    const isComposition=s.id==="composition";
    const isAttribute=s.id==="attribute";
    $("defaultRunRow").hidden=isComposition;
    $("composeRun").hidden=!isComposition;
    // The physical order of each strategy depends on the query (demo-data.js).
    if(isComposition&&s.orders){
      $("runGenopFirst").querySelector("small").textContent=s.orders.genopFirst;
      $("runPlanner").querySelector("small").textContent=s.orders.planner;
    }

    if(!isComposition){
      $("executionResult").hidden=true;
      $("executionResult").className="execution-result";
    }
    if(!isAttribute){
      $("attributeResult").hidden=true;
    }
    $("attributeResultTitle").textContent=s.generatedTitle||"Generated player positions";
    $("attributeAccuracyLabel").textContent=s.metricLabel||"Correct among answered entities in Wikidata";
    $("attributeResultNote").textContent=s.note||"";

    const executionLabel=liveOn()&&s.id==="entity"?"live":"pre-recorded";
    $("runModeDefault").textContent=executionLabel;

    $("caption").textContent=
      isComposition
      ? "Compose + defer compares the same logical query under two physical execution orders: GENOP-first versus planner-deferred GENOP."
      : `${currentDb().label} uses the full ${currentDb().source} knowledge graph.`;
    if(s.recordedModel&&executionLabel==="pre-recorded"){
      $("caption").textContent+=` Recorded answers of ${s.recordedModel} from the evaluation of the paper.`;
    }
  }


  const BUILTIN_PREFIXES={
    ex:"http://example.org/",
    wd:"http://www.wikidata.org/entity/",
    rdf:"http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    rdfs:"http://www.w3.org/2000/01/rdf-schema#",
    xsd:"http://www.w3.org/2001/XMLSchema#"
  };

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

  function setEngineStatus(text,kind="",retry=null){
    const el=$("engineConnectionStatus");
    if(!el)return;
    el.textContent=text;
    el.className="engine-connection-status"+(kind?` ${kind}`:"");
    if(retry)el.append(retryButton(retry));
  }

  // A request that failed can be repeated from its message, as the T-MIGRANTS explorer offers
  // Retry when its server does not answer.
  function retryButton(action){
    const button=document.createElement("button");
    button.type="button";button.className="retry-btn";button.textContent="Retry";
    button.onclick=action;
    return button;
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
        setEngineStatus(`Connection failed: ${err.message||err}`,"err",()=>test.click());
      }finally{
        test.disabled=false;test.textContent="Test engine";
      }
    };
    sync();
    // Served by the service on another port, the page calls that service (engine-client.js).
    const preset=base.value;
    if(engine)engine.locate().then(found=>{if(base.value===preset&&found!==preset){base.value=found;sync()}});
  }

  // An uploaded graph is parsed by N3.js (docs/vendor), which covers the Turtle 1.1 grammar:
  // string escapes and triple-quoted literals, blank node property lists and collections,
  // @base, SPARQL-style PREFIX and BASE, and also N-Triples and TriG. The explorer draws the
  // IRIs and blank nodes with their rdf:type and rdfs:label; other literals are kept in the
  // triples but not drawn.
  function parseTurtleToDatabase(text,fileName){
    if(typeof N3==="undefined")throw new Error("vendor/n3.v2.13.12.min.js failed to load.");
    const prefixes={...BUILTIN_PREFIXES};
    const parser=new N3.Parser({baseIRI:`file:///${encodeURIComponent(fileName||"upload.ttl")}`});
    let quads;
    try{
      quads=parser.parse(String(text),null,(prefix,iri)=>{prefixes[prefix]=typeof iri==="string"?iri:iri.value});
    }catch(err){
      throw new Error(`RDF syntax error: ${err.message||err}`);
    }

    const termId=t=>t.termType==="BlankNode"?`_:${t.value}`:t.value;
    const triples=[];
    const tripleSeen=new Set();
    for(const q of quads){
      const subject=termId(q.subject),predicate=q.predicate.value;
      const object=q.object.termType==="Literal"
        ? {kind:"literal",value:q.object.value,raw:q.object.value,language:q.object.language||"",datatype:q.object.datatype?.value||""}
        : {kind:"iri",value:termId(q.object),raw:termId(q.object)};
      // Literals that differ in their language or datatype are different terms.
      const key=`${subject}\u0001${predicate}\u0001${object.kind}\u0001${object.value}\u0001${object.language||""}\u0001${object.datatype||""}`;
      if(tripleSeen.has(key))continue;
      tripleSeen.add(key);
      triples.push({s:subject,p:predicate,o:object});
    }

    if(!triples.length)throw new Error("No RDF triples could be parsed from this file.");

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
      label:fileName.replace(/\.(ttl|turtle|nt|trig|nq|n3)$/i,"")||"Uploaded KG",
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
        newView();
        state.dbKey=key;
        $("databaseSelect").value=key;
        localStorage.setItem("gensparql.database",key);
        status.textContent=`${db.graph.nodes.length} nodes · ${db.graph.links.length} edges`;
        syncPreRecordedVisibility();
        initGraph();
        clearMessage();
        writeUrl();

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

  // The column beside the graph scrolls by itself (styles.css): a card that a run or a click
  // fills comes into view there, with as little scrolling as it needs.
  function revealInColumn(el){
    const col=el?.closest(".query-column");
    if(!col||el.hidden||col.scrollHeight<=col.clientHeight+1)return;
    const c=col.getBoundingClientRect(),r=el.getBoundingClientRect();
    const z=col.offsetWidth?c.width/col.offsetWidth:1;     // the zoom of the page
    if(r.top>=c.top&&r.top<=c.bottom-Math.min(160*z,r.height))return;
    col.scrollTo({top:col.scrollTop+(r.top-c.top)/z-8,behavior:"smooth"});
  }

  function renderLiveQueryResult(result){
    const panel=$("liveQueryResult"),meta=$("liveResultMeta"),body=$("liveResultBody");
    panel.hidden=false;
    requestAnimationFrame(()=>revealInColumn(panel));
    const displayRows=result.rows.slice(0,250);
    const shown=displayRows.length;
    const plan=result.plan?` · ${result.plan}`:"";
    meta.textContent=`${result.total} row${result.total===1?"":"s"}${result.total>shown?` · showing first ${shown}`:""}${plan}`;
    if(!result.rows.length){
      body.innerHTML='<div class="live-result-empty">No matching rows.</div>';
      return;
    }
    const head=result.vars.map(v=>`<th>?${esc(v)}</th>`).join("");
    const rows=displayRows.map((row,index)=>`<tr tabindex="0" aria-label="Inspect result row ${index+1}">${result.vars.map(v=>`<td>${esc(displayQueryValue(row[v],result.prefixes))}</td>`).join("")}</tr>`).join("");
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
    // A hub such as a Nobel field points to a thousand awards; it keeps its most connected
    // neighbours and then the most recent ones by label, as the Nobel overview does, instead of
    // pulling the whole graph into the view.
    const degreeOf=other=>(outgoing.get(other)||[]).length+(incoming.get(other)||[]).length;
    const MAX_PARENTS=12;
    resultIds.forEach(id=>{
      let parents=[...new Set((outgoing.get(id)||[])
        .map(item=>item.id)
        .filter(other=>!resultIds.has(other)))];
      if(parents.length>MAX_PARENTS){
        parents=parents
          .sort((a,b)=>degreeOf(b)-degreeOf(a)||String(nodeById.get(b)?.label||b).localeCompare(String(nodeById.get(a)?.label||a)))
          .slice(0,MAX_PARENTS);
      }
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
    graph.nodes.forEach(node=>{delete node.liveRadiusScale});
    const stage=$("graphStage");
    const width=stage.clientWidth||760;
    const height=stage.clientHeight||570;
    const pad=10;
    const inner={x:pad,y:pad,w:Math.max(120,width-pad*2),h:Math.max(100,height-pad*2)};
    const nodeById=new Map(graph.nodes.map(n=>[n.id,n]));
    let results=[...resultIds].map(id=>nodeById.get(id)).filter(Boolean);
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

    const connected=(a,b)=>graph.links.some(link=>{
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

    // A generated value is placed beside its entity afterwards (redistributeLiveQueryGraph). The
    // few entities of one ring around their context (ringNodes) are laid out without their
    // values, so that they stay around the context. More entities keep their values in the grid
    // of unconnected results: the compaction pulls each entity towards its value, which spreads
    // the entities and leaves room for the values.
    let leftOut=[];
    if(results.some(n=>n.liveGeneratedPos)&&results.filter(n=>!n.liveGeneratedPos).length<=6){
      leftOut=results.filter(n=>n.liveGeneratedPos);
      const generated=new Set(leftOut.map(n=>n.id));
      const endId=end=>typeof end==="object"?end.id:end;
      results=results.filter(n=>!generated.has(n.id));
      graph={nodes:graph.nodes.filter(n=>!generated.has(n.id)),
        links:graph.links.filter(l=>!generated.has(endId(l.source))&&!generated.has(endId(l.target)))};
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
    // Until then a value that the layout leaves out sits on its entity, so that the zoom of the
    // fit, which sizes the gaps of the live view (livePx), is that of the graph as laid out.
    leftOut.forEach(node=>{
      const source=nodeById.get(node.sourceAthleteId);
      if(source){node.x=node.fx=source.x;node.y=node.fy=source.y;}
    });
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
    // A generated value stays next to the entity it was generated for: it is never swapped,
    // and it moves with its entity when that entity is swapped.
    const children=new Map();
    (graph.nodes||[]).filter(node=>node.liveGeneratedPos&&node.sourceAthleteId).forEach(node=>{
      if(!children.has(node.sourceAthleteId))children.set(node.sourceAthleteId,[]);
      children.get(node.sourceAthleteId).push(node);
    });
    const shift=(node,dx,dy)=>(children.get(node.id)||[]).forEach(child=>{
      child.x=child.fx=child.x+dx;child.y=child.fy=child.y+dy;
    });
    const swap=(a,b)=>{
      const x=a.x,y=a.y,dx=b.x-a.x,dy=b.y-a.y;
      a.x=a.fx=b.x;a.y=a.fy=b.y;
      b.x=b.fx=x;b.y=b.fy=y;
      shift(a,dx,dy);shift(b,-dx,-dy);
    };

    let current=crossingCount();
    for(let pass=0;pass<10&&current>0;pass++){
      const candidates=new Map();
      for(let i=0;i<links.length&&candidates.size<80;i++)for(let j=i+1;j<links.length&&candidates.size<80;j++){
        const e1=links[i],e2=links[j];
        if(!independent(e1,e2)||!crosses(e1.a,e1.b,e2.a,e2.b))continue;
        for(const [a,b] of [[e1.a,e2.a],[e1.a,e2.b],[e1.b,e2.a],[e1.b,e2.b]]){
          if(a===b||layer(a)!==layer(b)||layer(a)==="generated")continue;
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
    const px=livePx();
    if(node.liveContextDepth===2)return {font:10*px,height:13*px};
    if(node.liveContextDepth===1)return {font:7.5*px,height:10.5*px};
    return {font:8.8*px,height:12*px};
  }

  function liveLabelEstimatedWidth(node,fontOverride=null){
    const font=fontOverride||liveLabelMetrics(node).font;
    const text=String(node.label||node.id||"");
    // Intentionally conservative: this is wider than the average rendered
    // sans-serif glyph, so a successful estimated layout stays separated after
    // the browser renders the actual text.
    const px=livePx();
    return Math.max(18*px,text.length*font*.72+5*px);
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
    const px=livePx();
    const x=Number.isFinite(node.x)?node.x:0,y=Number.isFinite(node.y)?node.y:0;
    if(side==="above"||side==="below"){
      // Centred on the node, the text just above or below it; dy is the baseline offset
      // that nodeLabelY renders (it adds the 3.5 px that centre a side label on the node).
      const offset=side==="above"
        ?-(nodeRadius(node)+4*px+metrics.height*.28)
        :nodeRadius(node)+4*px+metrics.height*.78;
      const cy=y+offset;
      return {
        left:x-width/2,
        right:x+width/2,
        top:cy-metrics.height*.78,
        bottom:cy+metrics.height*.28,
        dy:offset-3.5*px
      };
    }
    const gap=nodeRadius(node)+5*px;
    const cy=y+dy;
    const left=side==="left"?x-gap-width:x+gap;
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

  function packMandatoryLiveLabels(nodes,view,px=1){
    if(!nodes.length)return;
    const edgePad=6*px;
    const availableW=Math.max(120*px,view.w-edgePad*2);
    const availableH=Math.max(100*px,view.h-edgePad*2);
    const fonts=[8.6,8.1,7.6,7.1,6.6,6.2].map(font=>font*px);
    let layout=null;

    for(const font of fonts){
      const maxTextW=Math.max(...nodes.map(node=>liveLabelEstimatedWidth(node,font)),28*px);
      const labelH=font*1.38;
      for(let cols=1;cols<=Math.min(12,nodes.length);cols++){
        const rows=Math.ceil(nodes.length/cols);
        const cellW=availableW/cols;
        const cellH=availableH/rows;
        if(cellW<maxTextW+8*px||cellH<labelH+5*px)continue;
        const score=Math.abs((cellW/cellH)-2.8)+rows*.002;
        if(!layout||score<layout.score)layout={font,cols,rows,cellW,cellH,score};
      }
      if(layout)break;
    }

    if(!layout){
      const font=6*px;
      let best=null;
      for(let cols=1;cols<=Math.min(14,nodes.length);cols++){
        const rows=Math.ceil(nodes.length/cols);
        const cellW=availableW/cols,cellH=availableH/rows;
        const maxTextW=Math.max(...nodes.map(node=>liveLabelEstimatedWidth(node,font)),28*px);
        const fit=Math.min(cellW/(maxTextW+6*px),cellH/(font*1.38+4*px));
        if(!best||fit>best.fit)best={font,cols,rows,cellW,cellH,fit};
      }
      layout=best;
    }

    const slots=[];
    for(let r=0;r<layout.rows;r++){
      for(let c=0;c<layout.cols;c++){
        if(slots.length>=nodes.length)break;
        const left=view.x+edgePad+c*layout.cellW;
        const top=view.y+edgePad+r*layout.cellH;
        slots.push({
          x:left+4*px,
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
    // The view is laid out small and zoomed to fit, so sizes and gaps are screen pixels
    // converted to graph units with the scale of the fit, and labels stay in the shown region.
    const fit=liveFitTransform(graph.nodes);
    state.liveLabelScale=fit.scale;
    const px=1/fit.scale;
    const view=liveViewBox(fit);
    // Circles keep about their designed size on screen; a view larger than the stage keeps them.
    const radiusScale=Math.min(1,px*1.15);
    graph.nodes.forEach(node=>{node.liveRadiusScale=radiusScale});
    const edgePad=4*px;
    const placed=[];

    graph.nodes.forEach(node=>{
      node.liveShowLabel=false;
      node.liveLabelDy=0;
      node.liveLabelAbsX=null;
      node.liveLabelAbsY=null;
      node.liveLabelFont=Number.isFinite(node.liveVisualFont)?node.liveVisualFont*px:null;
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
      // The results are the answer, so their labels are placed first; a context label is
      // shown where it still fits, and hovering shows the rest.
      candidates=[...graph.nodes.filter(node=>node.liveQueryResult),...structural]
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

    const mandatoryIds=new Set(!state.liveLabelsMandatory?[]
      :allDisplayedMandatory?candidates.map(node=>node.id)
      :candidates.filter(node=>node.liveQueryResult).map(node=>node.id));
    const sideOrder=node=>{
      const sides=node.labelSide==="left"?["left","right"]
        :node.labelSide==="right"?["right","left"]
        :(node.x||0)>view.x+view.w/2?["right","left"]:["left","right"];
      return [...sides,"below","above"];
    };
    const dyAttempts=[0,-8,8,-16,16].map(dy=>dy*px);
    const nodeObstacles=graph.nodes.map(node=>{
      const radius=nodeRadius(node)+2*px;
      return {id:node.id,left:node.x-radius,right:node.x+radius,top:node.y-radius,bottom:node.y+radius};
    });
    let mandatoryPlaced=0;

    candidates.forEach(node=>{
      let chosen=null;
      for(const side of sideOrder(node)){
        for(const dy of (side==="above"||side==="below"?[0]:dyAttempts)){
          const box=liveLabelBox(node,side,dy);
          if(box.left<view.x+edgePad||box.right>view.x+view.w-edgePad||box.top<view.y+edgePad||box.bottom>view.y+view.h-edgePad)continue;
          if(placed.some(other=>liveLabelBoxesOverlap(box,other,4*px)))continue;
          if(nodeObstacles.some(other=>other.id!==node.id&&liveLabelBoxesOverlap(box,other,px)))continue;
          chosen={box,side,dy};
          break;
        }
        if(chosen)break;
      }
      if(!chosen){
        // A team or a field in the middle of its results has no free side here; declutterLabels
        // finds it a place over the results once they are drawn.
        if(node.liveContextDepth===1)node.liveShowLabel=true;
        return;
      }
      node.liveShowLabel=true;
      node.labelSide=chosen.side;
      node.liveLabelDy=Number.isFinite(chosen.box.dy)?chosen.box.dy:chosen.dy;
      placed.push(chosen.box);
      if(mandatoryIds.has(node.id))mandatoryPlaced+=1;
    });

    if(state.liveLabelsMandatory&&mandatoryPlaced<mandatoryIds.size){
      // A mandatory label that found no place next to its node takes a free slot of the
      // compact layout; the labels that were placed stay next to their nodes.
      packMandatoryLiveLabels(candidates.filter(node=>mandatoryIds.has(node.id)&&!node.liveShowLabel),view,px);
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
      // getBBox is in the coordinates of the node, so the position of the node is added.
      const x=Number.isFinite(d.x)?d.x:0,y=Number.isFinite(d.y)?d.y:0;
      items.push({d,el:this,box:{left:x+box.x,right:x+box.x+box.width,top:y+box.y,bottom:y+box.y+box.height},priority});
    });
    items.sort((a,b)=>a.priority-b.priority||String(a.d.label||a.d.id).localeCompare(String(b.d.label||b.d.id)));
    const placed=[];
    items.forEach(item=>{
      if(placed.some(other=>liveLabelBoxesOverlap(item.box,other,4*livePx()))){
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
      // The overview of the page, as after loading the graph, rather than a free layout of the
      // whole graph, which takes long to settle.
      initGraph();
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
    state.liveLabelScale=liveFitTransform(state.graph.nodes).scale;

    const generatedBySource=new Map();
    state.graph.nodes.filter(n=>n.liveGeneratedPos&&n.sourceAthleteId).forEach(node=>{
      const key=node.sourceAthleteId;
      if(!generatedBySource.has(key))generatedBySource.set(key,[]);
      generatedBySource.get(key).push(node);
    });
    const nodeById=new Map(state.graph.nodes.map(n=>[n.id,n])),contextOf=new Map();
    state.graph.links.forEach(link=>{
      const a=nodeById.get(typeof link.source==="object"?link.source.id:link.source);
      const b=nodeById.get(typeof link.target==="object"?link.target.id:link.target);
      if(!a||!b||a.liveGeneratedPos||b.liveGeneratedPos)return;
      [[a,b],[b,a]].forEach(([from,to])=>{
        if(!generatedBySource.has(from.id))return;
        if(!contextOf.has(from.id))contextOf.set(from.id,[]);
        contextOf.get(from.id).push(to);
      });
    });
    generatedBySource.forEach((nodes,sourceId)=>{
      const source=state.graph.nodes.find(n=>n.id===sourceId);
      if(!source)return;
      // On the side of the entity away from its context, where the entity lies after the
      // compaction.
      const context=contextOf.get(sourceId)||[];
      const cx=context.reduce((sum,n)=>sum+n.x,0)/context.length,cy=context.reduce((sum,n)=>sum+n.y,0)/context.length;
      const baseAngle=context.length&&Math.hypot(source.x-cx,source.y-cy)>1e-6?Math.atan2(source.y-cy,source.x-cx)
        :source._liveAngle||stableUnit(source.id,411)*Math.PI*2;
      const distance=nodeRadius(source)+Math.max(...nodes.map(nodeRadius))+6*livePx();
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
        // The similarity join binds an output variable to an entity of the graph, which is
        // already a result node; only a generated literal becomes a node of its own.
        if(value.kind==="iri"){const id=nodeIdForIri(value.value);if(id&&nodeById.has(id))return;}
        const text=value.kind==="iri"?displayQueryValue(value,result.prefixes):String(value.value);
        const key=`${source.id}|${variable}|${value.value}`;
        if(seen.has(key))return;
        seen.add(key);
        const angle=stableUnit(key,307)*Math.PI*2;
        const distance=nodeRadius(source)+3.0+6*livePx();   // the two circles do not touch
        const id=`live:genop:${rowIndex}:${varIndex}:${hashString(key)}`;
        const node={
          id,label:text,aliases:[text],type:"Generated",
          generatedAttribute:true,liveGeneratedPos:true,generatedVariable:variable,
          sourceAthleteId:source.id,sourceAthleteLabel:source.label,positionLabel:text,
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
    // The variable on every edge says the same thing many times over; the result table and the
    // legend name it once.
    if(added.length>8)links.forEach(link=>{link.hideGeneratedEdgeLabel=true});
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
      worldCupWinners:`PREFIX ex:   <http://example.org/>

# Figure 2(b) of the paper: a base-mode GENOP lists names, and the similarity join
# maps them to the athletes of the graph at the threshold 0.85.
SELECT ?player
WHERE {
  ?player a ex:Athlete .

  GENOP(
    "List footballers who have won the World Cup.",
    (?player),
    <model>,
    0.85
  )
}`,
      nationalTeams:`PREFIX ex:   <http://example.org/>

# Scenario 2: map generated team names to the teams of the graph.
# Change the threshold 0.85 to see which names map.
SELECT ?team
WHERE {
  ?team a ex:Team .

  GENOP(
    "List the national teams that play at the 2026 FIFA World Cup.",
    (?team),
    <model>,
    0.85
  )
}`,
      groupADefenders:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

# Scenario 3: the planner evaluates the patterns for Group A before GENOP,
# so the LLM is called for the 104 players of Group A, not for all 1,248.
SELECT ?name ?pos
WHERE {
  ?t ex:inGroup ?g .
  ?g rdfs:label "Group A" .
  ?p ex:playsFor ?t ;
     rdfs:label ?name .

  GENOP(
    "Which position does the footballer {?name} primarily play: goalkeeper, defender, midfielder or forward?",
    (?pos),
    <model>
  )

  FILTER(LCASE(STR(?pos)) = "defender")
}`,
      nobelFields:`PREFIX ex:   <http://example.org/>

# Scenario 2: map generated field names to the Nobel fields of the graph.
# At 0.85 neither Physics nor Chemistry maps, because the graph labels them
# Nobel Prize in Physics and Nobel Prize in Chemistry.
SELECT ?field
WHERE {
  ?field a ex:NobelField .

  GENOP(
    "List the fields in which the Nobel Prize is awarded.",
    (?field),
    <model>,
    0.85
  )
}`,
      physicsBirthYears:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX wd:   <http://www.wikidata.org/entity/>
PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>

# Scenario 1: generate the birth years that the graph leaves out,
# here for the Physics laureates since 2020.
SELECT ?name ?born
WHERE {
  ?a ex:field wd:Q38104 ;
     ex:winningYear ?y ;
     ex:winner ?l .
  ?l rdfs:label ?name .
  FILTER(xsd:integer(STR(?y)) >= 2020)

  GENOP(
    "In which year was the Nobel laureate {?name} born? Answer with the year only.",
    (?born),
    <model>
  )
}
ORDER BY ?name`,
      physicsContributions:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX wd:   <http://www.wikidata.org/entity/>
PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>

# Scenario 3: the planner applies the FILTER on the award year before GENOP,
# so the LLM is called for the 17 Physics awards since 2020, not for all 230.
SELECT ?name ?contribution
WHERE {
  ?a ex:field wd:Q38104 ;
     ex:winningYear ?y ;
     ex:winner ?l .
  ?l rdfs:label ?name .
  FILTER(xsd:integer(STR(?y)) >= 2020)

  GENOP(
    "For which contribution did {?name} receive the Nobel Prize in Physics? Answer with one short phrase.",
    (?contribution),
    <model>
  )
}
ORDER BY ?name`,
      nobelQuantumLaureates:`PREFIX ex:   <http://example.org/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT DISTINCT ?person ?laureate ?year
WHERE {
  ?person a ex:NobelLaureate ;
          rdfs:label ?laureate ;
          ex:wonNobelPrize ?award .

  ?award ex:field <http://www.wikidata.org/entity/Q38104> ;
         ex:winningYear ?year .

  GENOP(
    "List the Nobel laureates in Physics who made foundational contributions to quantum mechanics. Return only the names.",
    (?person),
    <model>
  )
}
ORDER BY ?year`
    };
    // The editor grows with the query, up to the maximum height of the stylesheet.
    const fitEditor=()=>{editor.style.height="auto";editor.style.height=`${editor.scrollHeight+2}px`};
    editor.addEventListener("input",fitEditor);
    // Choosing a sample loads it into the editor at the current θ. While the editor holds anything
    // else, the picker shows "Custom query", so that choosing the same sample again restores it.
    const picker=$("liveSampleSelect");
    const sampleText=key=>window.GENSPARQL_EXPLORER.withThreshold(samples[key],$("theta").value);
    editor.addEventListener("input",()=>{if(samples[picker.value]&&editor.value!==sampleText(picker.value))picker.value=""});
    const initialSample=state.dbKey==="nobelPrize"?"physicsBirthYears":"argentinaPositions";
    editor.value=sampleText(initialSample);
    picker.value=initialSample;
    requestAnimationFrame(fitEditor);
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
    // Each sample runs over one of the two built-in graphs: loading it selects that graph. The focus
    // stays on the picker, so that the arrow keys can step through the samples.
    const sampleDb={argentinaPositions:"fifa2026",worldCupWinners:"fifa2026",nationalTeams:"fifa2026",
      groupADefenders:"fifa2026",nobelFields:"nobelPrize",physicsBirthYears:"nobelPrize",
      physicsContributions:"nobelPrize",nobelQuantumLaureates:"nobelPrize"};
    picker.onchange=()=>{
      const key=picker.value;
      if(!samples[key])return;
      editor.value=sampleText(key);
      fitEditor();
      const db=sampleDb[key], select=$("databaseSelect");
      if(db&&select&&select.value!==db&&[...select.options].some(o=>o.value===db)){
        select.value=db;select.dispatchEvent(new Event("change"));
      }
    };
    $("clearLiveQuery").onclick=()=>{editor.value="";picker.value="";fitEditor();editor.focus();$("liveQueryResult").hidden=true};
    $("liveRunBtn").onclick=async()=>{
      clearMessage();
      const btn=$("liveRunBtn");
      btn.disabled=true;btn.textContent="Running…";
      const startedAt=Date.now();
      const ticker=setInterval(()=>{btn.textContent=`Running… ${Math.round((Date.now()-startedAt)/1000)} s`},1000);
      const view=state.view;
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
          apiKey:embeddingKey(),
          baseUrl:$("embBase")?.value?.trim()||"",
          model:$("embModel")?.value?.trim()||""
        }:{method:"jaccard"};
        const result=await engine.query(editor.value,{datasetId,prefixes,includeTrace:true,llm,similarity});
        if(view!==state.view){
          showMessage(`The query on ${db.label} finished after the database changed to ${currentDb().label}, so its result is not shown.`,"warn");
          return;
        }
        const executorLabel="Java engine";
        setEngineStatus(`Java query completed · ${result.total} row${result.total===1?"":"s"}`,"ok");

        renderLiveQueryResult(result);
        highlightLiveQueryResult(result);
        addLiveGeneratedNodes(result);
        window.GENSPARQL_EXPLORER?.showQuery(result);
        const opText=result.operators?.length?` · operators: ${[...new Set(result.operators)].join(", ")}`:"";
        const genopText=result.genopCalls?` · ${result.genopCalls} GENOP call${result.genopCalls===1?"":"s"}`:"";
        // The service falls back to the Jaccard similarity for the whole query if the embedding model fails.
        const sim=result.trace?.similarity;
        const fellBack=sim&&sim.requested==="embedding"&&sim.used!=="embedding";
        const simText=fellBack?` The embedding model failed (${sim.fallbackReason||"no reason given"}), so the query used the Jaccard similarity.`:"";
        const seconds=((Date.now()-startedAt)/1000).toFixed(1);
        const graphText=result.total?" Matching entities are shown in the graph.":" The graph keeps the full KG.";
        showMessage(`${executorLabel} completed on ${db.label} in ${seconds} s: ${result.total} row${result.total===1?"":"s"}${opText}${genopText}.${graphText}${simText}`,fellBack?"warn":"ok");
      }catch(err){
        if(view!==state.view)return;
        $("liveQueryResult").hidden=true;
        showMessage(`Query failed: ${err.message||err}`,"err",()=>$("liveRunBtn").click());
      }finally{
        clearInterval(ticker);
        btn.disabled=false;btn.textContent="Run query ▶";
      }
    };
  }

  function initTheme(){
    $("themeBtn").onclick=()=>{
      // The page is light unless data-theme says dark: the stylesheet does not follow the theme of
      // the system, so the toggle must not either, or its first click sets the light theme again.
      const r=document.documentElement;
      r.setAttribute("data-theme",r.getAttribute("data-theme")==="dark"?"light":"dark");
    };
  }

  function liveOn(){return pageMode==="live"&&$("key").value.trim().length>0}

  // The endpoint and model names of live mode survive a reload; the key does not.
  const LIVE_SETTINGS=["chatBase","chatModel","embBase","embModel"];
  function savedLiveSetting(id){
    try{return localStorage.getItem("gensparql.live."+id)}catch(_){return null}
  }
  function saveLiveSetting(id,value){
    try{localStorage.setItem("gensparql.live."+id,value)}catch(_){}
  }

  function initLive(){
    // The connection settings lie over the workspace (styles.css): a click outside them or
    // Escape closes them.
    const settings=$("connectionSettings");
    if(settings){
      document.addEventListener("click",e=>{if(settings.open&&!settings.contains(e.target))settings.open=false});
      document.addEventListener("keydown",e=>{if(e.key==="Escape"&&settings.open)settings.open=false});
    }
    $("chatBase").value=CFG.live.chatBase;
    $("chatModel").value=CFG.live.chatModel;
    $("embBase").value=CFG.live.embBase;
    $("embModel").value=CFG.live.embModel;
    LIVE_SETTINGS.forEach(id=>{
      const saved=savedLiveSetting(id);
      if(saved!==null&&saved.trim())$(id).value=saved;
      $(id).addEventListener("change",()=>saveLiveSetting(id,$(id).value.trim()));
    });
    const grounding=savedLiveSetting("grounding");
    if(grounding==="embedding")$("groundEmbedding").checked=true;
    else if(grounding==="jaccard")$("groundText").checked=true;
    CFG.live.embModels.forEach(m=>{const o=document.createElement("option");o.value=m.value;o.label=m.label;$("embModelOptions").appendChild(o)});
    $("key").addEventListener("input",renderScenario);
    // Both radios need their own handler: a change event only fires on the
    // input that became checked.
    const onGroundingMethodChange=async()=>{
      syncGrounding();
      saveLiveSetting("grounding",$("groundEmbedding")?.checked?"embedding":"jaccard");
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

  // The recorded mode hides the live panel, so the threshold row has its own choice of
  // similarity. It sets the radios of the live panel, which the grounding view reads.
  function initDemoGrounding(){
    const rebuild=async()=>{
      if(pageMode==="demo"&&currentScenario()?.id==="entity"&&state.graph?.nodes.some(n=>n.generatedCandidate)){
        try{
          await buildGroundEntityView({preserveViewport:true});
        }catch(err){
          showMessage(runFailureMessage(err),"err");
        }
      }
    };
    const choose=method=>{
      $("groundText").checked=method==="text";
      $("groundEmbedding").checked=method==="embedding";
      if(pageMode==="live"){
        syncGrounding();
        saveLiveSetting("grounding",method==="embedding"?"embedding":"jaccard");
      }
      writeUrl();
      window.GENSPARQL_EXPLORER?.graphChanged();
      rebuild();
    };
    $("demoGroundText").onchange=()=>choose("text");
    $("demoGroundEmbedding").onchange=()=>choose("embedding");
    syncDemoGrounding();
  }

  // The recorded embedding scores cover the names of the two built-in graphs. For an uploaded
  // graph the recorded mode offers Jaccard alone and says why.
  function syncDemoGrounding(){
    const embedding=$("demoGroundEmbedding");
    if(!embedding)return;
    const available=pageMode==="live"||Boolean(recordedEntityRun()?.embedding);
    embedding.disabled=!available;
    embedding.closest("label")?.classList.toggle("is-disabled",!available);
    $("demoGroundNote").hidden=available;
    $("demoGroundText").checked=$("groundText").checked;
    $("demoGroundEmbedding").checked=$("groundEmbedding").checked;
    if(!available&&embedding.checked){
      $("demoGroundText").checked=true;$("groundText").checked=true;
    }
  }

  function syncGrounding(){
    const on=$("groundEmbedding").checked;
    $("embeddingSettings").classList.toggle("is-disabled",!on);
    $("embBase").disabled=!on;$("embModel").disabled=!on;$("embKey").disabled=!on;
  }

  function showMessage(text,kind="ok",retry=null){
    const live=$("liveRunFeedback");
    if(live){
      live.textContent=text;live.className="live-run-feedback "+kind;
      if(retry)live.append(retryButton(retry));
    }
    // The recorded mode has no live query panel, so an error goes to the graph status.
    if(pageMode==="demo"&&kind==="err")$("graphStatus").textContent=text;
  }
  function clearMessage(){
    const live=$("liveRunFeedback");
    if(live){live.textContent="";live.className="live-run-feedback";}
  }

  function bindInspectorLinks(layer,links){
    state.linkHitSel=layer.append("g").attr("class","relation-hit-targets")
      .selectAll("line").data(links).join("line")
      .attr("class","relation-hit-target")
      .on("click",(event,link)=>{
        if(clickAfterDrag(event))return;
        event.stopPropagation();
        state.selectedId=null;state.inspectedLink=link;
        $("tooltip").hidden=true;
        window.GENSPARQL_EXPLORER?.showLink(link);
        applyClasses();
      });
  }

  function inspectNode(event,node){
    state.inspectedLink=null;
    window.GENSPARQL_EXPLORER?.showNode(node);
  }

  function clearInspector(){
    state.inspectedLink=null;
    window.GENSPARQL_EXPLORER?.overview();
  }

  function keyboardInspect(event){
    if(event.key!=="Enter"&&event.key!==" ")return;
    event.preventDefault();
    event.currentTarget.dispatchEvent(new MouseEvent("click",{bubbles:true}));
  }

  function initGraph(){
    if(typeof NativeGraph==="undefined"){
      $("graphStatus").textContent="Native graph renderer failed to load.";return;
    }
    if(state.simulation)state.simulation.stop();

    state.graph=graphForInitialDisplay();
    state.resultIds.clear();state.contextIds.clear();state.selectedId=null;state.focusMode=false;state.tooltipPinnedId=null;
    state.inspectedLink=null;
    resetPointerFocus();
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
    bindInspectorLinks(linkLayer,state.graph.links);

    state.generatedLabelSel=labelLayer.selectAll("text")
      .data(state.graph.links.filter(d=>d.generatedAttributeLink&&!d.hideGeneratedEdgeLabel))
      .join("text")
      .attr("class","generated-edge-label")
      .text(d=>d.predicate||"?generated");

    state.nodeSel=nodeLayer.selectAll("g")
      .data(state.graph.nodes,d=>d.id)
      .join("g")
      .attr("class",d=>nodeClass(d))
      .attr("role","button").attr("tabindex",0)
      .attr("aria-label",d=>`Inspect ${d.label}`)
      .on("keydown",keyboardInspect);

    // The radius in --r caps the outline of the circle (styles.css).
    state.nodeSel.append("circle").attr("r",nodeRadius).style("--r",d=>`${nodeRadius(d)}px`);
    styleNodeCircles(state.nodeSel);
    // Large built-in KGs use different leaf types (Athlete in FIFA,
    // NobelLaureate/NobelAward in Nobel). Keep only structural labels in the
    // overview; focused query results rebuild a smaller graph with result labels.
    const overviewGraph=state.graph.nodes.length>300&&!state.focusMode;
    const staticNobelOverview=state.dbKey==="nobelPrize"&&(state.graph.nobelCompactOverview||state.graph.nodes.length>1000)&&!state.focusMode;
    const overviewLeafTypes=new Set(["Athlete","NobelLaureate","NobelAward","CountryOrCitizenship"]);
    // In an overview a label grows on screen more slowly than the graph (styles.css, --k, and
    // labelGrowth): the circle's radius scales with the zoom, the gap of 7 px and the baseline
    // are in em of the counter-scaled font.
    const em=overviewGraph;
    state.nodeSel.filter(d=>!overviewGraph||!overviewLeafTypes.has(d.type)).append("text")
      .attr("x",d=>em?(nodeLabelAnchor(d)==="end"?-nodeRadius(d):nodeLabelAnchor(d)==="middle"?0:nodeRadius(d)):nodeLabelX(d))
      .attr("dx",d=>em?(nodeLabelAnchor(d)==="end"?"-0.74em":nodeLabelAnchor(d)==="middle"?null:"0.74em"):null)
      .attr("text-anchor",d=>nodeLabelAnchor(d))
      .attr("y",d=>em?0:nodeLabelY(d))
      .attr("dy",d=>em?"0.35em":null)
      .style("font-size",d=>Number.isFinite(d.liveLabelFont)?`${d.liveLabelFont}px`:null,"important")
      // The halo of a label sized for the zoom of a Run-result view is sized with it.
      .style("stroke-width",d=>Number.isFinite(d.liveLabelFont)?`${d.liveLabelFont*.3}px`:null,"important")
      .text(d=>d.label);
    // The labels of the leaves of an overview appear when the graph is zoomed in or the node is
    // in focus (styles.css).
    // A leaf label grows on screen as the other labels of the overview do (styles.css, --k): the
    // circle's radius scales with the zoom, the gap and the baseline are in em of the font.
    if(INTERACTIVE&&overviewGraph){
      state.nodeSel.filter(d=>overviewLeafTypes.has(d.type)).append("text")
        .attr("class","leaf-label")
        .attr("x",d=>nodeRadius(d))
        .attr("dx","0.74em")
        .attr("text-anchor","start")
        .attr("y",0)
        .attr("dy","0.35em")
        .text(d=>d.label);
    }

    appendSimilarityBoxes(state.nodeSel);

    state.zoom=NativeGraph.zoom().scaleExtent([.12,6]).on("zoom",e=>{state.zoomLayer.attr("transform",e.transform);updateZoomLabels(e.transform.k)});
    svg.call(state.zoom).on("dblclick.zoom",null);
    $("kgGraph").classList.toggle("large",overviewGraph);

    state.nodeSel.call(nodeDrag(staticNobelOverview));
    focusOnHover(state.nodeSel)
      .on("click",(e,d)=>{
        if(clickAfterDrag(e))return;
        e.stopPropagation();
        inspectNode(e,d);
        revealInColumn($("infoPanel"));
        if(state.graph?.nobelOverview&&d.type==="NobelField"){
          state.nobelExpandedField=state.nobelExpandedField===d.id?null:d.id;
          initGraph();
          return;
        }
        state.tooltipPinnedId=null;
        $("tooltip").hidden=true;
        state.selectedId=state.selectedId===d.id?null:d.id;
        applyClasses();
      });

    svg.on("click",e=>{if(clickAfterDrag(e))return;state.tooltipPinnedId=null;$("tooltip").hidden=true;state.selectedId=null;clearInspector();applyClasses()});

    state.simulation=NativeGraph.forceSimulation(state.graph.nodes)
      .force("link",NativeGraph.forceLink(state.graph.links).id(d=>d.id).distance(d=>d.source?.type==="Athlete"||d.target?.type==="Athlete"?20:40).strength(.78))
      .force("charge",NativeGraph.forceManyBody().strength(state.graph.nodes.length>300?-42:-125))
      .force("center",NativeGraph.forceCenter(width/2,height/2))
      .force("collision",NativeGraph.forceCollide().radius(d=>nodeRadius(d)+3).iterations(1))
      .on("tick",tick);
    state.anchored=makeElastic(state.simulation,state.graph.nodes);
    state.simulation.on("end.labels",scheduleLabelCull).on("tick.labels",cullWhileMoving);

    // A dense overview does not need
    // that level of precision and was keeping the main thread busy for several seconds.
    if(staticNobelOverview){
      // The circular Nobel overview already has fixed coordinates. Thousands
      // of additional force ticks only repeat SVG writes without changing it.
      state.simulation.alpha(0).stop();
      tick();
      scheduleLabelCull();
    }else if(overviewGraph){
      state.simulation.alphaDecay(.12).alphaMin(.03).velocityDecay(.5);
    }

    updateStatus();
    // Dense graphs settle in a short, aggressively decayed native simulation. Fit as soon as
    // that layout is useful instead of making the UI feel blocked, but not before its first
    // tick: the simulation starts the nodes without a position in a spiral around the origin,
    // and its first tick moves them to the middle of the stage, which the fit frames.
    const sim=state.simulation,delay=staticNobelOverview?0:(overviewGraph?240:600);
    const fitOnce=()=>{sim.on("tick.firstfit",null);if(state.simulation===sim)fitGraph(false)};
    if(staticNobelOverview||sim.alpha()<sim.alphaMin())setTimeout(fitOnce,delay);
    else{
      let ticked=false,due=false;
      sim.on("tick.firstfit",()=>{ticked=true;if(due)fitOnce()});
      setTimeout(()=>{due=true;if(ticked)fitOnce()},delay);
    }
    // A free d3 layout keeps spreading after that, so it is fitted once more when it comes to rest.
    if(INTERACTIVE&&!state.anchored){
      sim.on("end.fit",()=>{sim.on("end.fit",null);if(state.simulation===sim)fitGraph(false)});
    }
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
    if(d.groundingLink)return d.additionalPair?"link grounding-link additional-pair":"link grounding-link";
    if(d.potentialGroundingLink)return"link potential-grounding-link";
    if(d.compositionBindingLink)return"link composition-binding-link";
    if(d.compositionPathLink)return"link composition-path-link";
    return"link";
  }

  // A Run-result view is laid out small and zoomed to fit, so its circles are scaled back to
  // the size on screen they were designed for (liveRadiusScale, resolveLiveLabelCollisions).
  function nodeRadius(d){
    const r=baseNodeRadius(d);
    return Number.isFinite(d.liveRadiusScale)?r*d.liveRadiusScale:r;
  }

  function baseNodeRadius(d){
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
      .style("--node-stroke",d=>d.liveGeneratedPos?"1.6px":null)
      .style("filter",d=>d.liveGeneratedPos?"drop-shadow(0 0 4px rgba(245,158,11,.35))":null);
  }

  function arrangeFourReturnedPairs(){
    if(!state.graph?.nodes?.length)return false;
    const sources=state.graph.nodes.filter(n=>state.resultIds.has(n.id)&&!n.liveGeneratedPos);
    const generated=state.graph.nodes.filter(n=>n.liveGeneratedPos&&state.resultIds.has(n.id));
    if(sources.length!==4||generated.length!==4)return false;
    const bySource=new Map(generated.map(n=>[n.sourceAthleteId,n]));
    if(sources.some(src=>!bySource.has(src.id)))return false;
    // Four pairs that share a context node, such as the four teams of a group, stay on the ring
    // around that node (layoutLiveRelatedGraph), which keeps their links to it short.
    const sourceSet=new Set(sources.map(n=>n.id)),sharedBy=new Map();
    state.graph.links.forEach(link=>{
      const s=typeof link.source==="object"?link.source.id:link.source;
      const t=typeof link.target==="object"?link.target.id:link.target;
      const other=sourceSet.has(s)&&!sourceSet.has(t)?t:sourceSet.has(t)&&!sourceSet.has(s)?s:null;
      if(other)sharedBy.set(other,(sharedBy.get(other)||0)+1);
    });
    if([...sharedBy.values()].some(count=>count>1))return false;

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
    if((d.liveQueryResult||d.liveContextDepth)&&(d.labelSide==="above"||d.labelSide==="below"))return 0;
    const sideAware=d.liveQueryResult||d.liveContextDepth||d.generatedCandidate||d.generatedAttribute||d.hasGeneratedAttribute||d.compositionSource||d.compositionTeam||d.outerCountry;
    const gap=d.liveQueryResult||d.liveContextDepth?nodeRadius(d)+5*livePx():nodeRadius(d)+7;
    if(sideAware&&d.labelSide==="left")return -gap;
    return gap;
  }

  function nodeLabelAnchor(d){
    if(Number.isFinite(d.liveLabelAbsX))return"start";
    if(d.compositionGroup)return"middle";
    if((d.liveQueryResult||d.liveContextDepth)&&(d.labelSide==="above"||d.labelSide==="below"))return"middle";
    const sideAware=d.liveQueryResult||d.liveContextDepth||d.generatedCandidate||d.generatedAttribute||d.hasGeneratedAttribute||d.compositionSource||d.compositionTeam||d.outerCountry;
    return sideAware&&d.labelSide==="left"?"end":"start";
  }

  function nodeLabelY(d){
    if(Number.isFinite(d.liveLabelAbsY))return d.liveLabelAbsY-(Number.isFinite(d.y)?d.y:0);
    if(d.compositionGroup)return -(nodeRadius(d)+5);
    if(d.liveQueryResult||d.liveContextDepth)return 3.5*livePx()+(d.liveLabelDy||0);
    return d.generatedAttribute?-1:3.5;
  }

  function similarityBoxesOverlap(a,b){
    return a.left<b.right+SIMILARITY_BOX_GAP
      &&a.right+SIMILARITY_BOX_GAP>b.left
      &&a.top<b.bottom+SIMILARITY_BOX_GAP
      &&a.bottom+SIMILARITY_BOX_GAP>b.top;
  }

  // ?print: the two rings put the labels of a narrow figure on top of each other, so the
  // generated names form a left column and their closest entities of the graph a right
  // column, one row per name. The mapped names come first, then the names whose closest
  // entity stays below θ, then the names that share no word with any label. A similarity
  // box sits on the edge of its name, or right of the name when there is no edge.
  function layoutGroundingColumns(candidates,targets,cx,cy,links=[]){
    const ROW=29,HALF=95;
    const rank=c=>c.grounded?0:(c.bestId?1:2);
    const rows=[...candidates].sort((a,b)=>rank(a)-rank(b)||String(a.label).localeCompare(String(b.label)));
    const top=cy-(rows.length-1)*ROW/2;
    const targetRow=new Map();
    rows.forEach((c,i)=>{
      const y=top+i*ROW;
      c.x=cx-HALF;c.y=y;c.fx=c.x;c.fy=y;
      c.labelSide="left";
      if(c.bestId&&!targetRow.has(c.bestId))targetRow.set(c.bestId,y);
    });
    // A target that only an additional pair reaches takes the free row nearest to the first
    // name that reaches it.
    const rowOf=new Map(rows.map((c,i)=>[c.id,top+i*ROW]));
    const used=new Set(targetRow.values());
    links.forEach(link=>{
      const source=typeof link.source==="object"?link.source.id:link.source;
      const target=typeof link.target==="object"?link.target.id:link.target;
      if(!link.groundingLink||targetRow.has(target)||!rowOf.has(source))return;
      const y0=rowOf.get(source);
      const free=[...rowOf.values()].filter(y=>!used.has(y)).sort((p,q)=>Math.abs(p-y0)-Math.abs(q-y0));
      const y=free.length?free[0]:y0;
      targetRow.set(target,y);used.add(y);
    });
    targets.forEach(t=>{
      const y=targetRow.has(t.id)?targetRow.get(t.id):cy;
      t.x=cx+HALF;t.y=y;t.fx=t.x;t.fy=y;
    });
    rows.forEach(c=>{
      const boxCenter=c.bestId?cx:c.x+nodeRadius(c)+12+SIMILARITY_BOX_WIDTH/2;
      c.similarityBoxX=boxCenter-SIMILARITY_BOX_WIDTH/2-c.x;
      c.similarityBoxY=-SIMILARITY_BOX_HEIGHT/2;
    });
  }

  function layoutSimilarityBoxes(candidates,cx,cy){
    const placed=[];
    const step=SIMILARITY_BOX_HEIGHT+7,radialStep=Math.round(SIMILARITY_BOX_HEIGHT*.9);
    const tangentShifts=[0,1,-1,2,-2,3,-3,4,-4,5,-5].map(i=>i*step);
    const radialShifts=[0,1,2,3,4,-.5].map(i=>i*radialStep);

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
          ? `translate(${nodeRadius(d)+12},${-SIMILARITY_BOX_HEIGHT/2})`
          : `translate(${-nodeRadius(d)-SIMILARITY_BOX_WIDTH-12},${-SIMILARITY_BOX_HEIGHT/2})`;
      });

    const theta=Number($("theta").value);
    boxes.append("rect")
      .attr("width",SIMILARITY_BOX_WIDTH)
      .attr("height",SIMILARITY_BOX_HEIGHT)
      .attr("rx",0)
      .attr("ry",0)
      .style("fill",d=>PRINT_LAYOUT?null:scoreFill(d.score,theta));

    boxes.append("text")
      .attr("x",SIMILARITY_BOX_WIDTH/2)
      .attr("y",PRINT_LAYOUT?18:11)
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
    if(state.linkHitSel)state.linkHitSel
      .attr("x1",d=>d.source.x).attr("y1",d=>d.source.y)
      .attr("x2",d=>d.target.x).attr("y2",d=>d.target.y);

    if(state.generatedLabelSel){
      const f=d=>Number.isFinite(d.labelT)?d.labelT:.5;
      state.generatedLabelSel
        .attr("x",d=>d.source.x+(d.target.x-d.source.x)*f(d))
        .attr("y",d=>d.source.y+(d.target.y-d.source.y)*f(d)-6);
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
        `<div>Generated by GENOP</div>`+
        filterLine+
        `<code>${esc(d.id)}</code>`;
    }else if(d.generatedAttribute){
      const attributeLabel=d.generatedAttributeLabel||currentScenario()?.generatedPredicate||"generated attribute";
      tip.innerHTML=
        `<strong>${esc(d.label)}</strong>`+
        `<div>${esc(attributeLabel)} · generated by GENOP</div>`+
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
    // The last line says what a click does, as in the tooltips of the T-MIGRANTS explorer.
    const action=d.id===state.selectedId?"Click to release the selection":"Click or drag to inspect in Info";
    tip.insertAdjacentHTML("beforeend",`<div class="tooltip-action">${action}</div>`);
    moveTip(e);
  }
  // The tooltip follows the pointer and turns to the other side of it near the right or the
  // bottom edge of the stage instead of being cut off there.
  function moveTip(e){
    const stage=$("graphStage"),r=stage.getBoundingClientRect(),tip=$("tooltip");
    // The pointer is in screen pixels, the tooltip in those of the page, which a zoom of the page
    // (styles.css) makes larger.
    const z=stage.offsetWidth?r.width/stage.offsetWidth:1;
    const x=(e.clientX-r.left)/z,y=(e.clientY-r.top)/z,width=r.width/z,height=r.height/z;
    const w=tip.offsetWidth||260,h=tip.offsetHeight||80,gap=14,margin=8;
    let left=x+gap,top=y+gap;
    if(left+w+margin>width)left=x-w-gap;
    if(top+h+margin>height)top=y-h-gap;
    tip.style.left=Math.max(margin,left)+"px";
    tip.style.top=Math.max(margin,top)+"px";
  }

  function neighborIds(ids){
    const out=new Set();
    state.graph.links.forEach(l=>{
      const s=typeof l.source==="object"?l.source.id:l.source,t=typeof l.target==="object"?l.target.id:l.target;
      if(ids.has(s)&&!ids.has(t))out.add(t);if(ids.has(t)&&!ids.has(s))out.add(s);
    });
    return out;
  }

  // ---- Obsidian-style interaction, after the knowledge-graph explorer of the Circular Factory
  // demo (INTERACTIVE, defined with PRINT_LAYOUT). ----

  // The positions that a layout computed become anchors: a spring holds each node there instead
  // of pinning it, a link between two anchored nodes rests at its drawn length, and anchored
  // nodes do not repel, so the graph is at rest as drawn. A dragged node pulls its neighbours
  // along its links, and when it is let go the graph stays as the drag left it (reanchor). A
  // graph without computed positions keeps its forces.
  function makeElastic(sim,nodes){
    if(!INTERACTIVE)return false;
    const placed=nodes.filter(n=>n.fx!=null&&n.fy!=null);
    if(!placed.length){
      // A free layout has no anchors. A weak pull towards the centre, as the gravity of the
      // Circular Factory explorer, holds its unconnected parts together, and a limited reach of
      // the repulsion keeps distant parts from pushing each other out of view while a node is
      // dragged.
      const center=sim.force("center");
      const cx=center&&typeof center.x==="function"?center.x():0,cy=center&&typeof center.y==="function"?center.y():0;
      const charge=sim.force("charge");
      if(charge&&charge.distanceMax)charge.distanceMax(260);
      sim.force("gravityX",NativeGraph.forceX(cx).strength(.06)).force("gravityY",NativeGraph.forceY(cy).strength(.06));
      return false;
    }
    placed.forEach(n=>{n.ax=n.x=n.fx;n.ay=n.y=n.fy;n.fx=null;n.fy=null});
    const anchored=n=>n&&n.ax!=null;
    const link=sim.force("link");
    if(link){
      const distance=link.distance();
      link.distance((l,i,ls)=>anchored(l.source)&&anchored(l.target)
        ?Math.hypot(l.target.ax-l.source.ax,l.target.ay-l.source.ay):distance(l,i,ls));
    }
    const charge=sim.force("charge");
    if(charge){
      const strength=charge.strength();
      charge.strength((n,i,ns)=>anchored(n)?0:strength(n,i,ns));
    }
    if(placed.length===nodes.length)sim.force("collision",null);
    sim.force("center",null)
      .force("anchorX",NativeGraph.forceX(n=>anchored(n)?n.ax:0).strength(n=>anchored(n)?.25:0))
      .force("anchorY",NativeGraph.forceY(n=>anchored(n)?n.ay:0).strength(n=>anchored(n)?.25:0))
      .alphaDecay(.012);   // cools slowly enough for the springs to bring every node back
    if(placed.length===nodes.length)sim.alpha(0);
    return true;
  }

  // The graph stays as the drag left it, so that a view can be arranged by hand: the dropped node
  // and the neighbours that it pulled along are anchored where they are, and the links rest at
  // their new lengths.
  function reanchor(node){
    const sim=state.simulation;
    sim.nodes().forEach(n=>{if(n.ax!=null){n.ax=n.x;n.ay=n.y}});
    node.ax=node.fx??node.x;node.ay=node.fy??node.y;
    // d3 evaluates these accessors once per node when they are set, so setting them again makes
    // the forces read the new anchor.
    ["link","charge"].forEach(name=>{
      const force=sim.force(name),key=name==="link"?"distance":"strength";
      if(force)force[key](force[key]());
    });
    const ax=sim.force("anchorX"),ay=sim.force("anchorY");
    if(ax)ax.x(ax.x());
    if(ay)ay.y(ay.y());
  }

  // The nodes under the pointer belong to the graph that was just removed: a removed element
  // fires no mouseleave, so a kept hover id would dim the next view.
  function resetPointerFocus(){
    state.hoverId=null;state.dragId=null;state.dragStart=null;state.dragMoved=false;
    if(state.classFrame){cancelAnimationFrame(state.classFrame);state.classFrame=0;}
  }

  // A press and release on a node also fires a click, which selects or releases the node. After
  // a drag that moved the node, that click is swallowed: the drop has selected the node already.
  function clickAfterDrag(e){
    if(!state.suppressClick)return false;
    state.suppressClick=false;
    e.stopPropagation();
    return true;
  }

  // A dropped node is selected and shown in Info, as a click does, but a drag never releases a
  // selection and does not open a Nobel field. In the figure layout a drag only arranges.
  function selectDragged(d){
    inspectNode(null,d);
    state.tooltipPinnedId=null;
    $("tooltip").hidden=true;
    state.selectedId=d.id;
    applyClasses();
  }

  // A press becomes a drag once the pointer has moved more than the click distance in screen
  // pixels, the distance at which d3 stops firing the click (native-graph.js). Only then does the
  // drag reheat the simulation and pin the node, so a click leaves the layout as it is. In an
  // anchored graph a dropped node stays where it was dropped, with the neighbours that it pulled
  // along; otherwise the forces place it, or it stays pinned while query results are focused.
  const CLICK_DISTANCE=window.NativeGraph?.clickDistance??4;
  const clientPoint=e=>{
    const s=e.sourceEvent,p=s?.changedTouches?.[0]||s;
    return p&&Number.isFinite(p.clientX)?{x:p.clientX,y:p.clientY}:null;
  };
  function nodeDrag(staticLayout=false){
    return NativeGraph.drag()
      .on("start",e=>{state.dragStart=clientPoint(e);state.dragMoved=false})
      .on("drag",(e,d)=>{
        if(!state.dragMoved){
          const p=clientPoint(e),s=state.dragStart;
          if(p&&s&&(p.x-s.x)**2+(p.y-s.y)**2<=CLICK_DISTANCE**2)return;
          state.dragMoved=true;
          // At full heat at once, so that the neighbours follow from the first move. A drag event
          // counts its own gesture as active.
          if((INTERACTIVE||!staticLayout)&&e.active<=1){
            if(INTERACTIVE)state.simulation.alpha(Math.max(state.simulation.alpha(),.3));
            state.simulation.alphaTarget(INTERACTIVE?.3:.22).restart();
          }
          if(INTERACTIVE){state.dragId=d.id;scheduleClasses()}
        }
        d.fx=e.x;d.fy=e.y;
      })
      .on("end",(e,d)=>{
        const moved=state.dragMoved;
        state.dragStart=null;state.dragMoved=false;
        if(!moved)return;
        state.suppressClick=true;
        setTimeout(()=>{state.suppressClick=false},0);
        if(!PRINT_LAYOUT)selectDragged(d);
        if(!INTERACTIVE&&staticLayout){state.simulation.stop();tick();return}
        if(!e.active)state.simulation.alphaTarget(0);
        if(INTERACTIVE){state.dragId=null;scheduleClasses()}
        if(state.anchored)reanchor(d);
        if(state.anchored||!state.focusMode){d.fx=null;d.fy=null}
        scheduleLabelCull();
      });
  }

  // The tooltip, and outside the figure layout the focus: the hovered node and its neighbours
  // stay bright and the rest dims (applyClasses).
  function focusOnHover(selection){
    return selection
      .on("mouseenter",(e,d)=>{showTip(e,d);if(INTERACTIVE){state.hoverId=d.id;scheduleClasses()}})
      .on("mousemove",e=>{if(!state.tooltipPinnedId)moveTip(e)})
      .on("mouseleave",()=>{
        if(!state.tooltipPinnedId)$("tooltip").hidden=true;
        if(INTERACTIVE){state.hoverId=null;scheduleClasses()}
      });
  }

  function scheduleClasses(){
    if(state.classFrame)return;
    state.classFrame=requestAnimationFrame(()=>{state.classFrame=0;applyClasses()});
  }

  // In a large graph the labels of the small nodes fade out when zoomed out (styles.css), and the
  // labels compete for space on screen (cullLabels), which the zoom changes.
  function updateZoomLabels(k){
    if(!INTERACTIVE)return;
    const svg=$("kgGraph"),large=(state.graph?.nodes?.length||0)>300;
    svg.classList.toggle("zoom-far",large&&k<.45);
    svg.classList.toggle("zoom-near",k>=1.6);
    svg.classList.toggle("large",large);
    // The label size on screen (--k) and the placement follow when the gesture pauses, since
    // every change of --k recomputes the style of every label.
    scheduleLabelCull();
  }

  // ---- label placement in a large graph ------------------------------------------------------
  // The labels of an overview compete for space on screen: a label is drawn only if it does not
  // overlap a label placed before it. Focused nodes come first, then the structural nodes by
  // size, then the leaves by degree. A label grows more slowly than the graph, so zooming in
  // makes room for more of them and zooming out takes it away again; a leaf label of a node in focus
  // is shown by styles.css whatever this decides. The placement runs after a zoom, a drag and
  // the end of the layout, not on every frame.
  const LEAF_LABEL_MIN_ZOOM=.9;
  const labelWidthCache=new Map();
  let labelMeasure=null;

  function labelWidth(text,px){
    const key=`${px}\u0001${text}`;
    let w=labelWidthCache.get(key);
    if(w===undefined){
      labelMeasure||=document.createElement("canvas").getContext("2d");
      const family=getComputedStyle(document.body).getPropertyValue("--sans").trim()||"sans-serif";
      labelMeasure.font=`${px}px ${family}`;
      w=labelMeasure.measureText(text).width;
      labelWidthCache.set(key,w);
    }
    return w;
  }

  // While a layout moves, the placement runs a few times a second, so that labels placed at the
  // start do not drift onto each other before the layout comes to rest.
  function cullWhileMoving(){
    const now=performance.now();
    if(now-(state.labelCullAt||0)<300)return;
    state.labelCullAt=now;
    scheduleLabelCull();
  }

  // How much larger than at zoom 1 a label of a large graph is drawn on screen (cullLabels).
  function labelGrowth(k){
    return Math.min(1.8,Math.max(1,Math.sqrt(k)));
  }

  function scheduleLabelCull(){
    if(state.labelCullTimer)clearTimeout(state.labelCullTimer);
    state.labelCullTimer=setTimeout(()=>{state.labelCullTimer=0;cullLabels()},90);
  }

  function nodeDegrees(graph){
    const degree=new Map();
    const id=v=>typeof v==="object"&&v!==null?v.id:v;
    (graph.links||[]).forEach(l=>{
      degree.set(id(l.source),(degree.get(id(l.source))||0)+1);
      degree.set(id(l.target),(degree.get(id(l.target))||0)+1);
    });
    return degree;
  }

  function cullLabels(){
    if(!INTERACTIVE||!state.nodeSel||!state.graph?.nodes)return;
    const svg=$("kgGraph"),stage=$("graphStage");
    if(!svg||!stage)return;
    const large=state.graph.nodes.length>300;
    const t=NativeGraph.zoomTransform(svg),k=t.k;
    // A label of a large graph grows on screen with the square root of the zoom, between its
    // size at zoom 1 and 1.8 times that: zooming in makes the labels larger, but more slowly
    // than the graph, so the room that opens shows more labels instead of the same ones larger.
    // The stylesheet divides the font size by --k, the zoom over this growth.
    const grow=labelGrowth(k);
    svg.style.setProperty("--k",String(k/grow));
    const W=stage.clientWidth,H=stage.clientHeight;
    const farOut=svg.classList.contains("zoom-far");
    if(state.labelDegreeGraph!==state.graph){state.labelDegreeGraph=state.graph;state.labelDegrees=nodeDegrees(state.graph)}
    const degree=state.labelDegrees;
    // While the layout still moves, a leaf label would be placed for a moment and then drift
    // into its neighbours, so leaf labels wait for the end of the simulation.
    const sim=state.simulation;
    const settling=!!sim&&typeof sim.alpha==="function"&&sim.alpha()>(typeof sim.alphaMin==="function"?sim.alphaMin():.001);
    // The selection (applyClasses): pinned labels are drawn whatever else, the neighbours of the
    // selected node come next. Their nodes get .label-focus, which shows a label that the
    // stylesheet hides otherwise, as the name of a player in a result view.
    const pinned=state.labelPinned||new Set(),near=state.labelNear||new Set();
    const selected=state.selectedId&&state.graph.nodes.find(n=>n.id===state.selectedId);
    const selectedX=selected&&Number.isFinite(selected.x)?selected.x*k+t.x:null;
    const items=[];
    state.nodeSel.each(function(d){
      if(!Number.isFinite(d.x)||!Number.isFinite(d.y))return;
      const g=this,sx=d.x*k+t.x,sy=d.y*k+t.y;
      const isPinned=pinned.has(d.id),isNear=!isPinned&&near.has(d.id);
      g.classList.toggle("label-focus",isNear);
      const focus=isPinned||isNear||g.classList.contains("query-result")||g.classList.contains("context");
      const emphasized=g.classList.contains("query-result")||g.classList.contains("inspect");
      for(const el of g.querySelectorAll("text")){
        const leaf=el.classList.contains("leaf-label");
        if(!leaf&&!large)continue;                       // a small graph keeps every label
        if(leaf&&(k<LEAF_LABEL_MIN_ZOOM||settling)&&!focus){el.classList.remove("shown");continue}
        if(!leaf&&farOut&&!focus)continue;                // hidden by the zoom-far rule anyway
        const text=el.textContent||"";
        if(!text){continue}
        const px=(emphasized?11:9.5)*grow;               // its size on screen in a large graph
        const w=labelWidth(text,px),h=px*1.15;
        const gap=nodeRadius(d)*k+7*grow;                // the gap of .74em scales with the font
        let places;
        if(leaf){
          // A leaf label is drawn to the right of its node. The name of a neighbour of the selected
          // node goes to the side away from it, else to the other side, so that the names around
          // a hub spread to both sides.
          const right={side:"right",x0:sx+gap},left={side:"left",x0:sx-gap-w};
          places=isNear&&selectedX!=null?(sx<selectedX?[left,right]:[right,left]):[right];
        }else{
          const anchor=el.getAttribute("text-anchor")||"start";
          places=[{side:null,x0:anchor==="end"?sx-gap-w:anchor==="middle"?sx-w/2:sx+gap}];
        }
        items.push({el,leaf,focus,pinned:isPinned,id:d.id,
          priority:isPinned?0:isNear?1:focus?1.5:(leaf?3:2),weight:leaf?(degree.get(d.id)||0):nodeRadius(d),text,
          places:places.map(p=>({...p,id:d.id,x1:p.x0+w,y0:sy-h/2,y1:sy+h/2}))});
      }
    });
    items.sort((a,b)=>a.priority-b.priority||b.weight-a.weight||a.text.localeCompare(b.text));
    const cell=48,grid=new Map();
    const cells=b=>{
      const out=[];
      for(let cx=Math.floor(b.x0/cell);cx<=Math.floor(b.x1/cell);cx++)
        for(let cy=Math.floor(b.y0/cell);cy<=Math.floor(b.y1/cell);cy++)out.push(`${cx}_${cy}`);
      return out;
    };
    const pad=2;
    const overlaps=b=>{
      for(const key of cells(b)){
        const bucket=grid.get(key);
        if(!bucket)continue;
        for(const o of bucket){
          if(b.x0<o.x1+pad&&b.x1>o.x0-pad&&b.y0<o.y1+pad&&b.y1>o.y0-pad)return true;
        }
      }
      return false;
    };
    const place=b=>cells(b).forEach(key=>{(grid.get(key)||grid.set(key,[]).get(key)).push(b)});
    // The name of a leaf is drawn only where it covers no other node: a name over the dots of
    // its neighbours is hard to read and makes the view look denser than it is.
    const circleGrid=new Map();
    if(items.some(it=>it.leaf)){
      state.nodeSel.each(function(d){
        if(!Number.isFinite(d.x)||!Number.isFinite(d.y))return;
        const r=nodeRadius(d)*k,sx=d.x*k+t.x,sy=d.y*k+t.y;
        if(sx+r<0||sx-r>W||sy+r<0||sy-r>H)return;
        const b={id:d.id,x0:sx-r,x1:sx+r,y0:sy-r,y1:sy+r};
        cells(b).forEach(key=>{(circleGrid.get(key)||circleGrid.set(key,[]).get(key)).push(b)});
      });
    }
    const coversNode=b=>{
      for(const key of cells(b)){
        for(const c of circleGrid.get(key)||[]){
          if(c.id!==b.id&&b.x0<c.x1&&b.x1>c.x0&&b.y0<c.y1&&b.y1>c.y0)return true;
        }
      }
      return false;
    };
    // A label in focus may lie over the dots around its node, which are dimmed, but not over
    // another label.
    const free=(it,b)=>!(b.x1<0||b.x0>W||b.y1<0||b.y0>H)&&!overlaps(b)&&!(it.leaf&&!it.focus&&coversNode(b));
    for(const it of items){
      const chosen=it.pinned?it.places[0]:it.places.find(b=>free(it,b));
      if(chosen)place(chosen);
      if(it.leaf){
        it.el.classList.toggle("shown",!!chosen);
        if(chosen)setLeafLabelSide(it.el,chosen.side);
      }
      else it.el.classList.toggle("occluded",!chosen);
    }
    if(large)raiseLabelledNodes();
    else declutterLabels();
  }

  function setLeafLabelSide(el,side){
    if((el.dataset.side||"right")===side)return;
    el.dataset.side=side;
    const left=side==="left";
    el.setAttribute("text-anchor",left?"end":"start");
    el.setAttribute("x",String((left?-1:1)*nodeRadius(el.__data__)));
    el.setAttribute("dx",left?"-0.74em":"0.74em");
  }

  // ---- label placement in a small graph ----------------------------------------------------
  // A view of up to a few hundred nodes draws every label at a fixed side of its node, so the
  // labels of close nodes run into each other, into a similarity box or into another node.
  // When the layout is at rest and after a zoom, each label keeps its place if that place is
  // free, else moves to another side of its node, else is hidden until its node is hovered.
  // The labels are placed in the order of their priority (labelPriority), and the nodes of
  // the labels that come first are drawn last, so that no circle covers them.
  function labelPriority(g,d,opacity){
    const c=g.classList;
    if(state.selectedId===d.id||c.contains("inspect"))return 0;
    if(state.labelNear?.has(d.id))return .5;                // the neighbours of the selection
    if(opacity<.5||c.contains("outer-country")||c.contains("live-context-2"))return 6;
    if(c.contains("query-result")||c.contains("live-query-result-node")||c.contains("composition-survivor"))return 1;
    if(c.contains("candidate-dropped"))return 2.6;          // after the mapped names and the scores
    if(c.contains("live-context-1"))return 1.5;              // the team or field around the results
    if(c.contains("generated-candidate")||c.contains("generated-attribute")||c.contains("composition-pos-node")||c.contains("live-generated-pos"))return 2;
    if(c.contains("grounded-target")||c.contains("potential-ground-target")||c.contains("attribute-source")
      ||c.contains("composition-source")||c.contains("composition-team")||c.contains("composition-group"))return 3;
    return 5;
  }

  function declutterLabels(){
    const svgEl=$("kgGraph");
    if(!svgEl||!state.nodeSel)return;
    // Boxes on screen are measured in the pixels of the page, as the nodes are placed: a zoom of
    // the page (styles.css) makes the screen boxes larger by z.
    const frame=svgEl.getBoundingClientRect();
    const z=svgEl.clientWidth?frame.width/svgEl.clientWidth:1;
    const W=frame.width/z,H=frame.height/z;
    if(!W||!H)return;
    const t=NativeGraph.zoomTransform(svgEl),k=t.k||1;
    const rel=r=>({x0:(r.left-frame.left)/z,x1:(r.right-frame.left)/z,y0:(r.top-frame.top)/z,y1:(r.bottom-frame.top)/z});
    const styleOf=new Map();
    const style=e=>{let s=styleOf.get(e);if(!s){s=getComputedStyle(e);styleOf.set(e,s)}return s};
    const shown=el=>{
      for(let e=el;e&&e!==svgEl;e=e.parentNode){
        const s=style(e);
        if(s.display==="none"||s.visibility==="hidden")return false;
      }
      return true;
    };
    const opacityOf=el=>{let o=1;for(let e=el;e&&e!==svgEl;e=e.parentNode)o*=parseFloat(style(e).opacity||"1");return o};
    const hit=(a,b,pad)=>a.x0<b.x1+pad&&a.x1+pad>b.x0&&a.y0<b.y1+pad&&a.y1+pad>b.y0;

    svgEl.querySelectorAll(".declutter-hidden").forEach(el=>el.classList.remove("declutter-hidden"));
    styleOf.clear();

    const fixed=[];
    svgEl.querySelectorAll(".similarity-box").forEach(el=>{if(shown(el))fixed.push(rel(el.getBoundingClientRect()))});
    // The legend, the zoom buttons and the hint lie over the graph, so a label keeps clear of
    // them.
    $("graphStage")?.querySelectorAll(".graph-legend,.graph-controls,.graph-help").forEach(el=>{
      const r=el.getBoundingClientRect();
      if(r.width&&r.height&&getComputedStyle(el).display!=="none")fixed.push(rel(r));
    });

    const circles=[],items=[],order=[];
    state.nodeSel.each(function(d){
      if(!Number.isFinite(d.x)||!Number.isFinite(d.y))return;
      const sx=d.x*k+t.x,sy=d.y*k+t.y,rs=Math.max(1,nodeRadius(d)*k);
      const visible=shown(this);
      const opacity=visible?opacityOf(this):0;
      const priority=labelPriority(this,d,opacity);
      order.push({g:this,priority});
      if(!visible)return;
      circles.push({id:d.id,x0:sx-rs,x1:sx+rs,y0:sy-rs,y1:sy+rs});
      const el=this.querySelector(":scope > text");
      if(!el||!el.textContent||!shown(el))return;
      const r=el.getBoundingClientRect();
      if(r.width<1||r.height<1)return;
      const c=rel(r);
      if(c.x1<0||c.x0>W||c.y1<0||c.y0>H)return;             // the label is outside the view
      items.push({kind:"node",d,el,sx,sy,rs,w:r.width/z,h:r.height/z,current:c,priority});
    });

    // Labels on edges: the similarity of an additional pair, the variable of a generated value.
    if(state.generatedLabelSel){
      state.generatedLabelSel.each(function(d){
        if(!shown(this)||!d.source||!d.target)return;
        const r=this.getBoundingClientRect();
        if(r.width<1||r.height<1)return;
        const c=rel(r);
        items.push({kind:"edge",d,el:this,w:r.width/z,h:r.height/z,current:c,priority:d.scoreLabel?2.5:4,
          cx0:(c.x0+c.x1)/2,cy0:(c.y0+c.y1)/2});
      });
    }

    // The direction of the edges of a node: its label goes the other way, where the edges leave
    // room, as the inward labels of a ring whose edges point outwards.
    const edgeDir=new Map();
    (state.graph?.links||[]).forEach(l=>{
      const s=l.source,e=l.target;
      if(!s||!e||typeof s!=="object"||typeof e!=="object")return;
      if(!Number.isFinite(s.x)||!Number.isFinite(e.x))return;
      const dx=e.x-s.x,dy=e.y-s.y,len=Math.hypot(dx,dy);
      if(len<1e-6)return;
      [[s.id,dx/len,dy/len],[e.id,-dx/len,-dy/len]].forEach(([id,ux,uy])=>{
        const v=edgeDir.get(id)||{x:0,y:0,n:0};
        v.x+=ux;v.y+=uy;v.n+=1;edgeDir.set(id,v);
      });
    });
    const gap=3;
    const spots=it=>{
      const {w,h}=it;
      if(it.kind==="edge"){
        const d=it.d,f0=Number.isFinite(d.labelT)?d.labelT:.5;
        const s=[d.source.x*k+t.x,d.source.y*k+t.y],e=[d.target.x*k+t.x,d.target.y*k+t.y];
        // the label sits 6 graph units above the point of the edge at f0 (tick)
        const dy=it.cy0-(s[1]+(e[1]-s[1])*f0);
        const at=f=>{const x=s[0]+(e[0]-s[0])*f,y=s[1]+(e[1]-s[1])*f+dy;return {x0:x-w/2,x1:x+w/2,y0:y-h/2,y1:y+h/2,f}};
        return [it.current,...[.5,.38,.62,.28,.72].filter(f=>Math.abs(f-f0)>.01).map(at)];
      }
      const {sx,sy,rs}=it,o=rs*.72;
      const right={x0:sx+rs+gap,x1:sx+rs+gap+w,y0:sy-h/2,y1:sy+h/2,side:"right"};
      const left={x0:sx-rs-gap-w,x1:sx-rs-gap,y0:sy-h/2,y1:sy+h/2,side:"left"};
      const below={x0:sx-w/2,x1:sx+w/2,y0:sy+rs+1,y1:sy+rs+1+h,side:"below"};
      const above={x0:sx-w/2,x1:sx+w/2,y0:sy-rs-1-h,y1:sy-rs-1,side:"above"};
      const ru={x0:sx+o+gap,x1:sx+o+gap+w,y0:sy-o-h,y1:sy-o,side:"right-up"};
      const rd={x0:sx+o+gap,x1:sx+o+gap+w,y0:sy+o,y1:sy+o+h,side:"right-down"};
      const lu={x0:sx-o-gap-w,x1:sx-o-gap,y0:sy-o-h,y1:sy-o,side:"left-up"};
      const ld={x0:sx-o-gap-w,x1:sx-o-gap,y0:sy+o,y1:sy+o+h,side:"left-down"};
      const onLeft=(it.current.x0+it.current.x1)/2<sx-1;
      const sides=[...(onLeft?[left,lu,ld,right,ru,rd]:[right,ru,rd,left,lu,ld]),below,above];
      // A label that an earlier pass moved is placed again at the same side, measured at the
      // present zoom: a pass during a zoom transition used another scale for the gap.
      const moved=it.d.labelSideChosen&&sides.find(x=>x.side===it.d.labelSideChosen);
      const options=moved?[moved,...sides.filter(x=>x!==moved)]:[it.current,...sides];
      const v=edgeDir.get(it.d.id);
      const pull=v?Math.hypot(v.x,v.y)/v.n:0;              // 1 when every edge leaves the same way
      if(pull<.35)return options;
      const ex=v.x/v.n/pull,ey=v.y/v.n/pull;
      const away=s=>{
        const cx=(s.x0+s.x1)/2-sx,cy=(s.y0+s.y1)/2-sy,l=Math.hypot(cx,cy)||1;
        return -(cx*ex+cy*ey)/l*pull+(s===options[0]?.35:0);
      };
      return options.map((s,i)=>({s,score:away(s)-i*.001})).sort((a,b)=>b.score-a.score).map(x=>x.s);
    };

    items.sort((a,b)=>a.priority-b.priority||(a.kind===b.kind?0:a.kind==="node"?-1:1));
    const placed=[];
    const inside=s=>s.x0>=1&&s.x1<=W-1&&s.y0>=1&&s.y1<=H-1;
    const free=(it,s,withCircles)=>{
      if(placed.some(p=>hit(s,p,3)))return false;
      if(fixed.some(p=>hit(s,p,2)))return false;
      if(withCircles&&circles.some(c=>(it.kind!=="node"||c.id!==it.d.id)&&hit(s,c,1)))return false;
      return true;
    };
    const covers=(it,s)=>circles.filter(c=>(it.kind!=="node"||c.id!==it.d.id)&&hit(s,c,1)).length;
    // A label smaller than this on screen cannot be read and only adds to the density; it
    // appears when a zoom makes it large enough, or when its node is hovered. In a crowded view
    // this holds for the labels of the results too.
    const MIN_TEXT_HEIGHT=9;
    const crowded=items.filter(it=>it.kind==="node").length>80;
    items.forEach(it=>{
      if(it.kind==="node"&&it.h<MIN_TEXT_HEIGHT&&(it.priority>=2||(crowded&&it.priority>=1))){
        it.el.classList.add("declutter-hidden");
        return;
      }
      const options=spots(it);
      const wasInside=inside(it.current);
      let chosen=options.find(s=>(!wasInside||inside(s))&&free(it,s,true));
      // Where every spot covers a node, the label of a result or of a mapped name takes the spot
      // that covers the fewest, and any other label waits for the hover or a zoom.
      if(!chosen&&it.priority<2.5){
        const open=options.filter(s=>(!wasInside||inside(s))&&free(it,s,false));
        chosen=open.reduce((best,s)=>!best||covers(it,s)<covers(it,best)?s:best,null);
      }
      if(!chosen){
        it.el.classList.add("declutter-hidden");
        return;
      }
      placed.push(chosen);
      if(chosen===it.current)return;
      if(it.kind==="node"&&chosen.side===it.d.labelSideChosen&&Math.abs(chosen.x0-it.current.x0)<.5&&Math.abs(chosen.y0-it.current.y0)<.5)return;
      if(it.kind==="edge"){
        it.d.labelT=chosen.f;
        const f=chosen.f,d=it.d;
        it.el.setAttribute("x",d.source.x+(d.target.x-d.source.x)*f);
        it.el.setAttribute("y",d.source.y+(d.target.y-d.source.y)*f-6);
        return;
      }
      // Back to the coordinates of the node, in which the text is drawn.
      it.el.setAttribute("text-anchor","start");
      it.el.removeAttribute("dx");
      it.el.setAttribute("x",(chosen.x0-it.sx)/k);
      it.el.setAttribute("y",((chosen.y0+chosen.y1)/2-it.sy)/k);
      it.el.setAttribute("dy","0.35em");
      it.d.labelSideChosen=chosen.side;
    });

    // The nodes of the labels placed first are drawn last.
    const layer=order[0]?.g.parentNode;
    if(layer){
      const sorted=[...order].sort((a,b)=>b.priority-a.priority);
      if(sorted.some((x,i)=>x.g!==order[i].g))sorted.forEach(x=>layer.appendChild(x.g));
    }
  }

  // In an overview the circles of the leaves surround the structural nodes, so a node with a
  // label is drawn after every node without one: its label then lies above the leaves.
  function raiseLabelledNodes(){
    const layer=state.nodeSel?.node()?.parentNode;
    if(!layer)return;
    const nodes=[...layer.children];
    const rank=g=>g.querySelector(":scope > text:not(.leaf-label), :scope > text.leaf-label.shown")?1:0;
    const ranked=nodes.map((g,i)=>({g,i,r:rank(g)}));
    const sorted=[...ranked].sort((a,b)=>a.r-b.r||a.i-b.i);
    if(sorted.some((x,i)=>x.g!==nodes[i]))sorted.forEach(x=>layer.appendChild(x.g));
  }

  function baseLinkClass(d,s,t){
    if(d.generatedAttributeLink){
      let cls=linkClass(d);
      if(state.focusMode && !state.resultIds.has(s) && !state.resultIds.has(t) && !state.contextIds.has(s) && !state.contextIds.has(t)){
        cls+=" faded";
      }
      return cls;
    }

    if(d.groundingLink)return d.additionalPair?"link grounding-link additional-pair":"link grounding-link";
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
  }

  // A clicked node keeps the focus on itself and its neighbours until the canvas is clicked. A
  // hovered or dragged node shows its neighbourhood the same way, more softly, except while query
  // results are lit, which then stay as they are. "Light all" keeps everything bright.
  function applyClasses(){
    const hover=INTERACTIVE?(state.dragId||state.hoverId):null;
    const focus=state.selectedId||hover;
    const near=new Set();
    if(focus){near.add(focus);neighborIds(new Set([focus])).forEach(x=>near.add(x))}
    const relationIds=new Set(state.inspectedLink?[state.inspectedLink.source,state.inspectedLink.target].map(value=>typeof value==="object"?value.id:value):[]);
    relationIds.forEach(id=>near.add(id));
    const dim=!state.lightAll;
    const hoverDims=dim&&!!hover&&!state.selectedId&&!state.inspectedLink&&!state.focusMode;
    state.nodeSel
      .classed("query-result",d=>state.resultIds.has(d.id))
      .classed("context",d=>state.contextIds.has(d.id)&&!state.resultIds.has(d.id))
      .classed("inspect",d=>state.selectedId===d.id||hover===d.id||relationIds.has(d.id))
      .classed("neighbor",d=>INTERACTIVE&&!!focus&&focus!==d.id&&near.has(d.id))
      .classed("faded",d=>{
        if(!dim)return false;
        if(state.focusMode)return !state.resultIds.has(d.id)&&!state.contextIds.has(d.id);
        if(state.selectedId)return !near.has(d.id);
        if(state.inspectedLink)return !near.has(d.id);
        return false;
      })
      .classed("dimmed",d=>hoverDims&&!near.has(d.id));
    // The labels make room for a new selection: the label of the selected node and of the ends of
    // an inspected relation are drawn whatever else, and its neighbours are placed before the
    // other labels (cullLabels, declutterLabels). A hover only highlights and moves no label.
    const labelKey=[state.selectedId||"",...[...relationIds].sort()].join("|");
    if(labelKey!==state.labelKey||state.labelGraph!==state.graph){
      state.labelKey=labelKey;state.labelGraph=state.graph;
      state.labelPinned=new Set(relationIds);
      if(state.selectedId)state.labelPinned.add(state.selectedId);
      state.labelNear=state.selectedId?neighborIds(new Set([state.selectedId])):new Set();
      scheduleLabelCull();
    }
    const ends=d=>[typeof d.source==="object"?d.source.id:d.source,typeof d.target==="object"?d.target.id:d.target];
    state.linkSel.attr("class",d=>{
      const [s,t]=ends(d);
      let cls=baseLinkClass(d,s,t);
      if(state.inspectedLink)cls+=state.inspectedLink===d?" inspect-link":" faded";
      if(!dim)cls=cls.replace(/ faded\b/g,"");
      if(INTERACTIVE&&focus&&(s===focus||t===focus))cls+=" focus-link";
      else if(hoverDims)cls+=" dimmed";
      return cls;
    });
    if(state.generatedLabelSel){
      state.generatedLabelSel.classed("dimmed",d=>{
        const [s,t]=ends(d);
        return hoverDims&&s!==focus&&t!==focus;
      });
    }
  }

  function resetGraph(){
    $("attributeResult").hidden=true;
    clearMessage();
    initGraph();
  }

  function updateStatus(){
    window.GENSPARQL_EXPLORER?.graphChanged();
    renderScoreLegend();
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
      const generated=state.graph.nodes.filter(n=>n.generatedCandidate).length;
      const grounded=state.graph.nodes.filter(n=>n.generatedCandidate&&n.grounded).length;

      $("graphTitle").textContent=generated?"Grounding Result":"Knowledge Graph";
      const similarityMethod=state.graph.nodes.find(n=>n.generatedCandidate)?.similarityMethod;
      if(PRINT_LAYOUT&&state.printFolded){
        const f=state.printFolded;
        $("graphTitle").textContent="Mapping of generated names";
        $("graphStatus").textContent=`${similarityLabel(similarityMethod)} · θ = ${f.theta.toFixed(2)} · ${f.total} generated names · ${f.names.length} equal a label and map (not drawn)`;
        const legend=document.querySelector(".legend");
        if(legend)legend.innerHTML='<span><i class="lg-kg"></i>entity of the graph</span><span><i class="lg-llm"></i>generated name</span><span><i class="lg-drop"></i>name below θ</span>';
        return;
      }
      const mappedTargets=countryNodes.filter(n=>n.groundedTarget).length;
      const pairs=state.graph.links.filter(l=>l.groundingLink).length;
      $("graphStatus").textContent=generated
        ? `${similarityStatus(similarityMethod)} · θ ${Number($("theta").value).toFixed(2)} · ${mappedTargets} KG targets mapped · ${pairs} pairs ≥ θ from ${grounded} of ${generated} generated names`
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
    // A live result is laid out small and sized for its own fit: the margins below, in graph
    // units, would leave it a fraction of the stage.
    if(state.graph.nodes.some(n=>n.liveQueryResult)){fitRelevantGraph();return}
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

  // The zoom that shows the nodes of a Run-result view: smaller padding and a higher maximum
  // zoom than the overview, because unrelated nodes have already been removed. The labels are
  // placed with this zoom in mind (resolveLiveLabelCollisions), so both read the same fit.
  // The fit takes in the circles and keeps a margin at the edges of the stage, so that the
  // outermost nodes lie wholly inside it.
  function liveFitTransform(nodes){
    const stage=$("graphStage");
    const w=stage?.clientWidth||760,h=stage?.clientHeight||570;
    const placed=(nodes||[]).filter(n=>Number.isFinite(n.x)&&Number.isFinite(n.y));
    if(!placed.length)return {scale:1,tx:0,ty:0,w,h,empty:true};
    const pad=3,margin=16;
    const minX=NativeGraph.min(placed,d=>d.x-nodeRadius(d))-pad;
    const maxX=NativeGraph.max(placed,d=>d.x+nodeRadius(d))+pad;
    const minY=NativeGraph.min(placed,d=>d.y-nodeRadius(d))-pad;
    const maxY=NativeGraph.max(placed,d=>d.y+nodeRadius(d))+pad;
    // Circles and labels keep their size on screen at any zoom (resolveLiveLabelCollisions), so a
    // small result is zoomed until it fills the stage. The least box keeps a single node from a
    // zoom without end.
    const bw=Math.max(maxX-minX,70);
    const bh=Math.max(maxY-minY,70);
    // The hint bar covers the bottom of the stage, as in fitGraphWithLabels.
    const bottom=40;
    const scale=Math.max(.28,.995/Math.max(bw/Math.max(1,w-2*margin),bh/Math.max(1,h-margin-bottom)));
    return {scale,tx:w/2-scale*(minX+maxX)/2,ty:(margin+h-bottom)/2-scale*(minY+maxY)/2,w,h,empty:false};
  }

  // The region of the graph that the fit shows, in graph units.
  function liveViewBox(fit){
    return {x:-fit.tx/fit.scale,y:-fit.ty/fit.scale,w:fit.w/fit.scale,h:fit.h/fit.scale};
  }

  // One screen pixel of the live view in graph units.
  function livePx(){
    return 1/(state.liveLabelScale||1);
  }

  // The zoom that shows every node of a composition stage together with its label. The labels
  // of the outer nodes reach beyond their nodes, so a fit of the nodes alone cut them off at
  // the edges of the stage; the hint bar covers the bottom of the stage.
  function fitGraphWithLabels(){
    if(!state.nodeSel)return;
    let x0=Infinity,y0=Infinity,x1=-Infinity,y1=-Infinity;
    const add=(a,b,c,d)=>{x0=Math.min(x0,a);y0=Math.min(y0,b);x1=Math.max(x1,c);y1=Math.max(y1,d)};
    state.nodeSel.each(function(d){
      if(!Number.isFinite(d.x)||!Number.isFinite(d.y))return;
      const r=nodeRadius(d);
      add(d.x-r,d.y-r,d.x+r,d.y+r);
      const t=this.querySelector(":scope > text");
      if(!t||getComputedStyle(t).display==="none")return;
      try{
        const b=t.getBBox();
        if(b.width)add(d.x+b.x,d.y+b.y,d.x+b.x+b.width,d.y+b.y+b.height);
      }catch(_){}
    });
    if(!Number.isFinite(x0))return;
    const stage=$("graphStage"),w=stage?.clientWidth||760,h=stage?.clientHeight||570;
    const padX=16,padTop=16,padBottom=46;
    const bw=Math.max(x1-x0,70),bh=Math.max(y1-y0,70);
    const scale=Math.max(.28,Math.min(6.2,(w-2*padX)/bw,(h-padTop-padBottom)/bh));
    const tx=w/2-scale*(x0+x1)/2;
    const ty=(padTop+h-padBottom)/2-scale*(y0+y1)/2;
    NativeGraph.select("#kgGraph")
      .transition()
      .duration(420)
      .call(state.zoom.transform,NativeGraph.zoomIdentity.translate(tx,ty).scale(scale));
  }

  function fitRelevantGraph(){
    const fit=liveFitTransform(state.graph.nodes);
    if(fit.empty)return;
    // The zoom reaches four times beyond the fit either way: the fit of a small result lies above
    // the limit of 6 of the other views.
    state.zoom.scaleExtent([Math.min(.12,fit.scale/4),Math.max(6,fit.scale*4)]);
    NativeGraph.select("#kgGraph")
      .transition()
      .duration(420)
      .call(state.zoom.transform,NativeGraph.zoomIdentity.translate(fit.tx,fit.ty).scale(fit.scale));
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
    const pad=PRINT_LAYOUT?8:42;
    const padY=PRINT_LAYOUT?18:pad;
    // In the figure layout a name is drawn left of its node and a label right of its node,
    // so the fit makes room for the text, measured in the font of the page at 20 px.
    const ctx=PRINT_LAYOUT?document.createElement("canvas").getContext("2d"):null;
    const family=getComputedStyle(document.body).fontFamily;
    const textWidth=d=>{
      if(!ctx)return 0;
      ctx.font=`${d.generatedCandidate?"700 ":""}20px ${family}`;
      return ctx.measureText(String(d.label||"")).width+nodeRadius(d)+10;
    };
    const left=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxX)
      ? Math.min(d.x-textWidth(d),d.x+d.similarityBoxX)
      : d.x;
    const right=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxX)
      ? Math.max(d.x,d.x+d.similarityBoxX+SIMILARITY_BOX_WIDTH)
      : d.x+textWidth(d);
    const top=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxY)
      ? Math.min(d.y,d.y+d.similarityBoxY)
      : d.y;
    const bottom=d=>d.generatedCandidate&&Number.isFinite(d.similarityBoxY)
      ? Math.max(d.y,d.y+d.similarityBoxY+SIMILARITY_BOX_HEIGHT)
      : d.y;

    const minX=NativeGraph.min(nodes,left)-pad;
    const maxX=NativeGraph.max(nodes,right)+pad;
    const minY=NativeGraph.min(nodes,top)-padY;
    const maxY=NativeGraph.max(nodes,bottom)+padY;

    const bw=Math.max(maxX-minX,160);
    const bh=Math.max(maxY-minY,160);
    const fill=PRINT_LAYOUT?.98:.86;
    const scale=Math.max(.14,Math.min(2.85,fill/Math.max(bw/w,bh/h)));
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
  // The measure of the engine (JaccardSimText): the Jaccard coefficient of the two lower-cased
  // word sets, so strings that differ only in case score 1. The page
  // used to compare character trigrams, so it scored pairs the engine scores 0 (Czech Republic
  // and Czechia: 0.38 here, 0 in the engine).
  function words(s){return new Set(String(s).toLowerCase().trim().split(/\s+/))}
  function jac(a,b){a=String(a??"");b=String(b??"");if(a===b)return 1;const A=words(a),B=words(b),U=new Set([...A,...B]);if(!U.size)return 0;let k=0;A.forEach(x=>{if(B.has(x))k++});return k/U.size}

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
      // The similarity to every entity is kept, because the similarity join pairs a name
      // with every entity whose similarity reaches θ.
      let best=null,score=0;
      const scores=new Map();
      teams.forEach(team=>{
        const aliases=[team.label,...(team.aliases||[])];
        const candidateScore=Math.max(...aliases.map(alias=>jac(name,alias)));
        scores.set(team.id,candidateScore);
        if(candidateScore>score){
          score=candidateScore;
          best=team;
        }
      });
      return {name,best,score,scores};
    });
  }

  // The recorded mode has no embedding endpoint. For the names of the recorded run it uses the
  // bge-large-en-v1.5 similarity of each name to each team, which eval/www27/
  // embedding_scores.py computed as the similarity join of the engine does.
  // The recorded entity scenario of the database: the team names or the Nobel field names.
  function recordedEntityRun(){
    const key=state.dbKey==="fifa2026"?"wcEntity":state.dbKey==="nobelPrize"?"nobelEntity":null;
    return key?window.RECORDED_RUNS?.[key]:null;
  }

  function recordedEmbeddingMatches(){
    const rec=recordedEntityRun()?.embedding;
    const names=currentScenario().generated||[];
    if(!rec||!names.length||!names.every(n=>rec.best?.[n]))return null;
    const nodes=new Map(currentDb().graph.nodes.map(n=>[n.id,n]));
    return names.map(name=>{
      const best=rec.best[name];
      const row=rec.scores?.[name];
      const scores=new Map(row?rec.teams.map((team,i)=>[team,row[i]]):[[best.team,best.score]]);
      return {name,best:nodes.get(best.team)||null,score:scores.get(best.team)??best.score,scores};
    });
  }

  function recordedEmbeddingModel(){
    return String(recordedEntityRun()?.embedding?.model||"").replace(/^.*\//,"");
  }

  // The similarity named in the status line, with the model of recorded embedding scores.
  function similarityStatus(method){
    const recorded=state.recordedEmbedding&&String(method||"").toLowerCase()==="embedding";
    return similarityLabel(method)+(recorded?` (${state.recordedEmbedding}, recorded scores)`:"");
  }

  // The key of the embedding endpoint: its own field, or the chat key when that is empty.
  function embeddingKey(){
    return ($("embKey")?.value||"").trim()||($("key")?.value||"").trim();
  }

  async function embeddingGroundingMatches(){
    const key=embeddingKey();
    if(!key){
      const recorded=recordedEmbeddingMatches();
      if(!recorded)throw new Error("Recorded embedding scores exist only for the names of the two built-in graphs. Use Jaccard, or enter an API key and an embedding endpoint in the local workbench.");
      state.recordedEmbedding=recordedEmbeddingModel();
      return recorded;
    }
    state.recordedEmbedding=null;

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
      const scores=new Map();
      teams.forEach((team,j)=>{
        const v=cosine(nameVectors[i],teamVectors[j]);
        scores.set(team.id,v);
        if(v>score){score=v;best=team}
      });
      return {name,best,score,scores};
    });
  }

  async function embedForGrounding(texts){
    const base=$("embBase").value.replace(/\/$/,"");
    const model=$("embModel").value.trim();
    const key=embeddingKey();

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

  // θ only decides which of the computed pairs the view draws, so a new θ redraws the mapping
  // from the similarities of the last Run, without computing them again.
  function updateGroundingThresholdOnly(){
    if(currentScenario()?.id!=="entity"||!state.graph)return;
    const cache=state.groundingMatches;
    if(!cache||cache.db!==state.dbKey||!state.graph.nodes.some(n=>n.generatedCandidate))return;

    renderGroundingView(cache.matches,cache.method,{preserveViewport:true});

    const theta=Number($("theta").value);
    const candidates=state.graph.nodes.filter(n=>n.generatedCandidate);
    const grounded=candidates.filter(n=>n.grounded).length;
    showMessage(
      `${similarityStatus(cache.method)} · θ ${theta.toFixed(2)} · ${grounded} names mapped · ${candidates.length-grounded} below θ.`,
      "ok"
    );
  }

  async function buildGroundEntityView(options={}){
    let matches;
    let method;
    const view=state.view;
    if($("groundEmbedding").checked){
      matches=await embeddingGroundingMatches();
      if(view!==state.view)return;
      method="Embedding";
    }else{
      matches=textGroundingMatches();
      method="Text";
      state.recordedEmbedding=null;
    }
    state.groundingMatches={db:state.dbKey,method,matches};
    renderGroundingView(matches,method,options);
  }

  // The similarity join keeps every pair of a generated name and an entity whose similarity
  // reaches θ, so the view draws an edge for each such pair. A name without one keeps a dotted
  // edge to its closest entity, whose similarity its box shows, so the viewer sees why it fails.
  function renderGroundingView(allMatches,method,options={}){
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

    let matches=allMatches;

    // In the figure layout, the names that equal a label are listed in the status line
    // instead of drawn, so that the figure shows the names the similarity join decides on.
    state.printFolded=null;
    if(PRINT_LAYOUT){
      const folded=matches.filter(m=>m.best&&m.score>=1).map(m=>m.name);
      state.printFolded={names:folded,total:matches.length,theta:Number($("theta").value)};
      matches=matches.filter(m=>!(m.best&&m.score>=1));
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
      const pairs=[...(match.scores||[])].filter(([,score])=>score>=theta).sort((x,y)=>y[1]-x[1]);
      const grounded=pairs.length>0;
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

      // A candidate with no best match keeps its node and its score, and has no relation to
      // draw below θ.
      if(match.best)potentialTargets.add(match.best.id);
      if(grounded){
        pairs.forEach(([targetId,score])=>{
          groundedTargets.add(targetId);
          matchLinks.push({
            source:id,
            target:targetId,
            predicate:"SIMJOIN",
            groundingLink:true,
            potentialGroundingLink:false,
            score,
            additionalPair:targetId!==match.best?.id
          });
        });
      }else if(match.best){
        matchLinks.push({
          source:id,
          target:match.best.id,
          predicate:"best match",
          groundingLink:false,
          potentialGroundingLink:true,
          score:match.score
        });
      }
    });

    // The box of a name shows its best similarity. The other pairs carry their similarity on
    // the edge while the edges are few enough to read.
    if(matchLinks.length<=60){
      matchLinks.forEach(link=>{if(link.additionalPair)link.scoreLabel=link.score.toFixed(2)});
    }

    // Keep the existing grounding result intact, then add every remaining
    // country as unlinked KG context on a separate outer ring.
    const relevantTargetIds=new Set([
      ...generatedNodes.map(n=>n.bestId).filter(Boolean),
      ...groundedTargets
    ]);
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
    //   below-threshold      -> farther out
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
    // A target that only an additional pair reaches sits right after the closest entity of
    // the first name that reaches it, so that the edge stays short.
    const candidateById=new Map(generatedNodes.map(candidate=>[candidate.id,candidate]));
    const insertedAfter=new Map();
    matchLinks.forEach(link=>{
      if(!link.groundingLink||targetSeen.has(link.target))return;
      const anchor=candidateLayoutKey(candidateById.get(link.source));
      const count=insertedAfter.get(anchor)||0;
      targetOrder.splice(targetOrder.indexOf(anchor)+1+count,0,link.target);
      targetSeen.add(link.target);
      insertedAfter.set(anchor,count+1);
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
    // In a stage wider than high, the rings widen into ellipses, up to half as wide again: the
    // labels run across, so the names and score boxes at the top and at the bottom of the rings
    // take the room of the width. The figure layout keeps its columns.
    const stretch=PRINT_LAYOUT?1:Math.min(1.5,Math.max(1,width/height));
    // The direction from the inner ring to the outer rings at the angle of the rings.
    const outwards=angle=>Math.atan2(Math.sin(angle),stretch*Math.cos(angle));

    const groupedCandidates=new Map();
    generatedNodes.forEach(candidate=>{
      const key=candidateLayoutKey(candidate);
      if(!groupedCandidates.has(key))groupedCandidates.set(key,[]);
      groupedCandidates.get(key).push(candidate);
    });

    // Each entity takes an arc of the ring in proportion to the names whose closest entity it
    // is, and its names spread over that arc, so that several names close to one entity, such
    // as three names near New Zealand, have room for their score boxes.
    const weightOf=id=>Math.max(1,groupedCandidates.get(id)?.length||0);
    const totalWeight=targetOrder.reduce((sum,id)=>sum+weightOf(id),0)||1;
    const targetAngle=new Map(),targetSpan=new Map();
    let arcStart=-Math.PI/2-Math.PI*weightOf(targetOrder[0])/totalWeight;
    targetOrder.forEach(id=>{
      const span=2*Math.PI*weightOf(id)/totalWeight;
      const angle=arcStart+span/2;
      arcStart+=span;
      targetAngle.set(id,angle);
      targetSpan.set(id,span);

      const country=relevantCountries.find(n=>n.id===id);
      if(country){
        const x=cx+Math.cos(angle)*innerRadius*stretch;
        const y=cy+Math.sin(angle)*innerRadius;
        country.x=x;country.y=y;
        country.fx=x;country.fy=y;
      }
    });

    groupedCandidates.forEach((candidates,targetId)=>{
      candidates.sort((a,b)=>a.label.localeCompare(b.label));
      const base=targetAngle.has(targetId)
        ? targetAngle.get(targetId)
        : -Math.PI/2;
      const step=(targetSpan.get(targetId)||0)/candidates.length;

      candidates.forEach((candidate,i)=>{
        const centered=i-(candidates.length-1)/2;

        // The names of an entity share its arc; a failing name lies farther out.
        const angle=base+centered*step;
        const radius=candidate.grounded?groundedRadius:potentialRadius;

        const x=cx+Math.cos(angle)*radius*stretch;
        const y=cy+Math.sin(angle)*radius;

        candidate.x=x;candidate.y=y;
        candidate.fx=x;candidate.fy=y;
        candidate.radialAngle=outwards(angle);
        candidate.labelSide=Math.cos(angle)<-.12?"left":"right";
      });
    });

    // Place similarity boxes after the radial node layout. A deterministic
    // collision pass offsets nearby boxes tangentially/radially as needed.
    layoutSimilarityBoxes(generatedNodes,cx,cy);

    if(PRINT_LAYOUT)layoutGroundingColumns(generatedNodes,relevantCountries,cx,cy,matchLinks);

    // Remaining KG countries form a stable, unlinked halo beyond the original
    // result. The initial camera still fits only the original result nodes.
    const outerCountryRadius=Math.max(
      potentialRadius+Math.min(width,height)*.15,
      Math.min(width,height)*.60
    );
    remainingCountries.forEach((country,i)=>{
      const angle=-Math.PI/2+Math.PI/Math.max(remainingCountries.length,1)
        +(2*Math.PI*i/Math.max(remainingCountries.length,1));
      const x=cx+Math.cos(angle)*outerCountryRadius*stretch;
      const y=cy+Math.sin(angle)*outerCountryRadius;
      country.x=x;country.y=y;
      country.fx=x;country.fy=y;
      country.radialAngle=outwards(angle);
      country.labelSide=Math.cos(angle)<0?"left":"right";
    });

    state.resultIds.clear();
    state.contextIds.clear();
    state.focusMode=false;
    state.selectedId=null;

    rerenderCurrentGraphWithoutReset();
    // Beyond the closest entity of each name, a low θ adds a pair for nearly every entity; those
    // pairs are drawn thin and light once there are many of them (crowded-pairs, styles.css).
    $("kgGraph").classList.toggle("crowded-pairs",matchLinks.length>60);

    if(preserveViewport&&previousTransform){
      // Keep exactly the user's current zoom / pan.
      NativeGraph.select("#kgGraph").call(state.zoom.transform,previousTransform);

      // Threshold-dependent radial positions are already fixed in fx/fy; keep viewport unchanged.
      // Stopped before its first tick, the simulation would leave every node at (0,0).
      state.simulation.alpha(0).stop();
      tick();
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
    // The step that selects the bindings: KG patterns, or a FILTER as for the Nobel awards.
    const select={title:s.selectionTitle||"KG selection",detail:selection,kind:s.selectionTitle==="FILTER"?"filter":"kg"};
    const generated=s.generatedVariable||"?pos";
    const filter=s.filterLabel||'?pos = "Defender"';
    const matches=result.matches??result.defenders;
    const genopFirstSteps=[
          {title:bindings,detail:`${cost.genopFirst} bindings`,kind:"kg"},
          {title:"GENOP",detail:`${cost.genopFirst} LLM calls`,kind:"genop"},
          select,
        ];
    const plannerSteps=[
          select,
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
    const steps=order.querySelectorAll(".execution-step");
    steps.forEach((node,i)=>{
      node.classList.toggle("is-active",i===index);
      node.classList.toggle("is-complete",i<index);
      node.setAttribute("aria-current",i===index?"step":"false");
    });
    const fill=$("playbackFill");
    if(fill)fill.style.width=steps.length>1?`${Math.max(0,index)/(steps.length-1)*100}%`:"100%";
  }

  // Play steps shows the steps of the last strategy one after the other at a pace for reading,
  // with the line under the steps as its progress, as the playback of the time brush of the
  // T-MIGRANTS explorer. It starts again from Step 1 after the last step.
  const PLAYBACK_STEP_MS=1600;
  function stopPlayback(){
    clearTimeout(state.playbackTimer);state.playbackTimer=0;
    const button=$("playSteps");
    if(button){button.setAttribute("aria-pressed","false");button.textContent="▶ Play steps";}
  }
  function togglePlayback(){
    if(state.playbackTimer){stopPlayback();return;}
    const strategy=state.compositionStrategy;
    if(!strategy)return;
    const show=state.dbKey==="nobelPrize"?showNobelCompositionStep:showCompositionStep;
    const last=compositionSteps(strategy).length-1,view=state.view;
    let index=state.compositionStep==null||state.compositionStep>=last?0:state.compositionStep+1;
    $("playSteps").setAttribute("aria-pressed","true");$("playSteps").textContent="❚❚ Pause";
    const next=()=>{
      if(view!==state.view){stopPlayback();return;}
      show(strategy,index);
      if(index++>=last){stopPlayback();return;}
      state.playbackTimer=setTimeout(next,PLAYBACK_STEP_MS);
    };
    state.playbackTimer=setTimeout(next,0);
  }

  // A change of database or scenario starts a new view. A run that is still stepping or waiting
  // for an answer when the view changes stops instead of drawing over the new view, as the
  // T-MIGRANTS explorer drops an answer that belongs to an earlier request.
  function newView(){
    stopPlayback();
    return ++state.view;
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
      button.onclick=()=>{stopPlayback();showCompositionStep(strategy,Number(button.dataset.compositionStep))};
    });

    const bindings=(s.bindingLabel||"athlete bindings").toLowerCase();
    $("executionExplanation").textContent=genopFirst
      ? `GENOP runs before ${s.selective||"the selective KG patterns"}, so the LLM is invoked for all ${cost.genopFirst} ${bindings} before later operators discard irrelevant rows. Click any execution step to inspect the execution order.`
      : `The planner evaluates ${s.selective||"the selective KG patterns"} first and defers GENOP until only ${cost.planned} bindings remain. This reduces LLM calls from ${cost.genopFirst} to ${cost.planned} (${cost.factor} fewer) while preserving the recorded logical query result.`;
  }

  // The positions that the recorded model generated for the players in the evaluation (E1,
  // with the prompt of the composed query), keyed by IRI. They stay outside the graph, which
  // leaves positions out.
  function recordedPositions(){
    const codes={Goalkeeper:"GK",Defender:"DF",Midfielder:"MF",Forward:"FW"};
    const byQid=window.RECORDED_RUNS?.wcComposition?.positions||{};
    const out={};
    Object.entries(byQid).forEach(([q,label])=>{
      out[`http://www.wikidata.org/entity/${q}`]={code:codes[label]||"?",label};
    });
    return out;
  }

  function compositionData(){
    const source=cloneGraph(currentDb().graph);
    const nodeById=new Map(source.nodes.map(node=>[node.id,node]));
    const positionById=recordedPositions();
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

    // The defenders are the players that the recorded run of the composed query kept (E3).
    // The positions on the generated nodes are the answers of the same model to the same
    // prompt in E1, which keep the same 80 defenders of Group A.
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
      fitGraphWithLabels();
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
    stopPlayback();
    const view=state.view;
    const {s,result,cost}=compositionContext();
    const btnA=$("runGenopFirst");
    const btnB=$("runPlanner");
    btnA.disabled=true;
    btnB.disabled=true;
    $("playSteps").disabled=true;

    const isPlanner=strategy==="planner";
    const active=isPlanner?btnB:btnA;
    const original=active.querySelector("strong").textContent;
    active.querySelector("strong").textContent="Running…";

    try{
      renderExecutionResult(strategy);
      const steps=compositionSteps(strategy);
      for(let i=0;i<steps.length;i++){
        if(view!==state.view)return;
        showCompositionStep(strategy,i);
        // Let each physical operator stage remain visible long enough to read.
        if(i<steps.length-1)await new Promise(resolve=>setTimeout(resolve,520));
      }
      if(view!==state.view)return;

      const calls=isPlanner?cost.planned:cost.genopFirst;
      const order=isPlanner
        ? s.orders?.planner||"KG patterns → GENOP → FILTER"
        : s.orders?.genopFirst||"GENOP → KG patterns → FILTER";

      showMessage(
        `${isPlanner?"Planner":"GENOP-first"} completed · ${calls} LLM calls · ${order} · recorded result ${result.defenders}/${result.total}. Click any Step above to replay that intermediate KG state.`,
        "ok"
      );
    }finally{
      active.querySelector("strong").textContent=original;
      btnA.disabled=false;
      btnB.disabled=false;
      $("playSteps").disabled=false;
    }
  }

  function nobelCompositionData(){
    const {s}=compositionContext();
    const full=currentDb().graph,byId=new Map(full.nodes.map(node=>[node.id,node]));
    const physicsId="http://www.wikidata.org/entity/Q38104",sinceYear=s.sinceYear||2020;
    const awardIds=new Set(full.links.filter(link=>link.predicate==="field"&&link.target===physicsId&&nobelAwardYear({id:link.source})>=sinceYear).map(link=>link.source));
    const selectedIds=[...new Set(full.links.filter(link=>link.predicate==="winner"&&awardIds.has(link.source)).map(link=>link.target))];
    // Without the reordering, the FILTER on the year runs after GENOP, so GENOP sees the
    // winner of every Physics award.
    const physicsAwards=new Set(full.links.filter(link=>link.predicate==="field"&&link.target===physicsId).map(link=>link.source));
    const allIds=[...new Set(full.links.filter(link=>link.predicate==="winner"&&physicsAwards.has(link.source)).map(link=>link.target))];
    const answered=new Set((s.result?.answeredNames||[]).map(name=>norm(name)));
    return {s,full,byId,physicsId,sinceYear,selectedIds,allIds,answered};
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
      if(selected&&data.answered.size&&!data.answered.has(norm(source.label)))return;
      const angle=dense?stableUnit(source.id,331)*Math.PI*2:-Math.PI/2+index*Math.PI*2/Math.max(sources.length,1);
      // Far enough from its laureate that the two circles and the edge label stay apart.
      const distance=dense?3.4:30;
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
      if(safeIndex===0){title="Step 1 · Physics award bindings";status=`${cost.genopFirst} Physics award bindings before GENOP`;}
      else if(safeIndex===1){withGenerated=true;title="Step 2 · GENOP";status=`${cost.genopFirst} LLM calls, one per binding`;}
      else if(safeIndex===2){selected=true;withGenerated=true;title=`Step 3 · ${s.selectionTitle||"KG selection"}`;status=`The FILTER on the award year keeps ${cost.planned} of ${cost.genopFirst} Physics award bindings · ${result.matches} generated contributions`;}
      else {selected=true;withGenerated=true;title="Step 4 · Result";status=`${cost.genopFirst} LLM calls · ${result.matches} generated contributions`;}
    }else{
      selected=true;
      if(safeIndex===0){title=`Step 1 · ${s.selectionTitle||"KG selection"}`;status=`The FILTER on the award year keeps ${cost.planned} of ${cost.genopFirst} Physics award bindings before GENOP`;}
      else {withGenerated=true;title=safeIndex===1?"Step 2 · GENOP":"Step 3 · Result";status=`${cost.planned} LLM calls · ${result.matches} generated contributions`;}
    }
    const graph=buildNobelCompositionStage({selected,withGenerated});
    const finalStep=safeIndex===steps.length-1;
    const resultIds=finalStep?[...graph.sources.map(node=>node.id),...graph.generated.map(node=>node.id)]:graph.sources.map(node=>node.id);
    applyCompositionStageGraph(graph,{title,status},{resultIds,contextIds:graph.field?[graph.field.id]:[]});
  }

  async function executeNobelCompositionStrategy(strategy){
    clearMessage();
    stopPlayback();
    const view=state.view;
    const {s,result,cost}=compositionContext(),btnA=$("runGenopFirst"),btnB=$("runPlanner");
    const active=strategy==="planner"?btnB:btnA,original=active.querySelector("strong").textContent;
    btnA.disabled=true;btnB.disabled=true;$("playSteps").disabled=true;active.querySelector("strong").textContent="Running…";
    try{
      renderExecutionResult(strategy);
      $("executionOrder").querySelectorAll("[data-composition-step]").forEach(button=>button.onclick=()=>{stopPlayback();showNobelCompositionStep(strategy,Number(button.dataset.compositionStep))});
      const steps=compositionSteps(strategy);
      for(let index=0;index<steps.length;index++){
        if(view!==state.view)return;
        showNobelCompositionStep(strategy,index);
        if(index<steps.length-1)await new Promise(resolve=>setTimeout(resolve,620));
      }
      if(view!==state.view)return;
      const calls=strategy==="planner"?cost.planned:cost.genopFirst,matches=result.matches??result.defenders;
      showMessage(`${strategy==="planner"?"Planner-first":"GENOP-first"} completed · ${calls} LLM calls · ${matches}/${result.total} ${s.resultLabel}. Click a Step to replay its intermediate KG.`,"ok");
    }finally{
      active.querySelector("strong").textContent=original;btnA.disabled=false;btnB.disabled=false;$("playSteps").disabled=false;
    }
  }


  function renderAttributeResult(rows,stat,model){
    $("attributeResult").hidden=false;
    requestAnimationFrame(()=>revealInColumn($("attributeResult")));
    $("attributeAnswerRate").textContent=stat?.a||"—";
    $("attributeAccuracy").textContent=stat?.b||"—";

    const counts={correct:0,wrong:0,abstain:0,unscored:0};
    rows.forEach(r=>{if(counts[r.state]!==undefined)counts[r.state]++});

    // An entity without a value in Wikidata is not scored, so the counts add up to the rows.
    $("attributeSampleSummary").textContent=
      `Displayed sample · ${counts.correct} correct · ${counts.wrong} wrong · ${counts.abstain} abstain`
      +(counts.unscored?` · ${counts.unscored} unscored (no value in Wikidata)`:"");

    // Under each name, the answers of the other recorded LLMs to the same prompt.
    const others=r=>(r.others||[]).length
      ? `<span class="attribute-others">${r.others.map(o=>`${esc(o.model)}: ${esc(o.gen)} (${esc(o.state)})`).join(" · ")}</span>`
      : "";
    // A header names the LLM of each column: the recorded model in the middle, the other
    // recorded LLMs under each name, and the judgement against Wikidata on the right.
    const otherModels=[...new Set(rows.flatMap(r=>(r.others||[]).map(o=>o.model)))];
    const head=model
      ? `<div class="attribute-row attribute-head"><span>Name${otherModels.length?` · below: ${esc(otherModels.join(", "))}`:""}</span><span>${esc(String(model).replace(/-A3B-Instruct-2507$|-Instruct-v0\.3$/,""))}</span><span>vs Wikidata</span></div>`
      : "";
    $("attributeRows").innerHTML=head+rows.map(r=>`
      <div class="attribute-row">
        <span class="attribute-player">${esc(r.name)}${others(r)}</span>
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

    // Two-sided fan: the KG entities form two columns, half on each side of the centre, with
    // their names facing the centre; each generated value lies further out on an arc, on the
    // row of its entity, with its label facing outwards. In two columns the names of the
    // entities stay apart.
    const stage=$("graphStage");
    const width=stage.clientWidth||760;
    const height=stage.clientHeight||570;
    const cx=width/2;
    const cy=height/2;
    const count=Math.max(playerNodes.length,1);
    const rightCount=Math.ceil(count/2);
    const innerX=width*.135;
    const edgeX=width*.18;
    const spanY=height*.39;

    playerNodes.forEach((player,i)=>{
      const right=i<rightCount;
      const n=right?rightCount:count-rightCount;
      const j=right?i:i-rightCount;
      const t=n<=1?0:j/(n-1)*2-1;                        // -1 at the top, 1 at the bottom
      const side=right?1:-1;
      const py=cy+t*spanY;
      const px=cx+side*innerX;

      player.x=px;player.y=py;
      player.fx=px;player.fy=py;
      player.radialAngle=Math.atan2(py-cy,px-cx);
      player.labelSide=right?"left":"right";

      const position=generatedNodes.find(n=>n.sourceAthleteId===player.id);
      if(position){
        // The values lie on an arc: further out in the middle, closer at the top and bottom.
        const qy=py;
        const qx=cx+side*(innerX+edgeX*(1-.35*t*t));
        position.x=qx;position.y=qy;
        position.fx=qx;position.fy=qy;
        position.radialAngle=Math.atan2(qy-cy,qx-cx);
        position.labelSide=right?"right":"left";
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
    resetPointerFocus();

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
      .attr("class",d=>linkClass(d))
      // A link of a Run-result view keeps its stroke width on screen whatever the zoom of the fit.
      .attr("vector-effect",d=>Number.isFinite(d.liveQueryLinkDepth)||d.liveGeneratedLink?"non-scaling-stroke":null);

    state.generatedLabelSel=labelLayer
      .selectAll("text")
      .data(graphSnapshot.links.filter(d=>(d.generatedAttributeLink&&!d.hideGeneratedEdgeLabel)||d.scoreLabel))
      .join("text")
      .attr("class",d=>{
        let cls="generated-edge-label";
        if(d.scoreLabel)cls+=" grounding-score-label";
        if(d.compositionResultLink)cls+=" composition-generated-edge";
        if(d.evaluationState==="wrong")cls+=" wrong";
        if(d.evaluationState==="abstain")cls+=" abstain";
        return cls;
      })
      .text(d=>d.scoreLabel||d.predicate||"?generated")
      // The label of a generated attribute in a Run-result view keeps its size on screen.
      .style("font-size",d=>d.liveGeneratedLink?`${7.3*livePx()}px`:null,"important")
      .style("stroke-width",d=>d.liveGeneratedLink?`${2.2*livePx()}px`:null,"important");

    state.nodeSel=nodeLayer
      .selectAll("g")
      .data(graphSnapshot.nodes,d=>d.id)
      .join("g")
      .attr("class",d=>nodeClass(d))
      .attr("role","button").attr("tabindex",0)
      .attr("aria-label",d=>`Inspect ${d.label}`)
      .on("keydown",keyboardInspect);

    // The radius in --r caps the outline of the circle (styles.css).
    state.nodeSel.append("circle").attr("r",nodeRadius).style("--r",d=>`${nodeRadius(d)}px`);
    styleNodeCircles(state.nodeSel);

    const denseCompositionSnapshot=graphSnapshot.nodes.length>300&&graphSnapshot.nodes.some(node=>node.compositionDense);
    // The 1,248 bindings of GENOP-first are a cloud of dots under their group labels; a label
    // for each of them, even one hidden by the stylesheet, came back through a more specific rule.
    state.nodeSel.filter(node=>(!denseCompositionSnapshot||node.compositionGroup)&&!node.compositionBindingCloud).append("text")
      .attr("x",d=>nodeLabelX(d))
      .attr("text-anchor",d=>nodeLabelAnchor(d))
      .attr("y",nodeLabelY)
      .style("font-size",d=>Number.isFinite(d.liveLabelFont)?`${d.liveLabelFont}px`:null,"important")
      // The halo of a label sized for the zoom of a Run-result view is sized with it.
      .style("stroke-width",d=>Number.isFinite(d.liveLabelFont)?`${d.liveLabelFont*.3}px`:null,"important")
      .text(d=>d.label);

    appendSimilarityBoxes(state.nodeSel);

    state.zoom=NativeGraph.zoom()
      .scaleExtent([.12,6])
      .on("zoom",e=>{state.zoomLayer.attr("transform",e.transform);updateZoomLabels(e.transform.k)});
    svg.call(state.zoom).on("dblclick.zoom",null);

    state.nodeSel.call(nodeDrag());
    focusOnHover(state.nodeSel)
      .on("click",(e,d)=>{
        if(clickAfterDrag(e))return;
        e.stopPropagation();
        inspectNode(e,d);
        revealInColumn($("infoPanel"));
        state.tooltipPinnedId=null;
        $("tooltip").hidden=true;
        state.selectedId=state.selectedId===d.id?null:d.id;
        applyClasses();
      });

    svg.on("click",e=>{if(clickAfterDrag(e))return;state.tooltipPinnedId=null;$("tooltip").hidden=true;state.selectedId=null;clearInspector();applyClasses()});
    bindInspectorLinks(linkLayer,graphSnapshot.links);

    state.simulation=NativeGraph.forceSimulation(graphSnapshot.nodes)
      .force("link",NativeGraph.forceLink(graphSnapshot.links).id(d=>d.id)
        .distance(d=>d.generatedAttributeLink?20:(d.groundingLink?36:(d.potentialGroundingLink?42:(d.source?.type==="Athlete"||d.target?.type==="Athlete"?13:25))))
        .strength(d=>d.generatedAttributeLink?.97:(d.groundingLink?.92:(d.potentialGroundingLink?.16:.82))))
      .force("charge",NativeGraph.forceManyBody().strength(graphSnapshot.nodes.length>300?-34:-62))
      .force("center",NativeGraph.forceCenter(width/2,height/2))
      .force("collision",NativeGraph.forceCollide().radius(d=>nodeRadius(d)+.8).iterations(1))
      .on("tick",tick);
    state.anchored=makeElastic(state.simulation,graphSnapshot.nodes);
    state.simulation.on("end.labels",scheduleLabelCull).on("tick.labels",cullWhileMoving);

    updateStatus();
  }


  function executeAttribute(){
    const s=currentScenario();
    const rows=s.rows||[];
    renderAttributeResult(rows,s.stat||{},s.recordedModel);

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
    newView();
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
      syncDemoGrounding();
    }
    initGraph();
    writeUrl();
  }

  // The fill of a score box: red below θ, neutral at θ and teal above, stronger the farther the
  // score lies from θ, so the boxes read as the colour ramp in the panel headers of the
  // T-MIGRANTS explorer. A box at least a quarter coloured keeps the side of θ visible.
  function scoreFill(score,theta){
    const above=score>=theta;
    const distance=above?(theta>=1?1:(score-theta)/(1-theta)):(theta-score)/Math.max(theta,1e-6);
    const strength=Math.round(25+75*Math.min(1,Math.max(0,distance)));
    return `color-mix(in srgb,var(${above?"--score-high":"--score-low"}) ${strength}%,var(--score-mid))`;
  }

  // The legend of the score boxes in the header of the graph, with θ marked on the ramp.
  function renderScoreLegend(){
    const legend=$("scoreLegend");
    if(!legend)return;
    const shown=!PRINT_LAYOUT&&Boolean(state.graph?.nodes?.some(n=>n.generatedCandidate));
    legend.hidden=!shown;
    if(!shown)return;
    const theta=Number($("theta").value);
    legend.innerHTML=`<span>Similarity</span><span>0</span>`+
      `<span class="score-ramp" style="--theta:${(theta*100).toFixed(1)}%" role="img" aria-label="Scores below θ are red, scores from θ on are teal"></span>`+
      `<span>1</span><span class="score-legend-theta">θ ${theta.toFixed(2)}</span>`;
  }

  // The address of the current view. The mode links carry it to the other mode, without the
  // scenario, which live mode does not have. An uploaded graph exists only in this page.
  function writeUrl(){
    const params=new URLSearchParams(location.search);
    if(currentDb()?.uploaded)params.delete("db");else params.set("db",state.dbKey);
    const scenario=currentScenario();
    if(pageMode==="demo"&&state.scenarioIndex>0&&scenario)params.set("tab",scenario.id);else params.delete("tab");
    const theta=Number($("theta").value);
    if(Math.abs(theta-Number($("theta").defaultValue))>1e-9)params.set("theta",theta.toFixed(2));else params.delete("theta");
    if($("groundEmbedding").checked)params.set("sim","embedding");else params.delete("sim");
    const query=params.toString().replace(/=(?=&|$)/g,"");
    history.replaceState(null,"",location.pathname+(query?"?"+query:"")+location.hash);
    const shared=new URLSearchParams(params);
    shared.delete("mode");shared.delete("tab");
    const demo=shared.toString(),live=new URLSearchParams(shared);
    live.set("mode","live");
    $("demoModeLink").href="index.html"+(demo?"?"+demo.replace(/=(?=&|$)/g,""):"");
    $("liveModeLink").href="index.html?"+live.toString().replace(/=(?=&|$)/g,"");
  }

  // θ, the similarity and the scenario of the address, once the controls exist.
  function applyUrlState(){
    const theta=Number(URL_STATE.get("theta"));
    if(URL_STATE.has("theta")&&theta>=.01&&theta<=1){
      $("theta").value=String(theta);
      $("thetaVal").textContent=theta.toFixed(2);
    }
    const sim=URL_STATE.get("sim");
    if(sim==="embedding"||sim==="jaccard"){
      $("groundEmbedding").checked=sim==="embedding";
      $("groundText").checked=sim!=="embedding";
      if(pageMode==="live")syncGrounding();
    }
    const tab=scenarioList().findIndex(s=>s.id===URL_STATE.get("tab"));
    if(pageMode==="demo"&&tab>=0)state.scenarioIndex=tab;
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
    window.GENSPARQL_EXPLORER?.attach({
      getGraph:()=>state.graph,
      getDatabase:currentDb,
      getScenario:currentScenario,
      getSettings:()=>({
        mode:pageMode,
        theta:Number($("theta").value),
        thetaUsed:pageMode==="live"||Boolean(currentScenario()?.hero),
        similarity:$("groundEmbedding").checked?"Embedding":"Jaccard",
        model:pageMode==="live"?($("chatModel")?.value.trim()||"the configured chat model"):currentScenario()?.recordedModel
      }),
      selectNode:id=>{state.inspectedLink=null;state.selectedId=state.graph.nodes.some(node=>node.id===id)?id:null;applyClasses();},
      clearSelection:()=>{state.inspectedLink=null;state.selectedId=null;if(state.nodeSel)applyClasses();}
    });
    initTheme();
    if(pageMode==="live"){
      initLive();
      initEngineBackend();
      initKgUpload();
      initLiveQuery();
    }
    $("databaseSelect").value=state.dbKey;$("databaseSelect").onchange=changeDb;
    applyUrlState();
    initDemoGrounding();
    $("theta").oninput=()=>{
      $("thetaVal").textContent=Number($("theta").value).toFixed(2);
      renderScoreLegend();
      window.GENSPARQL_EXPLORER?.graphChanged();
      if(pageMode==="live"){
        const editor=$("liveQuery");
        editor.value=window.GENSPARQL_EXPLORER.withThreshold(editor.value,$("theta").value);
        editor.dispatchEvent(new Event("input"));
        return;
      }
      renderScenario();
      if(state.thetaFrame)cancelAnimationFrame(state.thetaFrame);
      state.thetaFrame=requestAnimationFrame(()=>{
        state.thetaFrame=0;
        if(currentScenario()?.id==="entity"&&state.graph?.nodes.some(n=>n.generatedCandidate))updateGroundingThresholdOnly();
      });
    };
    $("theta").onchange=writeUrl;
    if(pageMode==="demo"){
      $("playSteps").onclick=togglePlayback;
      $("runBtn").onclick=execute;
      $("runGenopFirst").onclick=()=>executeCompositionStrategy("genop-first");
      $("runPlanner").onclick=()=>executeCompositionStrategy("planner");
    }
    $("resetGraph").onclick=resetGraph;
    $("fitGraph").onclick=()=>fitGraph(state.focusMode);
    $("lightAll").onclick=()=>{
      state.lightAll=!state.lightAll;
      $("lightAll").setAttribute("aria-pressed",String(state.lightAll));
      if(state.nodeSel)applyClasses();
    };
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
    writeUrl();
  }
  init();
})();
