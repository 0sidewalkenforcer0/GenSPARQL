(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const idOf = value => typeof value === "object" ? value?.id : value;
  const short = value => String(value || "").replace(/^.*[/#]/, "");
  const properties = new WeakMap();
  let context, selected = null, selectedDb = null, lastQuery = null, revision = 0, paintedKey = null;
  const graph = () => context?.getGraph?.() || {nodes:[],links:[]};
  const database = () => context?.getDatabase?.();
  const nodeFor = value => {
    const id = idOf(value);
    return graph().nodes.find(n => n.id === id) || database()?.graph.nodes.find(n => n.id === id)
      || (typeof value === "object" ? value : {id,label:short(id)});
  };
  const nodeButton = node => `<button class="info-entity" type="button" data-info-node="${esc(node.id)}">${esc(node.label || short(node.id))}<span aria-hidden="true">↗</span></button>`;
  const row = (label,value) => `<div class="info-field"><dt>${esc(label)}</dt><dd>${value}</dd></div>`;
  const section = (label,content) => `<section class="info-section"><h4>${esc(label)}</h4>${content}</section>`;
  const badge = (label,kind="") => `<span class="info-badge ${kind}">${esc(label)}</span>`;
  const safeUrl = value => /^https?:\/\/[^\s<>"']+$/i.test(String(value || ""));

  function paint(kind,html,clear=true){
    if(!$('infoContent'))return;
    const key=selected?.kind==='node'?'node:'+selected.id:selected?.kind==='link'?'link:'+idOf(selected.link.source)+':'+selected.link.predicate+':'+idOf(selected.link.target):selected?.kind==='row'?'row:'+selected.index:selected?.kind||'overview';
    const scroll=paintedKey===key?$('infoContent').scrollTop:0;
    $('infoKind').textContent=kind;
    $('infoContent').innerHTML=html;
    $('infoContent').scrollTop=scroll;
    paintedKey=key;
    $('clearInfo').hidden=!clear;
  }

  // While nothing is selected, the panel shows the graph and the settings of the view, a note on
  // how to read the view and where its answers come from, as the context panel of the T-MIGRANTS
  // explorer shows its focus, its filters and its sources.
  function caution(settings,scenario){
    if(settings.mode==='live')return 'Live answers come from the configured model at the time of the run and are not recorded.';
    if(scenario?.id==='entity')return settings.similarity==='Embedding'
      ?'A generated name is kept when its similarity to an entity reaches θ. Embedding compares the two names by the cosine similarity of their embeddings.'
      :'A generated name is kept when its similarity to an entity reaches θ. Jaccard compares the sets of lower-cased words of the two names, so a name that shares no word with an entity scores 0 against it.';
    if(scenario?.id==='attribute')return 'GENOP returns the values as new literals, so no grounding and no θ apply. The evaluation compared them with Wikidata.';
    if(scenario?.id==='composition')return 'Both execution orders return the same recorded result; the order only changes how many prompts reach the model.';
    return '';
  }

  function overview(){
    revision++;
    selected=null;
    selectedDb=database();
    const db=database();
    if(!db)return;
    const current=graph(),full=db.graph,settings=context?.getSettings?.()||{},scenario=context?.getScenario?.();
    const live=settings.mode==='live',note=caution(settings,scenario);
    const graphRows=row('Source',`<code>${esc(db.source || 'Uploaded graph')}</code>`)
      +row('Graph',`${full.nodes.length.toLocaleString()} entities · ${full.links.length.toLocaleString()} relations`)
      +row('In this view',`${current.nodes.length.toLocaleString()} nodes · ${current.links.length.toLocaleString()} relations`);
    const settingRows=row('Mode',live?'Live':'Recorded')
      +(live||!scenario?'':row('Scenario',esc(scenario.tab)))
      +(settings.model?row('Model',esc(settings.model)):'')
      +row('Threshold θ',settings.thetaUsed===false?'Not used in this scenario':Number(settings.theta).toFixed(2))
      +row('Similarity',esc(settings.similarity || 'Jaccard'))
      +row('Selection','None');
    paint('Graph overview',`<h3 class="info-title">${esc(db.label)}</h3>
      <p class="info-description">Select a node, a relation or a result row to see its details here.</p>
      <dl class="info-fields">${graphRows}</dl>
      ${section('Settings',`<dl class="info-fields">${settingRows}</dl>`)}
      ${note?`<p class="info-caution">${esc(note)}</p>`:''}
      ${!live&&!db.uploaded?'<p class="info-citation">Answers recorded in the evaluation of the paper.</p>':''}
      <p class="info-footnote">Hover to highlight neighbours. Click or drag a node to select it.</p>`,false);
  }

  function relationsFor(node){
    const links=[...(database()?.graph.links || []),...graph().links];
    const seen=new Set(),connected=[];
    for(const link of links){
      const a=idOf(link.source),b=idOf(link.target);
      if(a!==node.id&&b!==node.id)continue;
      const key=JSON.stringify([a,link.predicate,b]);
      if(seen.has(key))continue;
      seen.add(key);
      connected.push({link,outgoing:a===node.id,other:nodeFor(a===node.id?link.target:link.source)});
    }
    return connected;
  }

  function renderRelations(node){
    const related=relationsFor(node);
    if(!related.length)return section('Relations','<p class="info-description">No connected entities in this graph.</p>');
    const item=entry=>`<div class="info-relation"><span class="info-direction" title="${entry.outgoing?'Outgoing':'Incoming'}">${entry.outgoing?'→':'←'}</span><div><span class="info-predicate">${esc(entry.link.predicate || 'related to')}</span>${nodeButton(entry.other)}</div></div>`;
    return section(`Relations · ${related.length}`,related.slice(0,6).map(item).join('')+(related.length>6?`<details class="info-more"><summary>Show ${related.length-6} more</summary>${related.slice(6).map(item).join('')}</details>`:''));
  }

  async function triplesFor(db){
    if(Array.isArray(db.triples))return db.triples;
    if(!db.source||!window.N3)return [];
    if(!properties.has(db)){
      const promise=fetch(db.source).then(response=>{
        if(!response.ok)throw new Error('Graph properties unavailable');
        return response.text();
      }).then(text=>new N3.Parser({baseIRI:new URL(db.source,location.href).href}).parse(text).map(q=>({
        s:q.subject.termType==='BlankNode'?'_:'+q.subject.value:q.subject.value,
        p:q.predicate.value,
        o:{kind:q.object.termType==='Literal'?'literal':'iri',value:q.object.termType==='BlankNode'?'_:'+q.object.value:q.object.value,language:q.object.language||'',datatype:q.object.datatype?.value||''}
      }))).catch(()=>[]);
      properties.set(db,promise);
    }
    return properties.get(db);
  }

  function propertyRows(node,triples){
    const ignored=new Set(['http://www.w3.org/1999/02/22-rdf-syntax-ns#type','http://www.w3.org/2000/01/rdf-schema#label']);
    const values=triples.filter(t=>t.s===node.id&&t.o.kind==='literal'&&!ignored.has(t.p));
    if(!values.length)return '';
    return section('Properties','<dl class="info-fields">'+values.map(t=>row(short(t.p),`${esc(t.o.value)}${t.o.language?` <span class="info-language">@${esc(t.o.language)}</span>`:''}`)).join('')+'</dl>');
  }

  function nodeHtml(node,triples=[]){
    const generated=Boolean(node.generatedCandidate||node.generatedAttribute||node.liveGeneratedPos||node.compositionPosNode);
    let fields=row('Type',esc(node.type || (generated?'Generated value':'Entity')));
    if(generated){
      fields+=row('Origin',badge('Generated by GENOP','generated'));
      const source=node.sourceAthleteId&&nodeFor(node.sourceAthleteId);
      if(source)fields+=row('For',nodeButton(source));
      if(node.generatedVariable)fields+=row('Variable',`<code>?${esc(node.generatedVariable)}</code>`);
      if(node.evaluationState)fields+=row('Evaluation',esc(node.evaluationState));
      const model=context?.getScenario?.()?.recordedModel;
      if(model&&window.GENSPARQL_PAGE_MODE!=='live')fields+=row('Model',esc(model));
    }else{
      fields+=row('Origin',badge('Knowledge graph'));
    }
    if(node.generatedCandidate){
      if(Number.isFinite(node.score))fields+=row('Similarity',`<strong>${node.score.toFixed(4)}</strong>`);
      fields+=row('Threshold',Number($('theta').value).toFixed(2));
      fields+=row('Mapping',badge(node.grounded?'Matched':'No match above threshold',node.grounded?'matched':'unmatched'));
      if(node.bestId)fields+=row('Closest entity',nodeButton(nodeFor(node.bestId)));
      if(node.similarityMethod)fields+=row('Method',/^(text|jaccard)$/i.test(node.similarityMethod)?'Jaccard':esc(node.similarityMethod));
    }
    if(node.filterApplied)fields+=row('FILTER',badge(node.compositionDropped?'Dropped':'Kept',node.compositionDropped?'unmatched':'matched'));
    const aliases=(node.aliases||[]).filter(a=>a!==node.label);
    if(aliases.length)fields+=row('Other labels',aliases.map(esc).join(', '));
    const uri=safeUrl(node.id)?`<a class="info-iri" href="${esc(node.id)}" target="_blank" rel="noopener noreferrer">${esc(node.id)} ↗</a><button class="info-copy" type="button" data-copy-iri="${esc(node.id)}">Copy IRI</button>`:`<code class="info-iri">${esc(node.id)}</code>`;
    return `<div class="info-selection-heading">${badge(generated?'Generated value':'Entity',generated?'generated':'')}<h3 class="info-title">${esc(node.label || short(node.id))}</h3></div><dl class="info-fields">${fields}</dl>${section(generated?'Identifier':'IRI',uri)}${propertyRows(node,triples)}${renderRelations(node)}`;
  }

  function showNode(value){
    const node=nodeFor(value);
    if(!node?.id)return;
    selected={kind:'node',id:node.id};selectedDb=database();
    const version=++revision;
    const kind=node.generatedCandidate||node.generatedAttribute||node.compositionPosNode?'Generated value':'Entity details';
    paint(kind,nodeHtml(node));
    const db=database();
    triplesFor(db).then(triples=>{
      if(revision===version&&selectedDb===db)paint(kind,nodeHtml(node,triples));
    });
  }

  function showLink(link){
    selected={kind:'link',link};selectedDb=database();revision++;
    const source=nodeFor(link.source),target=nodeFor(link.target);
    const similarity=link.groundingLink||link.potentialGroundingLink;
    let fields=row('From',nodeButton(source))+row('To',nodeButton(target));
    const score=Number(link.score ?? link.similarity ?? parseFloat(link.scoreLabel));
    if(similarity){
      if(Number.isFinite(score))fields+=row('Similarity',score.toFixed(4));
      fields+=row('Threshold',Number($('theta').value).toFixed(2));
      fields+=row('Mapping',badge(link.groundingLink?'Matched':'Below threshold',link.groundingLink?'matched':'unmatched'));
    }
    fields+=row('Origin',badge(similarity?'Similarity join':link.generatedAttributeLink?'Generated by GENOP':'Knowledge graph',link.generatedAttributeLink?'generated':''));
    paint('Relation details',`<div class="info-selection-heading">${badge(similarity?'Similarity join':'Relation')}<h3 class="info-title">${esc(link.predicate || (similarity?'Maps to':'Related to'))}</h3></div><dl class="info-fields">${fields}</dl><p class="info-footnote">Select either entity to inspect its properties and connections.</p>`);
  }

  function showQuery(result){
    lastQuery=result;selected={kind:'query'};selectedDb=database();revision++;
    paint('Query execution',`<h3 class="info-title">Query completed</h3><div class="info-stats"><div><strong>${result.total.toLocaleString()}</strong><span>Result rows</span></div><div><strong>${result.genopCalls}</strong><span>GENOP calls</span></div></div><dl class="info-fields">${row('Database',esc(database().label))}${row('Variables',result.vars.map(v=>`<code>?${esc(v)}</code>`).join(' '))}</dl><p class="info-description">Select a result row to inspect its bindings, or a graph element to explore its connections.</p>`);
  }

  function showResultRow(index){
    const binding=lastQuery?.rows[index];
    if(!binding)return;
    selected={kind:'row',index};revision++;
    paint('Result bindings',`<h3 class="info-title">Result row ${index+1}</h3><dl class="info-fields">${lastQuery.vars.map(variable=>{
      const term=binding[variable];
      return row('?'+variable,!term?'<span class="info-description">Unbound</span>':term.kind==='iri'?nodeButton(nodeFor(term.value)):`<span>${esc(term.value)}</span>${term.lang?` <span class="info-language">@${esc(term.lang)}</span>`:''}`);
    }).join('')}</dl>`);
  }

  // Apply the visible threshold only to GENOP arguments, respecting quoted prompts,
  // comments, IRIs, nested output tuples and additional key:value options.
  function withThreshold(query,threshold){
    const text=String(query),edits=[];
    function skipLiteral(i){
      if(text[i]==='#'){const end=text.indexOf('\n',i);return end<0?text.length:end;}
      if(text[i]==='"'||text[i]==="'"){
        const quote=text[i],delimiter=text.slice(i,i+3)===quote.repeat(3)?quote.repeat(3):quote;
        let j=i+delimiter.length;
        while(j<text.length){if(text[j]==='\\'){j+=2;continue;}if(text.slice(j,j+delimiter.length)===delimiter)return j+delimiter.length;j++;}
        return text.length;
      }
      if(text[i]==='<'){
        const end=text.indexOf('>',i+1);
        if(end>=0&&!/[\s]/.test(text.slice(i+1,end)))return end+1;
      }
      return i;
    }
    for(let i=0;i<text.length;){
      const skipped=skipLiteral(i);if(skipped!==i){i=skipped;continue;}
      const match=(i===0||!/[\w:]/.test(text[i-1]))&&/^GENOP\s*\(/i.exec(text.slice(i));
      if(!match){i++;continue;}
      let depth=1,j=i+match[0].length,commas=[];
      for(;j<text.length&&depth;j++){
        const after=skipLiteral(j);if(after!==j){j=after-1;continue;}
        if(text[j]==='(')depth++;
        else if(text[j]===')')depth--;
        else if(text[j]===','&&depth===1)commas.push(j);
      }
      if(depth===0&&commas.length>=2){
        const end=j-1,value=Number(threshold).toFixed(2);
        if(commas.length===2)edits.push({start:end,end,text:', '+value});
        else{
          const start=commas[2]+1,stop=commas[3]??end;
          if(/^\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?\s*$/i.test(text.slice(start,stop)))edits.push({start,end:stop,text:' '+value});
          else edits.push({start,end:start,text:' '+value+','});
        }
      }
      i=j;
    }
    let result=text;
    for(const edit of edits.reverse())result=result.slice(0,edit.start)+edit.text+result.slice(edit.end);
    return result;
  }

  $('clearInfo').addEventListener('click',()=>{context?.clearSelection?.();overview();});
  $('infoContent').addEventListener('click',async event=>{
    const entity=event.target.closest('[data-info-node]');
    if(entity){context?.selectNode?.(entity.dataset.infoNode);showNode(entity.dataset.infoNode);return;}
    const copy=event.target.closest('[data-copy-iri]');
    if(copy){try{await navigator.clipboard.writeText(copy.dataset.copyIri);copy.textContent='Copied';}catch(_){copy.textContent='Copy unavailable';}}
  });
  $('liveResultBody').addEventListener('click',event=>{
    const tr=event.target.closest('tbody tr');
    if(tr)showResultRow([...tr.parentElement.children].indexOf(tr));
  });
  $('liveResultBody').addEventListener('keydown',event=>{
    const tr=event.target.closest('tbody tr');
    if(tr&&(event.key==='Enter'||event.key===' ')){
      event.preventDefault();
      showResultRow([...tr.parentElement.children].indexOf(tr));
    }
  });
  window.GENSPARQL_EXPLORER={
    attach(value){context=value;overview();},showNode,showLink,showQuery,overview,withThreshold,
    graphChanged(){
      if(database()!==selectedDb){lastQuery=null;overview();return;}
      if(selected?.kind==='node'){
        const node=graph().nodes.find(n=>n.id===selected.id);
        if(node)showNode(node);else overview();
      }else if(selected?.kind==='link'){
        const old=selected.link;
        const link=graph().links.find(l=>idOf(l.source)===idOf(old.source)&&idOf(l.target)===idOf(old.target)&&l.predicate===old.predicate);
        if(link)showLink(link);else overview();
      }else if(!selected)overview();
    }
  };
})();
