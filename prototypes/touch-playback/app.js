const variants=[{key:"A",name:"Familiar cinema"},{key:"B",name:"Thumb zones"},{key:"C",name:"Control dock"}];
const valid=(v,x,d)=>v.includes(x)?x:d,p=new URLSearchParams(location.search);
const player=document.querySelector("#player"),device=document.querySelector("#device"),label=document.querySelector("#variant-label");
function url(){const u=new URL(location);u.searchParams.set("variant",player.dataset.variant);u.searchParams.set("width",[...device.classList].find(x=>["phone","split","tablet","fold"].includes(x)));u.searchParams.set("state",player.dataset.state);history.replaceState({},"",u)}
function variant(k){const v=variants.find(x=>x.key===k)||variants[0];player.dataset.variant=v.key;label.textContent=`${v.key} — ${v.name}`;url()}
function cycle(n){const i=variants.findIndex(x=>x.key===player.dataset.variant);variant(variants[(i+n+variants.length)%variants.length].key)}
function width(w){device.className=`device ${w}`;document.querySelectorAll("[data-width]").forEach(b=>b.setAttribute("aria-pressed",b.dataset.width===w));url()}
function state(s){player.dataset.state=s;document.querySelectorAll("[data-state]").forEach(b=>b.setAttribute("aria-pressed",b.dataset.state===s));document.querySelector("#play").textContent=s==="paused"?"▶":"Ⅱ";url()}
document.querySelector("#prev").onclick=()=>cycle(-1);document.querySelector("#next").onclick=()=>cycle(1);
document.querySelectorAll("[data-width]").forEach(b=>b.onclick=()=>width(b.dataset.width));document.querySelectorAll("[data-state]").forEach(b=>b.onclick=()=>state(b.dataset.state));
document.querySelector("#play").onclick=()=>state(player.dataset.state==="paused"?"playing":"paused");document.querySelector(".tracks").onclick=()=>state("tracks");document.querySelector(".danmaku").onclick=()=>state("danmaku");document.querySelector(".close").onclick=()=>state("paused");
document.addEventListener("keydown",e=>{if(["INPUT","TEXTAREA"].includes(document.activeElement.tagName)||document.activeElement.isContentEditable)return;if(e.key==="ArrowLeft")cycle(-1);if(e.key==="ArrowRight")cycle(1)});
variant(valid(variants.map(x=>x.key),p.get("variant"),"A"));width(valid(["phone","split","tablet","fold"],p.get("width"),"phone"));state(valid(["playing","paused","tracks","danmaku"],p.get("state"),"playing"));
