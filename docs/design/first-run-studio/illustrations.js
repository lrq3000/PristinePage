/* All specimens are drawn for this study. No personal photograph, font service or network asset is used. */
window.firstRunArt = (() => {
  const line = (x,y,w,c="#778473") => `<path d="M${x} ${y}h${w}" stroke="${c}" stroke-width="1.5"/>`;
  const label = (x,y,s,opts="") => `<text x="${x}" y="${y}" font-family="Consolas,monospace" font-size="9" letter-spacing="1" fill="currentColor" ${opts}>${s}</text>`;
  const svg = (s,title) => `<svg viewBox="0 0 470 295" role="img" aria-label="${title}">${s}</svg>`;
  const leaf = (x,y,angle,scale) => `<g transform="translate(${x} ${y}) rotate(${angle}) scale(${scale})"><path d="M0 0C-13-13-15-30 0-50C13-34 14-14 0 0Z" fill="#557854" stroke="#355640"/><path d="M0 0v-45m0 32-6-8m6-1 6-9m-6 1-5-7" stroke="#c6ce98" stroke-width=".9" fill="none"/></g>`;
  const botanical = `<rect width="172" height="208" rx="2" fill="#fffbed"/><rect x="9" y="9" width="154" height="190" fill="none" stroke="#d9d9bd"/>
    ${label(17,26,"HERBARIUM   /   04")}<path d="M69 151q16-40 32-99m-21 68-17-24m27-7 22-16" fill="none" stroke="#385e41" stroke-width="2.4"/>
    ${leaf(76,132,-55,.67)}${leaf(82,111,55,.78)}${leaf(87,99,-36,.75)}${leaf(96,73,38,.64)}${leaf(101,55,9,.47)}
    <circle cx="42" cy="149" r="12" fill="none" stroke="#9f6443" stroke-dasharray="2 2"/><text x="17" y="172" font-family="Georgia,serif" font-size="18" font-style="italic" fill="#284232">Wild sage</text>${line(17,180,136)}${label(17,191,"GARDEN • NORTH BED")}`;
  const receipt = `<path d="M0 0h164v191l-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-7 6-7-6-5 5Z" fill="#f2edda"/>
    <g fill="#273d3b"><g transform="translate(70 12)" fill="none" stroke="currentColor" stroke-width="2"><path d="M-8 10h19v7a9.5 9.5 0 0 1-19 0zm19 1h3a4 4 0 010 8h-3M-5 27h18M0 6q-3-2 0-5m5 6q-3-2 0-5"/></g>
    <text x="82" y="55" text-anchor="middle" font-size="16" font-weight="bold" font-family="Georgia">NIGHT SHIFT</text>${label(41,68,"COFFEE COUNTER")}${line(15,80,134)}${label(16,97,"FILTER COFFEE   4.00")}${label(16,113,"RYE TOAST       6.00")}${line(15,124,134)}
    ${label(16,144,"TOTAL          10.00")}${Array.from({length:32},(_,i)=>`<rect x="${20+i*3.8}" y="155" width="${i%3===1?2.7:1.1}" height="16"/>`).join("")}${label(41,184,"THANK YOU / 08")}</g>`;
  const ticket = `<rect width="209" height="145" rx="5" fill="#fff6d8"/><path d="M0 0h209v28H0" fill="#b85132"/><text x="13" y="19" font-size="13" font-weight="bold" fill="white" letter-spacing="2" font-family="Consolas,monospace">COASTAL RAIL</text>
    <text x="14" y="57" font-size="23" font-family="Georgia" fill="#284659">Port Meridian</text>${line(14,66,153)}${label(14,83,"BAY LINE     PLATFORM 03")}<g stroke="#376276" fill="none" stroke-width="1.6"><path d="M15 104c16-12 28 5 45-4s30-7 42 2 29 3 43-7"/><circle cx="15" cy="104" r="3"/><circle cx="145" cy="95" r="3"/></g>${label(14,128,"ONE JOURNEY   •   042")}
    <path d="M175 30v111" stroke="#b3a88b" stroke-dasharray="3 3"/>${Array.from({length:15},(_,i)=>`<path d="M182 ${36+i*6}h18" stroke="#2b4554" stroke-width="${i%3?1:3}"/>`).join("")}`;
  const specimens={folio:botanical,bench:receipt,atlas:ticket};
  function ground(k){
    if(k==="folio")return `<defs><pattern id="dots" width="9" height="9" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r=".75" fill="#7b976d" opacity=".45"/></pattern></defs><rect x="7" y="6" width="456" height="283" rx="3" fill="url(#dots)"/><path d="M25 236C90 190 135 290 205 254S325 149 458 192M1 253C146 168 96 316 208 266S335 172 470 202" fill="none" stroke="#b6c4a4"/>`;
    if(k==="bench")return `<defs><pattern id="grid" width="21" height="21" patternUnits="userSpaceOnUse"><path d="M21 0H0v21" fill="none" stroke="#4b6768" stroke-width=".65"/></pattern></defs><rect width="470" height="295" fill="#16262e"/><rect width="470" height="295" fill="url(#grid)"/><g stroke="#87a4a0" stroke-width="1">${Array.from({length:31},(_,i)=>`<path d="M${10+i*15} 5v${i%5?4:9}"/>`).join("")}</g>`;
    return `<defs><pattern id="atlasgrid" width="25" height="25" patternUnits="userSpaceOnUse" patternTransform="rotate(-19)"><path d="M25 0H0v25" fill="none" stroke="#95acb1" stroke-width="1.4"/></pattern></defs><rect width="470" height="295" fill="#d8e4d9"/><rect width="470" height="295" fill="url(#atlasgrid)"/><path d="M300-10C202 63 389 140 279 192s-86 53-97 111h102c7-30 25-48 56-66 130-73-12-202 47-247" fill="#a3c7cd"/><path d="M-15 230Q122 181 175 81T309-22M0 252Q133 216 197 99T339-22" stroke="#f4f4db" stroke-width="6" fill="none"/><path d="M20 37l88 40 49-7 40 60 94 36 127 82" stroke="#67899a" stroke-width="2" fill="none" stroke-dasharray="4 5"/>`;
  }
  function paper(k,x,y,rot,scale,mode="clean"){
    const dims=k==="atlas"?[209,145]:k==="bench"?[164,198]:[172,208];
    const original=mode==="original";
    return `<g transform="translate(${x} ${y}) rotate(${rot} ${dims[0]*scale/2} ${dims[1]*scale/2}) scale(${scale})" color="#2e463a">
      <rect x="5" y="7" width="${dims[0]}" height="${dims[1]-1}" rx="3" fill="#21382d" opacity=".19"/>${specimens[k]}
      ${original?`<path d="M0 0h${dims[0]*.85}l-${dims[0]*.64} ${dims[1]}H0Z" fill="#77674c" opacity=".28"/>`:""}
      ${mode==="frame"?`<path d="M-4 16V-4h20m${dims[0]-32} 0h20v20m0 ${dims[1]-32}v20h-20m-${dims[0]-32} 0H-4v-20" fill="none" stroke="#12825d" stroke-width="4"/>`:""}</g>`;
  }
  const colors={folio:["#345a41","#f5f2dd","#ae6b3f"],bench:["#bfe49d","#24373f","#eac383"],atlas:["#305d77","#f8f2d8","#b55032"]};
  const safe=text=>String(text).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  function stamp(x,y,t,k){let [fg,bg]=colors[k];return `<g transform="translate(${x} ${y})"><rect width="${t.length*7+20}" height="23" fill="${bg}" stroke="${fg}"/>${label(9,15,t,`style="color:${fg}"`)}</g>`}
  const folder=(x,y,color,tag)=>`<g transform="translate(${x} ${y})"><path d="M0 8q0-8 8-8h35l10 10h69q8 0 8 8v67H0z" fill="#738987"/><path d="M0 19h131l-5 72H3Z" fill="${color}" stroke="#4d615b"/>${label(10,70,tag,'style="color:#203d39"')}</g>`;
  function scene(k,page,s){
    const [fg,bg,accent]=colors[k],demo=page==="practice",entry=page==="start",t=demo?s.tool:"Original";
    let draw="",caption="";
    if(entry && s.ethos==="search" || demo && t==="Search"){
      const word={folio:"Wild sage",bench:"rye toast",atlas:"Port Meridian"}[k],query=s.query.trim(),searching=demo&&t==="Search";
      const result=!searching?"FOUND INSIDE · PAGE 1":s.ocr!=="ready"?"WAITING FOR TEXT":!query?"TYPE A PRINTED WORD":s.match?"FOUND INSIDE · PAGE 1":"NO MATCHING PAGES";
      draw=paper(k,30,45,-6,.85)+`<path d="M165 135h47" stroke="${accent}" stroke-width="2" stroke-dasharray="4 4"/><g transform="translate(204 86)"><rect width="246" height="107" rx="10" fill="${bg}" stroke="${fg}"/><circle cx="24" cy="27" r="7" fill="none" stroke="${fg}" stroke-width="2"/><path d="m30 33 5 5" stroke="${fg}" stroke-width="2"/><text x="47" y="33" fill="${fg}" font-size="20" font-family="Georgia">${safe(searching?query.slice(0,16)||"Search…":word)}</text>${line(14,50,217,fg)}${label(15,71,result,`style="color:${fg}"`)}${label(15,89,"SAVED TEXT / LOCAL",`style="color:${fg}"`)}</g>`;caption="Search the printed text of the illustrated document";
    }else if(page==="library"&&k==="atlas"){
      draw=`<path d="M44 215Q139 122 193 164T370 77" stroke="${accent}" stroke-width="4" fill="none" stroke-dasharray="6 5"/>${folder(52,124,"#d5bb80","APP STORAGE")}${folder(275,41,"#bed1bd","YOUR FOLDER")}${paper(k,185,133,-8,.75)}${stamp(78,246,s.active==="Default app storage"?"ACTIVE / APP STORAGE":"ACTIVE / YOUR FOLDER",k)}`;caption="Route between app storage and a selected folder";
    }else if(page==="library" || entry && s.ethos==="yours"){
      draw=`<g stroke="${accent}" stroke-width="3" fill="none"><path d="M126 171C172 178 204 45 256 77S291 214 341 182" stroke-dasharray="5 6"/></g>${folder(34,123,k==="bench"?"#8aafa4":"#d5bb80","ORIGINALS")}${folder(299,133,k==="bench"?"#b4cf97":"#bed1bd","LIBRARY COPY")}${paper(k,174,33,-5,.54)}${stamp(160,228,"VERIFIED COPY",k)}`;caption="A verified library folder can be kept separately";
    }else if(page==="languages"){
      draw=paper(k,53,46,-9,.82)+`<g transform="translate(209 48) rotate(3)"><rect width="195" height="140" rx="6" fill="${bg}" stroke="${fg}"/>${label(15,23,"INTERFACE",`style="color:${fg}"`)}<text x="15" y="72" font-family="Georgia" font-size="39" fill="${fg}">${s.ui==="Français"?"Bonjour":"Welcome"}</text>${line(15,89,156,fg)}${label(15,115,"MENUS & BUTTONS",`style="color:${fg}"`)}</g>${stamp(234,220,"OCR / PAGE TEXT",k)}`;caption="Interface language and printed page language are independent";
    }else if(page==="access" || entry && s.ethos==="offline"){
      draw=`<g transform="translate(122 19) rotate(-5 85 115)"><rect width="161" height="242" rx="25" fill="${bg}" stroke="${fg}" stroke-width="4"/><rect x="12" y="26" width="137" height="174" rx="8" fill="#9fae97"/>${paper(k,32,45,-6,k==="atlas"?.47:.57,"frame")}<circle cx="81" cy="219" r="13" fill="none" stroke="${fg}" stroke-width="2"/></g><circle cx="323" cy="110" r="43" fill="${bg}" stroke="${fg}" stroke-width="2"/><path d="m307 109 12 12 22-25" fill="none" stroke="${fg}" stroke-width="4"/>${stamp(290,183,page==="access"?"YOUR CHOICE":"ON DEVICE",k)}`;caption="A phone with an on-device check mark";
    }else if(demo || entry && s.ethos==="fast"){
      const raw=t==="Capture"||t==="Original",intensity=(s.stage==="saved"?s.acceptedAmount:s.amount)/100,turn=t==="Rotate"?(intensity-.5)*80:raw?-10:0;
      let doc=paper(k,k==="atlas"?105:142,k==="atlas"?72:30,turn,k==="atlas"?1.25:1.13,raw?"original":t==="Frame"?"frame":"clean");
      if(t==="Flatten"||raw)doc=`<g transform="translate(235 145) skewX(${raw?-8:(1-intensity)*-16}) scale(1,${raw?.93:.85+.15*intensity}) translate(-235 -145)">${doc}</g>`;
      if(t==="Light")doc=`<defs><linearGradient id="windowshadow"><stop stop-color="#493d32" stop-opacity="${.7*(1-intensity)}"/><stop offset="1" stop-color="#493d32" stop-opacity="0"/></linearGradient></defs>${doc}<rect x="${k==="atlas"?105:142}" y="${k==="atlas"?72:30}" width="${k==="atlas"?262:k==="bench"?185:194}" height="${k==="atlas"?181:k==="bench"?222:235}" fill="url(#windowshadow)"/>`;
      if(t==="Color")doc=`<g style="filter:grayscale(${1-intensity}) sepia(${.4*(1-intensity)}) saturate(${.8+intensity})">${doc}</g>`;
      if(t==="Frame"&&s.stage!=="auto"){
        const x=(k==="atlas"?105:142)+22*intensity,y=(k==="atlas"?72:30)+22*intensity,w=(k==="atlas"?261:k==="bench"?185:194)-44*intensity,h=(k==="atlas"?181:k==="bench"?222:235)-44*intensity;
        doc+=`<path d="M0 0h470v295H0Z M${x} ${y}h${w}v${h}H${x}Z" fill="#162620" fill-rule="evenodd" opacity=".34"/><rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" stroke="#91efb1" stroke-width="3"/>`;
      }
      draw=doc;
      if(k==="bench"||t==="Frame")draw+=`<path d="M81 70V39h33m242 0h33v31m0 155v31h-33m-242 0H81v-31" fill="none" stroke="${fg}" stroke-width="2.4"/>`;
      draw+=stamp(18,249,t==="Capture"?"01 / ACQUIRED":t==="Original"?"SOURCE / INTACT":t.toUpperCase()+" / PREVIEW",k);caption="The sample document in the selected tool";
    }else if(page==="home" || page==="settings"){
      draw=folder(174,121,k==="bench"?"#b9cfac":"#c9b684","MY SCANS")+paper(k,118,36,-12,.8)+paper(k,234,80,10,.72)+stamp(115,245,"ORIGINAL INCLUDED",k);caption="Your sample album";
    }else{
      const raw=s.view==="Original";
      if(k==="folio")draw=paper(k,97,37,-13,1,"original")+paper(k,213,45,raw?7:1,1.03,raw?"original":"clean")+`<path d="M45 92q17-19 49-19m-7-6 8 6-8 5" stroke="#526d53" fill="none"/>`+stamp(25,173,"SOURCE",k)+stamp(302,254,raw?"ORIGINAL":"EDITABLE",k);
      if(k==="bench")draw=paper(k,155,29,raw?-9:0,1.18,raw?"original":"frame")+`<g fill="none" stroke="${fg}" stroke-width="2"><path d="M90 68V39h34m220 0h33v29m0 155v31h-33m-220 0H90v-31"/></g>`+stamp(16,22,"CAM 01",k)+stamp(308,242,raw?"SOURCE":"PROCESSED",k);
      if(k==="atlas")draw=`<path d="M42 239Q109 181 128 211t94-10 184-78" fill="none" stroke="${accent}" stroke-width="3" stroke-dasharray="5 5"/><circle cx="42" cy="239" r="8" fill="${fg}"/><circle cx="406" cy="123" r="8" fill="${accent}"/>`+paper(k,92,77,-12,1.13,"original")+paper(k,147,54,7,1.16,raw?"original":"clean")+stamp(41,253,"DEPARTURE",k)+stamp(330,208,"ARCHIVE",k);
      caption="The unchanged original and a separately editable scan";
    }
    return svg(ground(k)+draw,caption);
  }
  const paths={camera:'<path d="M3 8h4l2-3h6l2 3h4v12H3z"/><circle cx="12" cy="13.5" r="3.5"/>',folder:'<path d="M3 6h6l2 3h10v11H3z"/>',album:'<rect x="5" y="4" width="15" height="16" rx="2"/><path d="M2 7v16h15m-9-8 3-4 5 6"/>',arrow:'<path d="M3 12h16m-6-6 6 6-6 6"/>',settings:'<path d="M3 6h18M3 12h18M3 18h18"/><path d="M8 3v6m8 0v6M9 15v6" stroke-width="3"/>',read:'<path d="M3 5h7l2 2 2-2h7v14h-7l-2 2-2-2H3z"/>'};
  function icon(name){return `<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${paths[name]||paths.folder}</svg>`}
  return {scene,icon};
})();
