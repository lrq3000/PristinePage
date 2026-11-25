/* Three independent, replayable first launches. All documents, permissions, language packs and folder operations below are samples; nothing here opens a device picker or reads user files. */
(() => {
  "use strict";
  const designs={
    folio:{name:"Field Guide",mark:"Plate",sample:"Herbarium sheet",word:"sage",text:"Herbarium / 04 · Wild sage · Garden • North bed",title:"Keep the first impression.",lead:"This garden note came in crooked and in shadow. Straighten it for reading today. The photograph you took is still there for tomorrow.",order:["start","practice","access","languages","library"],art:"Botanical collection",entry:"Turn the page"},
    bench:{name:"Scan Bench",mark:"Station",sample:"Coffee receipt",word:"toast",text:"Night Shift · Coffee counter · Filter coffee 4.00 · Rye toast 6.00 · Total 10.00",title:"Take the shot. Fix it later.",lead:"The café queue is moving. Take one quick photo of the receipt, save it, and straighten the page when you have time.",order:["practice","start","access","languages","library"],art:"Coffee counter",entry:"Try the bench"},
    atlas:{name:"Library Atlas",mark:"Stop",sample:"Rail ticket",word:"Meridian",text:"Coastal Rail · Port Meridian · Bay line · Platform 03 · One journey",title:"Give your scans an address.",lead:"Keep a new collection on this device, or open the folder you brought from another one. Your ticket through this tour is a scan from the coastal railway.",order:["library","start","practice","languages","access"],art:"Coastal line",entry:"Follow the route"}
  };
  const labels={start:"Principles",practice:"Try a scan",access:"Camera",languages:"Languages",library:"Library"};
  const originalFolder="Default app storage", newFolder="Documents / Field notes", existingFolder="Scans / PristinePage", backupFolder="Backups / PristinePage";
  const defaults=k=>({page:designs[k].order[0],finished:false,ui:"System default",packs:["English"],camera:"Ask when needed",active:originalFolder,folders:{[originalFolder]:["Notebook"],[newFolder]:[],[existingFolder]:["Timetables"]},backups:[],storageNote:"",ethos:"original",view:"Corrected",stage:"capture",tool:"Capture",source:"",saved:false,recipe:"Auto",accepted:"Auto",amount:70,acceptedAmount:70,compare:false,ocr:"idle",query:"",fromHome:false});
  const states={}; let k=location.hash.slice(1); if(!Object.hasOwn(designs,k))k="folio";
  for(const id of Object.keys(designs)){
    try{states[id]=JSON.parse(sessionStorage.getItem("pristinepage.studio.2."+id))||defaults(id)}catch{states[id]=defaults(id)}
  }
  const s=()=>states[k],d=()=>designs[k],surface=document.querySelector("#surface"),sheet=document.querySelector("#sheet"),nav=document.querySelector(".designs");
  const escape=v=>String(v).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const save=()=>{try{sessionStorage.setItem("pristinepage.studio.2."+k,JSON.stringify(s()))}catch{/* The study also works when browser storage is disabled. */}};
  const action=(a,v="")=>`data-action="${escape(a)}" data-value="${escape(v)}"`;
  const btn=(a,text,v="",cls="textbtn")=>`<button class="${cls}" ${action(a,v)}>${text}</button>`;
  const chip=(a,text,v,on)=>`<button class="chip" ${action(a,v)} aria-pressed="${on}">${text}</button>`;
  const choice=(a,title,note,icon,v="")=>`<button class="choice" ${action(a,v)}><span class="sign">${firstRunArt.icon(icon)}</span><span>${title}<small>${note}</small></span></button>`;
  const msg=t=>`<div class="message" role="status">${t}</div>`;
  const caption=t=>`<p class="caption">${t}</p>`;
  const describe=(title,lead,body)=>({title,lead,body});
  const guide={
    original:["Keep the source","Original photos and imports stay intact. Corrections are instructions stored alongside them. A JPEG export is a rendered copy; it does not replace the original."],
    fast:["Save now","Capture or import a page, accept an automatic name and correction, then reopen your album to fine-tune the page."],
    search:["Find a word","Background text recognition reads the saved page. Search for a word printed inside it, even if you never put that word in the album name."],
    offline:["Stay offline","The page, its corrections and text recognition stay on your device. Once the reading language is available, no connection or account is needed."],
    yours:["Own the folder","Open a library where it lives, move it to another folder, or keep a separate backup you can bring to another device."]
  };
  function start(){
    const e=s().ethos;
    return describe(k==="folio"?d().title:k==="bench"?"Under the glass.":"What travels with a page?",k==="folio"?d().lead:k==="bench"?"Lift the corrected receipt and look underneath. You can change the instructions without taking the photo again.":"An album is more than a folder of finished pictures. Follow the ticket to see what stays with it.",`<div class="chips">${Object.entries(guide).map(([id,[t]])=>chip("ethos",t,id,e===id)).join("")}</div>${caption(guide[e][1])}`);
  }
  const techniques={Frame:["Edge detection","Move the sample crop inward or outward. The green corners are the retained boundary."],Flatten:["Deformation","Straighten the slanted page. The preview changes shape as you adjust it."],Light:["Illumination","Raise the correction to lift the shadow across the paper."],Color:["Color","Restore the paper’s color or take it toward monochrome."],Rotate:["Rotation","Turn the working page. Its stored source keeps the orientation you imported."]};
  function practice(){
    const a=s(),name=d().sample,which=a.stage,seed=escape(d().word);
    if(which==="capture")return describe(k==="bench"?d().title:`Try one ${name.toLowerCase()}.`,k==="bench"?d().lead:"Use the illustrated sample. Pick the way it arrives; the rest of the journey is the same.",`<div>${a.camera==="Gallery and files only"?"":choice("acquire","Take sample photo","Simulate a quick capture.","camera","Camera")}${choice("acquire","From gallery","Choose the illustrated image.","album","Gallery")}${choice("acquire","From files","Import the sample page.","folder","Files")}</div>`);
    if(which==="auto")return describe("Ready to save.",`${a.source} supplied the original. PristinePage suggests an album name and corrects the detected page edges, perspective and lighting.`, `<dl class="facts"><div><dt>Suggested album name</dt><dd>Scan · 24 Nov</dd></div><div><dt>Page</dt><dd>1 · ${name}</dd></div></dl>${caption("Compare the two previews. Save now; you can change any correction later.")}${btn("save","Save album","","cta")}`);
    if(which==="saved")return describe("Saved. Come back to it.",`“Scan · 24 Nov” contains your ${name.toLowerCase()}. The original and the saved correction are both available.`,`${msg(a.ocr==="reading"?"Reading page 1 in the background…":a.ocr==="ready"?"Page 1 is searchable on this device.":"Text recognition is off until you select a reading language.")}${choice("edit","Reopen page","Try Frame, flattening, lighting and color.","album")}${choice("search","Find a word",`Search the page for “${seed}”.`,"read")}`);
    if(which==="edit"){
      const t=techniques[a.recipe];
      return describe("Back at the worktable.",`Adjust the saved page, then compare it with the source. Apply stores the new look with this sample.`, `<div class="chips">${Object.entries(techniques).map(([id,[name]])=>chip("tool",name,id,a.recipe===id)).join("")}</div>${t?`<label class="group strength"><span>${escape(t[0])} · <output id="level">${a.amount}</output>%</span><input type="range" id="strength" min="0" max="100" value="${a.amount}"></label>${caption(t[1])}`:caption(a.recipe==="Original"?"All corrections are off. Apply to keep this original view.":"Automatic correction is active. Pick a tool to change the preview.")}<div class="links">${btn("revert","Revert to original")}${btn("apply","Apply","","cta")}</div>`);
    }
    return describe("Find it by a word.",`The album is called “Scan · 24 Nov”. Try “${seed}”, a word printed on the page itself.`, `<label class="group"><span>Search my scans</span><input class="search" id="query" type="search" placeholder="Search recognized text" value="${escape(a.query)}"></label><div id="hits" aria-live="polite">${hits()}</div>${btn("back-sample","Back to saved album")}`);
  }
  function hits(){
    const a=s(),q=a.query.trim(),text=d().text,index=text.toLowerCase().indexOf(q.toLowerCase());
    if(a.ocr==="reading")return caption("Reading page 1 in the background…");
    if(a.ocr!=="ready")return caption("No reading language selected. Choose an OCR pack in Languages, then return here.");
    if(!q)return btn("suggest",`Try “${escape(d().word)}”`,d().word,"chip");
    if(index<0)return caption("No matching pages. Try another word.");
    return `<button class="search-hit" ${action("hit")}><b>Scan · 24 Nov → Page 1</b>${escape(text.slice(0,index))}<mark>${escape(text.slice(index,index+q.length))}</mark>${escape(text.slice(index+q.length))}</button>`;
  }
  function access(){return describe("Camera is your choice.","Allow the camera when you want to photograph a page. Choose gallery and files only if you prefer to import. You can change this later.",`${choice("camera","Try camera permission","Preview the permission question.","camera")}${choice("gallery","Use gallery and files only","Continue without camera access.","album")}${msg("Current choice: "+s().camera)}`)}
  const languages=["English","French","German","Spanish"];
  function language(){return describe("Menus and reading are separate.","Choose the language of the controls, then the languages printed on your pages. The sample menu greeting previews your interface choice.",`<label class="group"><span>Interface language</span><select id="ui">${["System default","English","Français"].map(t=>`<option${s().ui===t?" selected":""}>${t}</option>`).join("")}</select></label><fieldset class="packs"><legend>OCR language packs</legend><div class="language-list">${languages.map(t=>`<label><input type="checkbox" data-pack="${t}" ${s().packs.includes(t)?"checked":""}> ${t}</label>`).join("")}</div></fieldset>${caption("Only choose the languages you scan. Packs must be available on the device before offline recognition. No pack downloads occur in this preview.")}`)}
  const folderCount=(f)=>s().folders[f]?.length||0;
  function library(){return describe(k==="atlas"?d().title:"Where will your scans live?",k==="atlas"?d().lead:"Start with app storage, pick an empty folder, or open a library where it already lives.",`${choice("default","Use default app storage",s().active===originalFolder?"Currently selected. No setup needed.":"Choose the app-managed library.","folder")}${choice("pick","Choose a library folder","Try an empty folder or an existing PristinePage library.","folder")}${btn("operations","Try move, backup and import")}${msg(`Active: <strong>${escape(s().active)}</strong>${s().storageNote?"<br>"+escape(s().storageNote):""}`)}<details><summary>Back up or sync to another device</summary>A separate app such as Syncthing can copy an external library folder. PristinePage does not sync it. Close the library, let the copy finish, then open it on the other device. Avoid editing on both devices at once.</details>`)}
  function home(){return describe("My scans","Your first-run choices are in Settings. The practice document is a separate sample you can revisit at any time.",`${choice("practice-home",s().saved?"Open practice album":"Try a sample scan",s().saved?"Scan · 24 Nov · Page 1":"Use an illustrated document.","album")}${choice("settings","Settings","Replay onboarding or explore library storage and backup.","settings")}`)}
  function settings(){return describe("Settings","Revisit the tour with your current choices, or explore how a library would move between folders.",`${choice("replay","Replay first-run wizard","Start this guide again. Keep your sample album and settings.","read")}${choice("operations","Library storage and backup",s().active,"folder")}<dl class="facts"><div><dt>Camera</dt><dd>${s().camera}</dd></div><div><dt>Interface</dt><dd>${s().ui}</dd></div><div><dt>OCR packs</dt><dd>${s().packs.join(", ")||"None"}</dd></div></dl>`) }
  const pages={start,practice,access,languages:language,library,home,settings};
  function illustration(){
    const a=s(),p=a.page;
    let tool=a.tool;
    if(p==="practice")tool=a.stage==="capture"?"Capture":a.stage==="search"?"Search":a.compare?"Original":a.stage==="auto"?"Frame":a.stage==="saved"?a.accepted:a.recipe;
    return firstRunArt.scene(k,p,{...a,tool,match:d().text.toLowerCase().includes(a.query.trim().toLowerCase())});
  }
  function render(focus=false){
    const prev=document.activeElement,prevAction=prev?.closest("[data-action]")?.dataset;
    const a=s(),p=a.page,sequence=d().order,idx=sequence.indexOf(p),tour=idx>=0&&!a.fromHome,data=pages[p](),step=idx+1;
    document.body.dataset.look=k;
    nav.innerHTML=Object.entries(designs).map(([id,model],i)=>`<button ${action("design",id)} aria-pressed="${k===id}"><i>0${i+1}</i>${model.name}</button>`).join("");
    const toggles=p==="practice"&&["auto","edit","saved"].includes(a.stage)?["compare",a.compare?"Original":"Corrected"]:p==="start"&&a.ethos==="original"?["view",a.view]:null;
    const toolbar=toggles?`<div class="art-command" role="group" aria-label="Compare page">${["Original","Corrected"].map(t=>`<button ${action(toggles[0],t)} aria-pressed="${toggles[1]===t}">${t}</button>`).join("")}</div>`:"";
    const demoSteps={capture:[1,"Choose a source"],auto:[2,"Review"],saved:[3,"Saved album"],edit:[4,"Edit a saved page"],search:[5,"Find the page"]};
    const demoNext={capture:["acquire",a.camera==="Gallery and files only"?"Use sample image":"Take sample photo",a.camera==="Gallery and files only"?"Gallery":"Camera"],auto:["save","Save album"],saved:a.triedEdit?["search","Try text search"]:["edit","Reopen page"],edit:["apply","Apply"]};
    const nextLabel=idx===sequence.length-1?"Finish setup":p==="start"&&k==="folio"?d().entry:"Continue";
    const primary=p==="practice"&&demoNext[a.stage]?demoNext[a.stage]:["next",nextLabel];
    surface.innerHTML=`<div class="shell ${k}"><div class="appbar"><span class="wordmark">${d().name}</span>${tour?btn("skip","Skip setup","","smallbtn"):p!=="home"?btn("home","My scans","","smallbtn"):btn("settings","Settings","","smallbtn")}</div><section class="frame">${tour?`<nav class="progress" aria-label="Wizard pages">${sequence.map((id,i)=>`<button ${action("page",id)}${id===p?' aria-current="step"':""}><b>${i+1}</b>${labels[id]}</button>`).join("")}</nav>`:""}<div class="spread"><div class="visual"><div class="arthead"><span>${d().art}</span><span>${d().mark} ${String(tour?step:1).padStart(2,"0")}</span></div><div class="art">${illustration()}</div>${toolbar}<div class="artfoot"><span>${d().sample}</span><span>${p==="practice"&&a.saved?"Saved sample":"PristinePage"}</span></div></div><div class="copy"><p class="kicker">${tour?`${d().mark} ${step} / ${sequence.length} · ${labels[p]}`:"Sample workspace"}</p><h1 tabindex="-1">${data.title}</h1><p class="lead">${data.lead}</p><div class="controls">${data.body}</div></div></div>${tour?`<div class="stepfoot"><div>${idx?btn("back","Back"): `<span class="position">FIRST LAUNCH</span>`}<span class="position">${step} / ${sequence.length}</span></div>${btn("next",`${idx===sequence.length-1?"Finish setup":p==="start"&&k==="folio"?d().entry:"Continue"} ${firstRunArt.icon("arrow")}`,"","cta")}</div>`:""}</section></div>`;
    if(p==="practice"){
      const [n,title]=demoSteps[a.stage];
      surface.querySelector(".kicker").textContent=`Scan example · ${n}/5 · ${title}`;
      if(tour){
        const foot=surface.querySelector(".stepfoot");
        foot.lastElementChild.outerHTML=btn(primary[0],`${primary[1]} ${firstRunArt.icon("arrow")}`,primary[2]||"","cta");
        if(a.stage!=="search")foot.firstElementChild.insertAdjacentHTML("beforeend",btn("next","Skip example"));
        if(["auto","edit"].includes(a.stage))surface.querySelector(`.controls [data-action="${primary[0]}"]`)?.remove();
      }
    }
    save(); if(focus){surface.querySelector("h1").focus({preventScroll:true});window.scrollTo(0,0)}
    else if(prevAction&&!prev.isConnected){const target=[...document.querySelectorAll("button[data-action]")].find(el=>el.dataset.action===prevAction.action&&el.dataset.value===prevAction.value);(target||surface.querySelector("h1")).focus({preventScroll:true})}
  }
  function go(p){s().page=p;render(true)}
  function show(title,body){
    sheet.innerHTML=`<h2 id="sheet-title">${title}</h2>${body}<div class="dialogfoot">${btn("close","Cancel")}</div>`;
    if(!sheet.open)sheet.showModal();else sheet.scrollTop=0;
    sheet.querySelector("button")?.focus();
  }
  function notify(text){document.querySelector("#announce").textContent=text}
  const close=()=>{if(sheet.open)sheet.close()};
  const names=(v)=>escape(v);
  function operations(){show("Library storage and backup",`<p>Active: <strong>${names(s().active)}</strong> · ${folderCount(s().active)} sample album(s). All folders in this picker are simulated.</p>${choice("pick","Change library folder","Open in place, start empty, or move.","folder")}${choice("export","Export library","Create a separate, complete backup folder.","folder")}${choice("import","Import library","Copy albums into the active folder.","album")}${s().storageNote?msg(escape(s().storageNote)):""}`)}
  function picker(){show("Choose a library folder",`<p>Simulated folder picker. Select a destination to see your options.</p>${[originalFolder,newFolder,existingFolder].map(f=>choice("folder",names(f),`${f===s().active?"Active · ":""}${folderCount(f)?folderCount(f)+" sample album(s)":"Empty folder"}`,"folder",f)).join("")}`)}
  function folders(f){
    const a=s(),n=folderCount(f);if(f===a.active){close();a.storageNote="Kept the current folder.";render();return}
    show(names(f),`<p>Current: <strong>${names(a.active)}</strong>. Selected: <strong>${names(f)}</strong>.</p>${choice("activate",n?"Open this library":"Start an empty library",n?"Open in place. The previous library stays where it is.":"Switch here and leave the previous library untouched.","folder",f)}${choice("move-dialog",n?"Move and merge":"Move library",`Transfer albums from ${names(a.active)}. Review before moving.`,"album",f)}`);
  }
  function activate(f,note){s().active=f;s().storageNote=note;close();render();notify(note)}
  function finishTransfer(note){s().storageNote=note;close();render();show("Sample operation complete",`<p>${escape(note)}</p>${btn("operations","Library storage and backup","","outline")}${btn("close","Done","","cta")}`)}
  function restartOCR(id=k){
    const a=states[id];if(!a.saved)return;
    a.ocr=a.packs.length?"reading":"off";
    if(!a.packs.length)return;
    window.setTimeout(()=>{if(states[id]!==a||!a.packs.length||a.ocr!=="reading")return;a.ocr="ready";
      if(k===id){if(a.page==="practice"&&a.stage==="saved")render();else if(a.page==="practice"&&a.stage==="search"){document.querySelector("#hits").innerHTML=hits();document.querySelector(".art").innerHTML=illustration()}save()}
    },750);
  }
  function stage(step){s().stage=step;s().compare=false;render()}
  document.addEventListener("click",ev=>{
    const b=ev.target.closest("[data-action]");if(!b)return;const act=b.dataset.action,v=b.dataset.value,a=s(),seq=d().order;
    if(act==="design"){if(v!==k){k=v;location.hash=v;render(true)}return}
    if(act==="fresh"){states[k]=defaults(k);render(true);return}
    if(act==="page"){go(v);return}
    if(act==="back"){go(seq[seq.indexOf(a.page)-1]);return}
    if(act==="next"){const n=seq.indexOf(a.page)+1;if(n<seq.length){go(seq[n]);return}}
    if(["next","skip","home"].includes(act)){a.finished=true;a.fromHome=false;go("home");return}
    if(act==="settings"){go("settings");return}
    if(act==="replay"){a.fromHome=false;a.stage="capture";go(seq[0]);return}
    if(act==="practice-home"){a.fromHome=true;a.stage=a.saved?"saved":"capture";a.compare=false;go("practice");return}
    if(act==="ethos"||act==="view"){a[act]=v;render();return}
    if(act==="compare"){a.compare=v==="Original";render();return}
    if(act==="acquire"){a.source=v;a.triedEdit=false;stage("auto");return}
    if(act==="save"){a.saved=true;a.accepted=a.recipe="Auto";a.acceptedAmount=a.amount=70;restartOCR();stage("saved");return}
    if(act==="edit"||act==="hit"){a.recipe=a.accepted;a.amount=a.acceptedAmount;stage("edit");return}
    if(act==="tool"){a.recipe=v;a.compare=false;render();return}
    if(act==="apply"){a.accepted=a.recipe;a.acceptedAmount=a.amount;a.triedEdit=true;stage("saved");return}
    if(act==="revert"){a.recipe="Original";a.compare=false;render();return}
    if(act==="search"||act==="back-sample"){stage(act==="search"?"search":"saved");return}
    if(act==="suggest"){a.query=v;render();document.querySelector("#query")?.focus();return}
    if(act==="camera"){show("Allow camera access?",`<p>Sample permission prompt. Allow capture, or decline and keep importing from gallery and files.</p>${btn("allow","Allow camera","","cta")} ${btn("gallery","Do not allow","","outline")}`);return}
    if(act==="allow"||act==="gallery"){a.camera=act==="allow"?"Camera allowed":"Gallery and files only";close();render();return}
    if(act==="close"){close();return}
    if(act==="operations"){operations();return}
    if(act==="pick"){picker();return}
    if(act==="default"||act==="folder"){folders(act==="default"?originalFolder:v);return}
    if(act==="activate"){activate(v,"Previous library left untouched.");return}
    if(act==="move-dialog"){show(folderCount(v)?"Confirm move and merge":"Confirm move",`<p>Move albums from <strong>${names(a.active)}</strong> to <strong>${names(v)}</strong>${folderCount(v)?" and merge with its albums":""}? Only after the destination is verified and active will the old app-managed library be removed. Unrelated files stay in place.</p>${btn("move","Confirm verified move",v,"cta")}`);return}
    if(act==="move"){const old=a.active;a.folders[v]=[...new Set([...a.folders[v],...a.folders[old]])];a.folders[old]=[];a.active=v;finishTransfer(`Preview: verified and opened ${v}, then retired the old library from ${old}.`);return}
    if(act==="export"){const dest=`Backups / PristinePage ${a.backups.length+1}`;show("Export a backup folder",`<p>Copy the albums, original files, edits and recognized text from <strong>${names(a.active)}</strong> to <strong>${dest}</strong>. The active library will not change.</p>${btn("export-ok","Create sample backup",dest,"cta")}`);return}
    if(act==="export-ok"){a.backups.push({folder:v,albums:[...a.folders[a.active]]});finishTransfer(`Backup created at ${v}. The active library is unchanged.`);return}
    if(act==="import"){show("Choose a backup to import",`<p>Copy from a backup into ${names(a.active)}.</p>${[{folder:backupFolder,albums:["Travel notes"]},...a.backups].map(({folder,albums})=>choice("import-dialog",names(folder),`${albums.length} sample album(s) · Source stays intact.`,"folder",folder)).join("")}`);return}
    if(act==="import-dialog"){const n=folderCount(a.active);show(n?"Confirm copy and merge":"Confirm import",`<p>Copy albums from <strong>${names(v)}</strong> into <strong>${names(a.active)}</strong>${n?" and merge with the albums already there":""}? The backup and its files will not change.</p>${btn("import-ok",n?"Copy and merge":"Copy albums",v,"cta")}`);return}
    if(act==="import-ok"){const src=v===backupFolder?["Travel notes"]:a.backups.find(x=>x.folder===v).albums;a.folders[a.active]=[...new Set([...a.folders[a.active],...src])];finishTransfer(`Copied from ${v} into ${a.active}. The backup is unchanged.`)}
  });
  document.addEventListener("change",ev=>{const field=ev.target;if(field.id==="ui"){s().ui=field.value;render();document.querySelector("#ui").focus()}else if(field.dataset.pack){const p=field.dataset.pack;s().packs=field.checked?[...s().packs,p]:s().packs.filter(it=>it!==p);restartOCR();save()}});
  document.addEventListener("input",ev=>{const field=ev.target;if(field.id==="query"){s().query=field.value;document.querySelector("#hits").innerHTML=hits();document.querySelector(".art").innerHTML=illustration();save()}else if(field.id==="strength"){s().amount=Number(field.value);document.querySelector("#level").textContent=field.value;document.querySelector(".art").innerHTML=illustration();save()}});
  window.addEventListener("hashchange",()=>{const id=location.hash.slice(1);if(Object.hasOwn(designs,id)&&k!==id){close();k=id;render(true)}});
  for(const id of Object.keys(designs))if(states[id].ocr==="reading")restartOCR(id);
  render();
})();
