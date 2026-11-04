/* ============ CIELO Y CLIMA portable (extraído de AutoBoard Next v176) ============
   Mismo motor que autoboard-next, pero sin MapLibre: sirve para cualquier escena 3D (three.js, etc.).

   Qué hace
   · Cielo según estación + posición real del sol (amanecer / día / atardecer / noche).
   · Tiempo real de Open-Meteo (nubes, lluvia, nieve, niebla) cada 10 min, o forzado a mano.
   · Degradado de cielo + nubes (sprites suaves, grises con mal tiempo, estrellas de noche) DETRÁS del canvas 3D.
   · Lluvia / nieve en un canvas ligero (≤140 partículas, ~30 fps) solo mientras hay precipitación.
   · No toca tu escena: te devuelve la paleta y tú pintas niebla/luces (callback alCambiar).

   Cómo se usa
     <div id="vista" style="position:relative"> <canvas (WebGL con alpha:true, z-index:1)> </div>
     const cc = CieloClima.crear({
       contenedor: document.getElementById('vista'),
       horizonteY: () => yDelHorizonteEnPixeles,      // desde el borde superior del contenedor
       posicion:   () => [lat, lon],                   // GPS del coche (si no, Bilbao)
       alCambiar:  (P, st) => { scene.fog.color.set(P.fog); ... }   // P.sky, P.hor, P.fog, st.sol (0..1), st.noche
     });
     cada frame:  cc.sync();     // barato: solo redibuja si el horizonte o el tiempo cambian
     botones:     cc.ciclarCielo(); cc.ciclarTiempo();
   Requisito: renderer = new THREE.WebGLRenderer({alpha:true}) y scene.background = null,
   para que se vea el cielo que hay detrás. */
(function(root){
'use strict';
function crear(opt){
  opt=opt||{};
  const cont=opt.contenedor, pref=opt.prefijo||'cielo', persistir=opt.persistir!==false;
  const horizonteY=opt.horizonteY||(()=>cont.clientHeight*0.45);
  const posicion=opt.posicion||(()=>[43.26,-2.93]);
  const alCambiar=opt.alCambiar||(()=>{});
  const activo=opt.activo||(()=>true);
  const rd=(k,d)=>{ if(!persistir) return d; try{ const v=localStorage.getItem(pref+'_'+k); return v===null?d:v; }catch(e){ return d; } };
  const wr=(k,v)=>{ if(!persistir) return; try{ localStorage.setItem(pref+'_'+k,v); }catch(e){} };
  const CIELOS=['auto','dia','atardecer','noche'], CN={auto:'Auto',dia:'Día',atardecer:'Atardecer',noche:'Noche'};
  const TIEMPOS=['real','despejado','nublado','lluvia','niebla','nieve','off'], TN={real:'Real',despejado:'Despejado',nublado:'Nublado',lluvia:'Lluvia',niebla:'Niebla',nieve:'Nieve',off:'Sin efectos'};
  let cielo=rd('modo',opt.cielo||'auto'); if(CIELOS.indexOf(cielo)<0) cielo='auto';
  let tiempoSel=rd('tiempo',opt.tiempo||'real'); if(TIEMPOS.indexOf(tiempoSel)<0) tiempoSel='real';
  let met={ nubes:0, lluvia:0, nieve:0, niebla:0, ok:false };      /* 0..1 */
  const hex=h=>{ h=h.replace('#',''); return [parseInt(h.slice(0,2),16),parseInt(h.slice(2,4),16),parseInt(h.slice(4,6),16)]; };
  const mix=(a,b,t)=>{ t=Math.max(0,Math.min(1,t)); const A=hex(a),B=hex(b); return '#'+A.map((v,i)=>Math.round(v+(B[i]-v)*t).toString(16).padStart(2,'0')).join(''); };
  /* paletas por estación: primavera, verano, otoño, invierno */
  const DIA=[ {sky:'#c9dff0',hor:'#f1f5ef',fog:'#e9eeec'}, {sky:'#b4d3ee',hor:'#f6f4e8',fog:'#eeeee6'}, {sky:'#d6d9db',hor:'#f3e6d2',fog:'#eee5d8'}, {sky:'#cdd5de',hor:'#f0f3f6',fog:'#eceff2'} ];
  const ATAR={sky:'#7f93b8',hor:'#f4a261',fog:'#ecbd98'}, ALBA={sky:'#9fb0cc',hor:'#f6b7a2',fog:'#efcdbd'}, NOCHE0={sky:'#1b2128',hor:'#2a323c',fog:'#252c35'};
  const NOCHE=Object.assign({},NOCHE0,opt.noche||{});   /* opt.noche: {sky,hor,fog} para conservar el azul de noche de tu app */
  function estacionAuto(d){ d=d||new Date(); const m=d.getMonth()+1, dia=d.getDate(), v=m*100+dia; return v>=321&&v<621?0:v>=621&&v<923?1:v>=923&&v<1221?2:3; }
  function elevSol(lat,lon,d){
    const R=Math.PI/180, n=d.getTime()/86400000+2440587.5-2451545;
    const L=(280.46+0.9856474*n)%360, g=((357.528+0.9856003*n)%360)*R, lam=(L+1.915*Math.sin(g)+0.02*Math.sin(2*g))*R, eps=23.439*R;
    const dec=Math.asin(Math.sin(eps)*Math.sin(lam)), ra=Math.atan2(Math.cos(eps)*Math.sin(lam),Math.cos(lam));
    const gm=(280.46061837+360.98564736629*n)%360; let H=((gm+lon)*R-ra)%(2*Math.PI); if(H>Math.PI) H-=2*Math.PI; if(H<-Math.PI) H+=2*Math.PI;
    const e=Math.asin(Math.sin(lat*R)*Math.sin(dec)+Math.cos(lat*R)*Math.cos(dec)*Math.cos(H))/R;
    return { elev:e, tarde:H>0 };
  }
  function pos(){ try{ const p=posicion(); if(p&&isFinite(p[0])&&isFinite(p[1])) return p; }catch(e){} return [43.26,-2.93]; }
  function estacion(){ try{ const v=opt.estacion&&opt.estacion(); if(v!=null&&v>=0&&v<=3) return v; }catch(e){} return estacionAuto(); }
  function fase(){
    if(cielo==='dia') return {elev:40,tarde:true};
    if(cielo==='atardecer') return {elev:1,tarde:true};
    if(cielo==='noche') return {elev:-30,tarde:true};
    const p=pos(); return elevSol(p[0],p[1],opt.fecha?opt.fecha():new Date());
  }
  function tiempoEfectivo(){
    switch(tiempoSel){ case 'despejado': return {nubes:0,lluvia:0,nieve:0,niebla:0};
      case 'nublado': return {nubes:0.85,lluvia:0,nieve:0,niebla:0}; case 'lluvia': return {nubes:1,lluvia:0.7,nieve:0,niebla:0};
      case 'niebla': return {nubes:0.6,lluvia:0,nieve:0,niebla:0.9}; case 'nieve': return {nubes:1,lluvia:0,nieve:0.7,niebla:0.2};
      case 'off': return {nubes:0,lluvia:0,nieve:0,niebla:0}; default: return met; }
  }
  function paleta(){
    const f=fase(), e=f.elev, d=DIA[estacion()], tw=f.tarde?ATAR:ALBA; let c;
    if(e>=12) c=d;
    else if(e>=3) c={sky:mix(d.sky,tw.sky,(12-e)/9),hor:mix(d.hor,tw.hor,(12-e)/9),fog:mix(d.fog,tw.fog,(12-e)/9)};
    else if(e>=-3) c=tw;
    else if(e>-9) c={sky:mix(tw.sky,NOCHE.sky,(-3-e)/6),hor:mix(tw.hor,NOCHE.hor,(-3-e)/6),fog:mix(tw.fog,NOCHE.fog,(-3-e)/6)};
    else c=NOCHE;
    const w=tiempoEfectivo(), oscuro=e<-3?1:0;
    const gris=oscuro?(opt.noche?{sky:mix(NOCHE.sky,'#000000',0.3),hor:mix(NOCHE.hor,'#000000',0.3),fog:mix(NOCHE.fog,'#000000',0.3)}:{sky:'#14181d',hor:'#1d232a',fog:'#1a1f25'}):{sky:'#a9b0b8',hor:'#c9ced3',fog:'#c4c9ce'};
    const k=Math.min(1,w.nubes*0.55+w.lluvia*0.45+w.nieve*0.2+w.niebla*0.3);
    c={sky:mix(c.sky,gris.sky,k),hor:mix(c.hor,gris.hor,k),fog:mix(c.fog,gris.fog,k)};
    const nb=w.niebla;
    if(nb>0.05){ c={sky:mix(c.sky,c.fog,nb*0.9),hor:mix(c.hor,c.fog,nb),fog:c.fog}; }
    return { sky:c.sky, hor:c.hor, fog:c.fog, skyH:0.6+nb*0.4, horFog:0.85+nb*0.15, fogG:0.55+nb*0.4+w.lluvia*0.1 };
  }
  function nieblaDensa(){ return tiempoEfectivo().niebla>0.5; }
  function estado(){
    const f=fase(), w=tiempoEfectivo();
    return { cielo, tiempoSel, met, w, fase:f, noche:f.elev<-3, sol:0.3+0.7*Math.max(0,Math.min(1,(f.elev+3)/20)), estacion:estacion(), nieblaDensa:w.niebla>0.5,
             textoCielo:CN[cielo], textoTiempo:TN[tiempoSel] };
  }
  function aplicar(){ clave=''; hzUlt=-1; lluvia.sync(); overlay.sync(true); try{ alCambiar(paleta(),estado()); }catch(e){} }

  /* ---- Open-Meteo: tiempo actual en la posición ---- */
  let ultimaPeticion=0;
  function pedirTiempo(forzar){
    if(tiempoSel!=='real') return;
    const ahora=Date.now(); if(!forzar && ahora-ultimaPeticion<600000) return; ultimaPeticion=ahora;
    const p=pos();
    const url='https://api.open-meteo.com/v1/forecast?latitude='+p[0].toFixed(3)+'&longitude='+p[1].toFixed(3)+'&current=weather_code,cloud_cover,precipitation,snowfall,visibility&timezone=auto';
    fetch(url).then(r=>r.ok?r.json():null).then(j=>{
      if(!j||!j.current) return; const c=j.current, code=c.weather_code|0, pr=+c.precipitation||0, sn=+c.snowfall||0, vis=c.visibility==null?99999:+c.visibility;
      const nieve=(code>=71&&code<=77)||code===85||code===86||sn>0.05;
      const lluv=!nieve&&((code>=51&&code<=67)||(code>=80&&code<=82)||code>=95||pr>0.05);
      const inten=Math.min(1,0.3+pr/3);
      met={ nubes:Math.min(1,(+c.cloud_cover||0)/100), lluvia:lluv?inten:0, nieve:nieve?Math.max(0.35,Math.min(1,0.3+sn/1.5)):0,
            niebla:(code===45||code===48||vis<1000)?(vis<400?1:0.8):(vis<3000?0.35:0), ok:true, codigo:code };
      aplicar();
    }).catch(()=>{});
  }

  /* ---- lluvia / nieve: canvas ligero, solo mientras hay precipitación ---- */
  const lluvia=(function(){
    let cv=null, cx=null, ps=[], raf=0, ult=0, modo='';
    function crearCv(){ cv=document.createElement('canvas'); cv.style.cssText='position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:3;'; cont.appendChild(cv); cx=cv.getContext('2d'); ajustar(); window.addEventListener('resize',ajustar); }
    function ajustar(){ if(!cv) return; cv.width=Math.max(1,Math.round(cont.clientWidth/2)); cv.height=Math.max(1,Math.round(cont.clientHeight/2)); }
    function sync(){
      if(opt.particulas===false) return;                 /* tu escena ya tiene su propia lluvia/nieve (p. ej. partículas 3D) */
      const w=tiempoEfectivo(), inten=Math.max(w.lluvia,w.nieve), m=w.nieve>w.lluvia?'nieve':'lluvia';
      if(inten<0.05){ parar(); return; }
      if(!cv) crearCv();
      const n=Math.round(30+inten*110); modo=m;
      while(ps.length<n) ps.push({x:Math.random()*cv.width,y:Math.random()*cv.height,v:0.6+Math.random()*0.8,l:6+Math.random()*10});
      ps.length=n; if(!raf){ ult=0; raf=requestAnimationFrame(paso); }
    }
    function parar(){ if(raf){ cancelAnimationFrame(raf); raf=0; } ps=[]; if(cx&&cv) cx.clearRect(0,0,cv.width,cv.height); }
    function paso(t){
      raf=requestAnimationFrame(paso); if(t-ult<33) return; const dt=Math.min(0.1,(t-ult)/1000||0.033); ult=t;
      const W=cv.width,H=cv.height, noche=fase().elev<-3;
      cx.clearRect(0,0,W,H);
      if(modo==='lluvia'){ cx.strokeStyle=noche?'rgba(205,220,235,0.40)':'rgba(120,138,158,0.50)'; cx.lineWidth=1; cx.beginPath();
        for(const p of ps){ p.y+=p.v*H*1.1*dt; p.x-=p.v*H*0.12*dt; if(p.y>H){ p.y=-p.l; p.x=Math.random()*(W+40); } if(p.x<-10) p.x+=W+20; cx.moveTo(p.x,p.y); cx.lineTo(p.x+p.l*0.12,p.y-p.l); } cx.stroke(); }
      else { cx.fillStyle=noche?'rgba(235,242,250,0.8)':'rgba(255,255,255,0.95)';
        for(const p of ps){ p.y+=p.v*H*0.16*dt; p.x+=Math.sin((p.y+p.l*7)/22)*0.35; if(p.y>H){ p.y=-4; p.x=Math.random()*W; } cx.beginPath(); cx.arc(p.x,p.y,1+p.v*1.1,0,6.283); cx.fill(); } }
    }
    return {sync,parar};
  })();

  /* ---- franja de cielo continua hasta el horizonte + nubes ---- */
  let clave='', hzUlt=-1;
  const overlay=(function(){
    let ov=null, g=null, nb=null, ovAct=false;
    const rgba=(h,a)=>{ const c=hex(h); return 'rgba('+c[0]+','+c[1]+','+c[2]+','+a+')'; };
    function sprite(w,h,col,som,seed){
      const c=document.createElement('canvas'); c.width=w; c.height=h; const x=c.getContext('2d'); let r=seed;
      const rnd=()=>{ r=(r*16807)%2147483647; return r/2147483647; };
      const n=8, bl=[]; for(let i=0;i<n;i++){ const t=(i+0.5)/n, rad=h*(0.20+0.20*Math.sin(Math.PI*(0.1+0.8*t))+0.05*rnd()); bl.push({cx:Math.max(rad+2,Math.min(w-rad-2,w*(0.15+0.7*t))),cy:h*0.66-rad*(0.6*rnd()+Math.sin(Math.PI*t)*0.5),rad}); }
      for(const pass of [0,1]) for(const b of bl){
        const cy=b.cy+(pass?0:h*0.06), cc=pass?col:som, gr=x.createRadialGradient(b.cx,cy,b.rad*0.1,b.cx,cy,b.rad);
        gr.addColorStop(0,rgba(cc,pass?0.9:0.5)); gr.addColorStop(0.5,rgba(cc,(pass?0.9:0.5)*0.6)); gr.addColorStop(1,rgba(cc,0));
        x.fillStyle=gr; x.beginPath(); x.arc(b.cx,cy,b.rad,0,6.283); x.fill();
      }
      return c.toDataURL();
    }
    function crearOv(){
      if(ov) return; ov=document.createElement('div'); ov.style.cssText='position:absolute;left:0;top:0;width:100%;height:0;pointer-events:none;overflow:hidden;display:none;z-index:0;';
      g=document.createElement('div'); g.style.cssText='position:absolute;inset:0;'; nb=document.createElement('div'); nb.style.cssText='position:absolute;inset:0;animation:'+pref+'Drift 150s linear infinite alternate;will-change:transform;';
      ov.appendChild(g); ov.appendChild(nb); cont.insertBefore(ov,cont.firstChild);
      const st=document.createElement('style'); st.textContent='@keyframes '+pref+'Drift{from{transform:translateX(0)}to{transform:translateX(-70px)}}@media (prefers-reduced-motion:reduce){#'+(cont.id||'x')+' *{animation:none!important}}'; document.head.appendChild(st);
    }
    function sync(forzar){
      const hz=horizonteY(), W=cont.clientWidth;
      if(!forzar && Math.round(hz)===hzUlt) return; hzUlt=Math.round(hz);
      if(!activo()||!(hz>=24)){ if(ov) ov.style.display='none'; return; }
      crearOv(); ov.style.display='block'; ov.style.height=Math.round(hz+60)+'px'; ovAct=true;
      const P=paleta(), f=fase(), e=f.elev, w=tiempoEfectivo(), noche=e<-3, atar=e<12&&e>=-3;
      const cover=Math.max(w.nubes,w.lluvia,w.nieve), gris=cover>0.6, limpio=cover<0.5&&w.niebla<0.4;
      const top=noche?(opt.noche?mix(NOCHE.sky,'#000000',0.35):'#0c1118'):(atar?(f.tarde?'#4f6a96':'#7f92b6'):(limpio?'#8fbbe6':P.sky));
      const mid=(limpio&&!noche&&!atar)?'#bcd8ef':P.sky;
      const k=[noche,atar,f.tarde,limpio,gris,Math.round(cover*5),Math.round(w.niebla*3),P.sky,P.hor,W,Math.round(hz/30)].join('|');
      const suelo=(opt.colorSuelo&&opt.colorSuelo())||P.fog, pct=Math.round(hz/(hz+60)*100);
      g.style.background='linear-gradient(to bottom,'+top+' 0%,'+mid+' 42%,'+P.hor+' '+Math.max(10,pct-14)+'%,'+suelo+' '+pct+'%,'+suelo+' 100%)';
      if(k===clave) return; clave=k; nb.innerHTML='';
      if(w.niebla>0.6) return;                                            /* niebla espesa: sin nubes, solo bruma */
      const claro=noche?'#46556a':(atar?(f.tarde?'#fbd9c0':'#fbd0c8'):'#ffffff'), sombra=noche?'#1d2631':(atar?(f.tarde?'#c98f86':'#c9a0a8'):'#cfd8e1');
      const col=gris?(noche?'#2c3541':'#c3cad1'):claro, som=gris?(noche?'#171c23':'#9aa3ac'):sombra;
      const N=Math.min(14,(noche?6:8)+Math.round(cover*6));
      for(let i=0;i<N;i++){
        const t=(i*0.6180339)%1, y=hz*(0.08+0.8*((i*0.37)%1)), esc=0.35+0.9*(y/hz), ww=Math.round((220+120*((i*0.73)%1))*esc), hh=Math.round(ww*0.42);
        const im=document.createElement('img'); im.src=sprite(ww,hh,col,som,1000+i*37);
        im.style.cssText='position:absolute;left:'+Math.round(t*W-ww/2)+'px;top:'+Math.round(y-hh*0.5)+'px;width:'+ww+'px;height:'+hh+'px;max-width:none;opacity:'+(noche?0.45:0.95-0.25*(1-y/hz))+';';
        nb.appendChild(im);
      }
      if(noche&&cover<0.6){ for(let i=0;i<45;i++){ const d=document.createElement('div'), sz=1+((i*7)%3)*0.6; d.style.cssText='position:absolute;border-radius:50%;background:#fff;opacity:'+(0.2+((i*13)%10)/22)+';width:'+sz+'px;height:'+sz+'px;left:'+((i*137)%W)+'px;top:'+Math.round(((i*53)%100)/100*hz*0.8)+'px;'; nb.appendChild(d); } }
    }
    return {sync};
  })();

  function ciclarCielo(){ cielo=CIELOS[(CIELOS.indexOf(cielo)+1)%CIELOS.length]; wr('modo',cielo); aplicar(); return CN[cielo]; }
  function ciclarTiempo(){ tiempoSel=TIEMPOS[(TIEMPOS.indexOf(tiempoSel)+1)%TIEMPOS.length]; wr('tiempo',tiempoSel); if(tiempoSel==='real') pedirTiempo(true); aplicar(); return TN[tiempoSel]; }
  function ponerCielo(c){ if(c!==cielo&&CIELOS.indexOf(c)>=0){ cielo=c; wr('modo',c); aplicar(); } }
  function ponerTiempo(t){ if(t!==tiempoSel&&TIEMPOS.indexOf(t)>=0){ tiempoSel=t; wr('tiempo',t); if(t==='real') pedirTiempo(true); aplicar(); } }
  const iniciar=()=>{ pedirTiempo(true); aplicar(); setInterval(()=>{ try{ pedirTiempo(false); }catch(e){} },60000); };
  setTimeout(iniciar,0);
  return { sync:()=>overlay.sync(false), aplicar, refrescar:()=>pedirTiempo(true), paleta, estado, nieblaDensa, ciclarCielo, ciclarTiempo, ponerCielo, ponerTiempo,
           ponerMet:(m)=>{ met=Object.assign({nubes:0,lluvia:0,nieve:0,niebla:0,ok:true},m); aplicar(); }, CIELOS, TIEMPOS, CN, TN };
}
root.CieloClima={crear};
})(window);
